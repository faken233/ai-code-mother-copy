package com.yupi.yuaicodemother.task;

import com.yupi.yuaicodemother.model.entity.AppAccessRoute;
import com.yupi.yuaicodemother.sandbox.PreviewLifecycleService;
import com.yupi.yuaicodemother.sandbox.SandboxContainerManager;
import com.yupi.yuaicodemother.service.AppDeployService;
import com.yupi.yuaicodemother.service.AppRouteService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 路由对账任务（P6）。
 * <p>
 * 解决的问题：路由表里的 {@code upstream} 是"某次启动时"回读的宿主端口，
 * 而 Docker 重启 / 容器重建后随机端口会变，proxy 仍然往旧端口转发 → 502。
 * 之前只有"用户再点一次预览"才能自愈，现在由本任务定期把实际端口写回 DB + Redis。
 * <p>
 * 规则：
 * 1. 只处理 status=READY 的路由，休眠中的路由不会被顺手唤醒；
 * 2. 预览容器不存在/已停止 → 标记 STOPPED（链接保留，proxy 返回 503，前端提示恢复）；
 * 3. 有生成任务在跑时跳过该应用，避免和沙箱搭建抢状态；
 * 4. 部署容器只刷新端口，不回收、不改状态（正式上线必须常驻）。
 */
@Slf4j
@Component
public class RouteReconcileJob {

    @Resource
    private AppRouteService appRouteService;

    @Resource
    private SandboxContainerManager sandboxContainerManager;

    @Resource
    private PreviewLifecycleService previewLifecycleService;

    @Resource
    private AppDeployService appDeployService;

    @Value("${route.reconcile.enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${route.reconcile.interval-ms:60000}",
            initialDelayString = "${route.reconcile.initial-delay-ms:20000}")
    public void scan() {
        if (!enabled) {
            return;
        }
        List<AppAccessRoute> routes;
        try {
            routes = appRouteService.listAllRoutes();
        } catch (Exception e) {
            log.error("路由对账扫描失败: {}", e.getMessage());
            return;
        }
        if (routes.isEmpty()) {
            return;
        }
        // Docker 短暂不可用时所有 inspect 都会失败，不能误判成"容器已删"而休眠全部预览
        if (!sandboxContainerManager.dockerAvailable()) {
            log.warn("Docker 不可用，跳过本轮路由对账");
            return;
        }
        for (AppAccessRoute route : routes) {
            if (!AppRouteService.STATUS_READY.equals(route.getStatus()) || route.getAppId() == null) {
                continue;
            }
            try {
                if (AppRouteService.KIND_PREVIEW.equals(route.getKind())) {
                    reconcilePreview(route);
                } else if (AppRouteService.KIND_DEPLOY.equals(route.getKind())) {
                    appDeployService.reconcileActiveDeployment(route.getAppId());
                }
            } catch (Exception e) {
                log.warn("路由对账单条失败: appId={}, host={}, err={}",
                        route.getAppId(), route.getHost(), e.getMessage());
            }
        }
    }

    private void reconcilePreview(AppAccessRoute route) {
        Long appId = route.getAppId();
        // 正在搭沙箱/生成时容器可能刚创建、正在重启，交给生成链路自己维护状态；
        // 只是排队中的任务不算，不能因为排队就保留一条指向死容器的路由。
        if (previewLifecycleService.hasExecutingTask(appId)) {
            return;
        }
        if (!sandboxContainerManager.containerExists(appId)) {
            log.warn("预览容器不存在，标记休眠: appId={}, host={}", appId, route.getHost());
            previewLifecycleService.sleep(appId, "container-missing");
            return;
        }
        if (!sandboxContainerManager.isContainerRunning(appId)) {
            log.warn("预览容器已停止，标记休眠: appId={}, host={}", appId, route.getHost());
            previewLifecycleService.sleep(appId, "container-stopped");
            return;
        }
        String actual = sandboxContainerManager.refreshAppBaseUrl(appId);
        appRouteService.refreshPreviewUpstream(appId, actual);
    }
}
