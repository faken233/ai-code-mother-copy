package com.yupi.yuaicodemother.task;

import com.yupi.yuaicodemother.model.entity.AppAccessRoute;
import com.yupi.yuaicodemother.sandbox.PreviewLifecycleService;
import com.yupi.yuaicodemother.service.AppRouteService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.List;

/**
 * 预览容器回收任务。
 * <p>
 * 两条回收规则（部署容器不参与，正式上线的应用一直跑）：
 * 1. 离开页面超过 {@code leave-timeout-seconds}（默认 2 分钟）→ 停容器，路由置 STOPPED
 * 2. 还在页面但超过 {@code idle-timeout-seconds}（默认 15 分钟）没有改动 → 停容器，前端弹窗说明
 * <p>
 * 有生成任务在跑时不回收；休眠只改路由状态，不删域名，恢复后链接不变。
 */
@Slf4j
@Component
public class PreviewReaperJob {

    @Resource
    private PreviewLifecycleService previewLifecycleService;

    @Resource
    private AppRouteService appRouteService;

    @Value("${preview.lifecycle.enabled:true}")
    private boolean enabled;

    @Value("${preview.lifecycle.leave-timeout-seconds:120}")
    private long leaveTimeoutSeconds;

    @Value("${preview.lifecycle.idle-timeout-seconds:900}")
    private long idleTimeoutSeconds;

    @Scheduled(fixedDelayString = "${preview.lifecycle.scan-interval-ms:30000}", initialDelayString = "30000")
    public void scan() {
        if (!enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        List<AppAccessRoute> routes = appRouteService.listPreviewRoutes();
        for (AppAccessRoute route : routes) {
            if (!AppRouteService.STATUS_READY.equals(route.getStatus())) {
                continue;
            }
            Long appId = route.getAppId();
            if (appId == null) {
                continue;
            }
            // 生成中不回收
            if (previewLifecycleService.hasActiveTask(appId)) {
                continue;
            }

            long routeTime = route.getUpdateTime() == null
                    ? 0L
                    : route.getUpdateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            Long seen = previewLifecycleService.lastSeenOf(appId);
            Long modified = previewLifecycleService.lastModifiedOf(appId);

            long lastSeenAt = Math.max(seen == null ? 0L : seen, routeTime);
            long lastModifiedAt = Math.max(modified == null ? 0L : modified, routeTime);

            if (now - lastSeenAt > leaveTimeoutSeconds * 1000L) {
                previewLifecycleService.sleep(appId, "left");
            } else if (now - lastModifiedAt > idleTimeoutSeconds * 1000L) {
                previewLifecycleService.sleep(appId, "idle");
            }
        }
    }
}
