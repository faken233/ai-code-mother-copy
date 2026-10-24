package com.yupi.proxy.route;

/**
 * 路由存储不可用（Redis 连不上 / 超时）。
 * <p>
 * 与"路由不存在"必须区分：前者应返回 503（可重试），后者才是 404。
 * 混为一谈会让 Redis 抖动表现为"所有预览链接突然 404"。
 */
public class RouteStoreUnavailableException extends RuntimeException {

    public RouteStoreUnavailableException(String host, Throwable cause) {
        super("route store unavailable for host: " + host, cause);
    }
}
