package com.yupi.proxy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 代理配置。P1 的路由来自配置文件；P2 起路由改由 Redis 提供，这里只保留通用参数。
 */
@ConfigurationProperties(prefix = "proxy")
public class ProxyProperties {

    /**
     * 是否开启自检端点（/__health、/__selftest/stream）。仅开发/验收用。
     */
    private boolean selftestEnabled = false;

    /**
     * 普通请求的上游读超时（秒）。SSE/长下载走另一条不设超时的通道。
     */
    private int normalTimeoutSeconds = 30;

    /**
     * 静态路由（P1 专用）。
     */
    private List<RouteConfig> routes = new ArrayList<>();

    public boolean isSelftestEnabled() {
        return selftestEnabled;
    }

    public void setSelftestEnabled(boolean selftestEnabled) {
        this.selftestEnabled = selftestEnabled;
    }

    public int getNormalTimeoutSeconds() {
        return normalTimeoutSeconds;
    }

    public void setNormalTimeoutSeconds(int normalTimeoutSeconds) {
        this.normalTimeoutSeconds = normalTimeoutSeconds;
    }

    public List<RouteConfig> getRoutes() {
        return routes;
    }

    public void setRoutes(List<RouteConfig> routes) {
        this.routes = routes;
    }

    /**
     * 配置文件里的一条路由。
     */
    public static class RouteConfig {
        private String host;
        private Long appId;
        private String kind = "PREVIEW";
        private String upstream;
        private String status = "READY";

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public Long getAppId() {
            return appId;
        }

        public void setAppId(Long appId) {
            this.appId = appId;
        }

        public String getKind() {
            return kind;
        }

        public void setKind(String kind) {
            this.kind = kind;
        }

        public String getUpstream() {
            return upstream;
        }

        public void setUpstream(String upstream) {
            this.upstream = upstream;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }
}
