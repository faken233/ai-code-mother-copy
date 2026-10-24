package com.yupi.proxy.route;

/**
 * 一条访问路由：域名 → 上游容器。
 *
 * @param host     访问域名（小写、不含端口），如 {@code 845.pv.localhost}
 * @param appId    应用 ID
 * @param kind     类型：PREVIEW / DEPLOY
 * @param upstream 上游基址，如 {@code http://127.0.0.1:20925}
 * @param status   READY / STARTING / STOPPED
 */
public record Route(String host, Long appId, String kind, String upstream, String status) {

    public static final String KIND_PREVIEW = "PREVIEW";
    public static final String KIND_DEPLOY = "DEPLOY";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_STARTING = "STARTING";
    public static final String STATUS_STOPPED = "STOPPED";

    public boolean ready() {
        return STATUS_READY.equalsIgnoreCase(status);
    }
}
