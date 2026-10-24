package com.yupi.yuaicodemother.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.yupi.yuaicodemother.exception.BusinessException;
import com.yupi.yuaicodemother.exception.ErrorCode;
import com.yupi.yuaicodemother.mapper.AppAccessRouteMapper;
import com.yupi.yuaicodemother.model.entity.AppAccessRoute;
import com.yupi.yuaicodemother.sandbox.SandboxContainerManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 应用访问路由服务。
 * <p>
 * 职责：把「应用 → 容器上游」映射成「域名 → 容器上游」，并同时写两处：
 * - MySQL {@code app_access_route}：权威记录，便于排查与后续 TTL 回收；
 * - Redis {@code route:{host}}：proxy 的唯一读取来源（带 TTL，避免主服务停机后链接永远可用）。
 * <p>
 * 约定：proxy 不查库、不调主服务，所以这里写 Redis 必须与写库同样及时。
 */
@Slf4j
@Service
public class AppRouteService {

    public static final String KIND_PREVIEW = "PREVIEW";
    public static final String KIND_DEPLOY = "DEPLOY";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_STOPPED = "STOPPED";

    private static final String REDIS_PREFIX = "route:";
    /** 预览域名随机段字符集：小写字母 + 数字，避免大小写歧义 */
    private static final char[] CODE_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final int CODE_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Resource
    private AppAccessRouteMapper appAccessRouteMapper;

    @Resource
    private SandboxContainerManager sandboxContainerManager;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 预览域名后缀，如 pv.localhost */
    @Value("${code.preview-host-suffix:pv.localhost}")
    private String previewHostSuffix;

    /** 访问代理的端口（对外暴露的端口，不是容器端口） */
    @Value("${code.preview-port:8124}")
    private int previewPort;

    @Value("${code.preview-scheme:http}")
    private String previewScheme;

    /** 预览路由 TTL（小时） */
    @Value("${code.preview-ttl-hours:24}")
    private int previewTtlHours;

    /** 部署域名后缀，如 app.localhost */
    @Value("${code.deploy-host-suffix:app.localhost}")
    private String deployHostSuffix;

    @Value("${code.deploy-port:8124}")
    private int deployPort;

    @Value("${code.deploy-scheme:http}")
    private String deployScheme;

    /**
     * 确保预览路由存在（幂等），返回可直接给浏览器的预览地址。
     * <p>
     * 每次调用都会刷新 upstream（容器重建后端口可能变化）与过期时间。
     *
     * @param appId 应用 ID
     * @return 形如 http://k3m9x2q7.pv.localhost:8124/
     */
    public String ensurePreviewRoute(Long appId) {
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 ID 错误");
        }
        AppAccessRoute existing = findPreview(appId);
        String host = (existing != null) ? existing.getHost() : allocatePreviewHost();
        String upstream = sandboxContainerManager.appBaseUrl(appId);
        LocalDateTime expireAt = LocalDateTime.now().plusHours(previewTtlHours);

        AppAccessRoute route = AppAccessRoute.builder()
                .host(host)
                .appId(appId)
                .kind(KIND_PREVIEW)
                .upstream(upstream)
                .status(STATUS_READY)
                .expireAt(expireAt)
                // 显式赋值：MyBatis-Flex 的 insert 会把 null 一并写入，绕开 DDL 的 DEFAULT CURRENT_TIMESTAMP
                .updateTime(LocalDateTime.now())
                .build();
        if (existing == null) {
            appAccessRouteMapper.insert(route);
        } else {
            appAccessRouteMapper.update(route);
        }
        removeDuplicatePreviewRoutes(appId, host);
        writeRedis(route);

        String url = buildUrl(host);
        log.info("预览路由就绪: appId={}, host={}, upstream={}, url={}", appId, host, upstream, url);
        return url;
    }

    /**
     * 删除某应用的预览路由（DB + Redis）。
     */
    public void removePreviewRoute(Long appId) {
        AppAccessRoute existing = findPreview(appId);
        if (existing == null) {
            return;
        }
        removeRouteByHost(existing.getHost());
        log.info("预览路由已删除: appId={}, host={}", appId, existing.getHost());
    }

    /**
     * 所有预览路由（回收任务扫描用）
     */
    public java.util.List<AppAccessRoute> listPreviewRoutes() {
        return appAccessRouteMapper.selectListByQuery(QueryWrapper.create().eq("kind", KIND_PREVIEW));
    }

    /**
     * 所有路由（对账任务扫描用：预览 + 部署）
     */
    public List<AppAccessRoute> listAllRoutes() {
        return appAccessRouteMapper.selectListByQuery(QueryWrapper.create());
    }

    /**
     * 刷新预览路由的上游端口（容器重启/重建后宿主端口可能变化）。
     * <p>
     * 只改 upstream 与 updateTime，host / status / expireAt 保持原样：
     * 休眠中的路由不会被对账任务"顺手唤醒"。
     *
     * @return 是否真的发生了变化
     */
    public boolean refreshPreviewUpstream(Long appId, String upstream) {
        return refreshUpstream(appId, KIND_PREVIEW, upstream);
    }

    /**
     * 刷新部署路由的上游端口（Docker 重启后部署容器也会换随机端口）。
     */
    public boolean refreshDeployUpstream(Long appId, String upstream) {
        return refreshUpstream(appId, KIND_DEPLOY, upstream);
    }

    private boolean refreshUpstream(Long appId, String kind, String upstream) {
        if (appId == null || StrUtil.isBlank(upstream)) {
            return false;
        }
        AppAccessRoute existing = KIND_DEPLOY.equals(kind) ? findDeploy(appId) : findPreview(appId);
        if (existing == null || !STATUS_READY.equals(existing.getStatus())) {
            return false;
        }
        if (upstream.equals(existing.getUpstream())) {
            return false;
        }
        existing.setUpstream(upstream);
        existing.setUpdateTime(LocalDateTime.now());
        appAccessRouteMapper.update(existing);
        writeRedis(existing);
        log.info("路由上游已刷新: appId={}, kind={}, host={}, upstream={}",
                appId, kind, existing.getHost(), upstream);
        return true;
    }

    /**
     * 按域名删除一条路由（DB + Redis）。
     * <p>
     * 应用删除时除了遍历 DB 行，还要按 deployKey 推导出的域名兜底删一次：
     * 历史数据可能只剩 Redis key、没有 DB 行。
     */
    public void removeRouteByHost(String host) {
        if (StrUtil.isBlank(host)) {
            return;
        }
        stringRedisTemplate.delete(REDIS_PREFIX + host);
        appAccessRouteMapper.deleteById(host);
    }

    /**
     * 删除某应用的全部路由（预览 + 部署，DB 与 Redis 一起清）。
     * <p>
     * 应用被删除时必须走这里，否则已经发出去的链接还能访问到残留容器。
     */
    public void removeRoutesOfApp(Long appId) {
        if (appId == null) {
            return;
        }
        List<AppAccessRoute> rows = appAccessRouteMapper.selectListByQuery(
                QueryWrapper.create().eq("app_id", appId));
        for (AppAccessRoute row : rows) {
            removeRouteByHost(row.getHost());
            log.info("应用路由已删除: appId={}, host={}, kind={}", appId, row.getHost(), row.getKind());
        }
    }

    /**
     * 查某应用的预览路由
     */
    public AppAccessRoute findPreviewRoute(Long appId) {
        return findPreview(appId);
    }

    /**
     * 标记预览休眠：保留 host（链接不变），只把状态改成 STOPPED，proxy 会返回 503 直到被唤醒。
     */
    public void markPreviewSleeping(Long appId, String reason) {
        AppAccessRoute existing = findPreview(appId);
        if (existing == null) {
            return;
        }
        existing.setStatus(STATUS_STOPPED);
        existing.setUpdateTime(LocalDateTime.now());
        appAccessRouteMapper.update(existing);
        writeRedis(existing);
        log.info("预览路由标记休眠: appId={}, host={}, reason={}", appId, existing.getHost(), reason);
    }

    /**
     * 确保部署路由存在（幂等），返回可直接给浏览器的部署地址。
     * <p>
     * 与预览的关键差别：
     * - 域名是 {@code {deployKey}.app.localhost}，deployKey 由 app 表持有，重新部署不换地址；
     * - 路由不设 TTL（部署常驻，不参与预览回收）；
     * - 写 Redis 是"切流量"的原子动作：先写库再写 Redis，Redis 成功即视为切换完成。
     *
     * @param appId     应用 ID
     * @param deployKey 部署标识（6 位，大小写不敏感）
     * @param upstream  新版本容器的上游地址
     * @return 形如 http://ab12cd.app.localhost:8124/
     */
    public String ensureDeployRoute(Long appId, String deployKey, String upstream) {
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 ID 错误");
        }
        if (StrUtil.isBlank(deployKey)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "部署标识为空");
        }
        String host = deployHostOf(deployKey);
        AppAccessRoute existing = findDeploy(appId);
        if (existing != null && !host.equals(existing.getHost())) {
            // deployKey 变了（历史数据）：旧域名要一起清掉，避免一个应用两个入口
            stringRedisTemplate.delete(REDIS_PREFIX + existing.getHost());
            appAccessRouteMapper.deleteById(existing.getHost());
            existing = null;
        }

        AppAccessRoute route = AppAccessRoute.builder()
                .host(host)
                .appId(appId)
                .kind(KIND_DEPLOY)
                .upstream(upstream)
                .status(STATUS_READY)
                .expireAt(null)
                .updateTime(LocalDateTime.now())
                .build();
        if (existing == null) {
            appAccessRouteMapper.insert(route);
        } else {
            appAccessRouteMapper.update(route);
        }
        writeRedis(route);

        String url = buildDeployUrl(host);
        log.info("部署路由就绪: appId={}, host={}, upstream={}, url={}", appId, host, upstream, url);
        return url;
    }

    /**
     * 由部署标识拼出部署域名（不含端口），大小写不敏感。
     */
    public String deployHostOf(String deployKey) {
        return deployKey.trim().toLowerCase(Locale.ROOT) + "." + deployHostSuffix;
    }

    /**
     * 由部署域名拼出可直接给浏览器的部署地址。
     */
    public String deployUrlOf(String host) {
        return buildDeployUrl(host);
    }

    /**
     * 查某应用的部署路由
     */
    public AppAccessRoute findDeployRoute(Long appId) {
        return findDeploy(appId);
    }

    /**
     * 删除部署路由（应用下线时用；P5 暂未接入删除流程）
     */
    public void removeDeployRoute(Long appId) {
        AppAccessRoute existing = findDeploy(appId);
        if (existing == null) {
            return;
        }
        removeRouteByHost(existing.getHost());
        log.info("部署路由已删除: appId={}, host={}", appId, existing.getHost());
    }

    private AppAccessRoute findDeploy(Long appId) {
        return appAccessRouteMapper.selectOneByQuery(QueryWrapper.create()
                .eq("app_id", appId)
                .eq("kind", KIND_DEPLOY)
                .orderBy("update_time", false)
                .limit(1));
    }

    private String buildDeployUrl(String host) {
        String portPart = (deployPort == 80 || deployPort <= 0) ? "" : ":" + deployPort;
        return deployScheme + "://" + host + portPart + "/";
    }

    private AppAccessRoute findPreview(Long appId) {
        // 按更新时间倒序，保证并发/历史遗留产生多条时总能拿到最新的那条
        return appAccessRouteMapper.selectOneByQuery(QueryWrapper.create()
                .eq("app_id", appId)
                .eq("kind", KIND_PREVIEW)
                .orderBy("update_time", false)
                .limit(1));
    }

    /**
     * 清掉同一应用的多余预览路由（历史上并发调用可能产生重复行）。
     * 只保留 keepHost，其余 DB 行与 Redis key 一起删除，避免"同一个应用两个地址"。
     */
    private void removeDuplicatePreviewRoutes(Long appId, String keepHost) {
        List<AppAccessRoute> rows = appAccessRouteMapper.selectListByQuery(QueryWrapper.create()
                .eq("app_id", appId)
                .eq("kind", KIND_PREVIEW));
        for (AppAccessRoute row : rows) {
            if (!keepHost.equals(row.getHost())) {
                stringRedisTemplate.delete(REDIS_PREFIX + row.getHost());
                appAccessRouteMapper.deleteById(row.getHost());
                log.info("清理重复预览路由: appId={}, host={}, keep={}", appId, row.getHost(), keepHost);
            }
        }
    }

    /**
     * 分配一个未被占用的预览域名。
     */
    private String allocatePreviewHost() {
        for (int i = 0; i < 10; i++) {
            String host = randomCode() + "." + previewHostSuffix;
            long count = appAccessRouteMapper.selectCountByQuery(QueryWrapper.create().eq("host", host));
            if (count == 0) {
                return host;
            }
            log.warn("预览域名冲突，重新分配: {}", host);
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "无法分配预览域名，请稍后重试");
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)]);
        }
        return sb.toString();
    }

    private String buildUrl(String host) {
        String portPart = (previewPort == 80 || previewPort <= 0) ? "" : ":" + previewPort;
        return previewScheme + "://" + host + portPart + "/";
    }

    /**
     * 写 Redis：只放 proxy 需要的字段，契约与 proxy 的 Route record 一一对应。
     */
    private void writeRedis(AppAccessRoute route) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("host", route.getHost());
        payload.put("appId", route.getAppId());
        payload.put("kind", route.getKind());
        payload.put("upstream", route.getUpstream());
        payload.put("status", route.getStatus());
        String key = REDIS_PREFIX + route.getHost();
        String json = JSONUtil.toJsonStr(payload);
        if (KIND_DEPLOY.equals(route.getKind())) {
            // 部署路由不设过期：正式上线必须常驻，容器由 --restart unless-stopped 保证
            stringRedisTemplate.opsForValue().set(key, json);
        } else {
            // Redis 比 DB 多留 1 小时，避免边界上"库里还在、代理已 404"
            stringRedisTemplate.opsForValue().set(key, json, Duration.ofHours(previewTtlHours + 1L));
        }
    }
}
