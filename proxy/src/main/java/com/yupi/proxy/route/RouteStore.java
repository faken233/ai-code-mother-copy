package com.yupi.proxy.route;

import reactor.core.publisher.Mono;

/**
 * 路由来源。
 * <p>
 * 返回 {@link Mono} 而不是 {@code Optional}：Redis 读取本身是异步的，
 * 在 Netty 事件循环里做阻塞调用会直接抛 "block() is not supported"。
 */
public interface RouteStore {

    /**
     * 按 host 查路由；未命中返回空 Mono。
     */
    Mono<Route> find(String host);
}
