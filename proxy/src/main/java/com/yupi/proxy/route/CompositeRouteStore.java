package com.yupi.proxy.route;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 路由查找顺序：静态别名优先，其次 Redis。
 * <p>
 * 静态优先是为了让自检/排障路由（echo、dead）在任何时候都可用，
 * 不被 Redis 状态影响——这样"代理本身是否正常"和"动态路由是否正常"可以分开判定。
 */
@Component
@Primary
public class CompositeRouteStore implements RouteStore {

    private final StaticRouteStore staticRouteStore;
    private final RedisRouteStore redisRouteStore;

    public CompositeRouteStore(StaticRouteStore staticRouteStore, RedisRouteStore redisRouteStore) {
        this.staticRouteStore = staticRouteStore;
        this.redisRouteStore = redisRouteStore;
    }

    @Override
    public Mono<Route> find(String host) {
        return staticRouteStore.find(host)
                .switchIfEmpty(Mono.defer(() -> redisRouteStore.find(host)));
    }
}
