package com.yupi.proxy.route;

import com.yupi.proxy.config.ProxyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * 静态路由（配置文件）：用于自检/排障的固定别名，真实预览路由由 {@link RedisRouteStore} 提供。
 * <p>
 * 保留它的理由：验代理本身时不应该依赖"主服务 + Redis + 容器"全部就绪，
 * 否则代理出问题时无法区分是代理坏了还是上游坏了。
 */
@Component
public class StaticRouteStore implements RouteStore {

    private static final Logger log = LoggerFactory.getLogger(StaticRouteStore.class);

    private final Map<String, Route> routes = new HashMap<>();

    public StaticRouteStore(ProxyProperties properties) {
        for (ProxyProperties.RouteConfig cfg : properties.getRoutes()) {
            String host = HostNames.normalize(cfg.getHost());
            if (host == null || cfg.getUpstream() == null) {
                log.warn("忽略无效静态路由: host={}, upstream={}", cfg.getHost(), cfg.getUpstream());
                continue;
            }
            routes.put(host, new Route(host, cfg.getAppId(), cfg.getKind(), trimSlash(cfg.getUpstream()), cfg.getStatus()));
        }
        if (!routes.isEmpty()) {
            log.info("静态路由已加载 {} 条: {}", routes.size(), routes.keySet());
        }
    }

    @Override
    public Mono<Route> find(String host) {
        return Mono.justOrEmpty(routes.get(host));
    }

    private static String trimSlash(String upstream) {
        return upstream.endsWith("/") ? upstream.substring(0, upstream.length() - 1) : upstream;
    }
}
