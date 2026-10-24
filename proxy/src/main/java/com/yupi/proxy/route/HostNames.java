package com.yupi.proxy.route;

import java.util.Locale;

/**
 * Host 头归一化：去端口、转小写、去尾部点。
 * <p>
 * 浏览器可能带端口（:8124）、带尾点（FQDN 形式），也可能大小写混用，
 * 查路由前必须统一，否则会出现"明明有路由却 404"。
 */
public final class HostNames {

    private HostNames() {
    }

    public static String normalize(String rawHost) {
        if (rawHost == null) {
            return null;
        }
        String host = rawHost.trim().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            return null;
        }
        // IPv6 字面量 [::1]:8124
        int bracket = host.lastIndexOf(']');
        if (bracket >= 0) {
            host = host.substring(0, bracket + 1);
        } else {
            int colon = host.indexOf(':');
            if (colon >= 0) {
                host = host.substring(0, colon);
            }
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host.isEmpty() ? null : host;
    }
}
