package com.yupi.yuaicodemother.sandbox;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 代码沙箱配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "sandbox")
public class SandboxProperties {

    /**
     * 是否启用沙箱（false 降级为本地直写，无 docker 环境可用）
     */
    private boolean enabled = false;

    /**
     * 沙箱镜像名
     */
    private String image = "code-sandbox:latest";

    /**
     * 工作区根目录（宿主机）
     */
    private String workspaceRoot = System.getProperty("user.dir") + "/tmp/code_output";

    /**
     * 预览端口范围起（映射容器内 node 服务 8080）
     */
    private int previewPortRangeStart = 18080;

    /**
     * 预览端口范围内不同 appId 的偏移上限
     */
    private int previewPortRangeSize = 1000;

    /**
     * 沙箱容器内 HTTP 服务（node 文件服务）端口
     */
    private int containerPort = 8080;

    /**
     * 沙箱容器内 Spring Boot 后端端口
     */
    private int containerBackendPort = 8081;

    /**
     * 映射到宿主机的后端端口范围起（反代到容器内后端）
     */
    private int backendPortRangeStart = 19080;

    /**
     * 主服务访问沙箱容器的地址（沙箱容器通过 -p 绑定到宿主机端口）
     * 主服务本地直跑时用 localhost；主服务跑在 compose 容器内时用 host.docker.internal。
     */
    private String host = "localhost";

    /**
     * 宿主机 MySQL 主机（供沙箱内后端连接，物理隔离的沙箱专用实例）
     */
    private String mysqlHost = "host.docker.internal";

    /**
     * 宿主机沙箱专用 MySQL 端口（与平台 MySQL 3306 错开）
     */
    private int mysqlPort = 3307;

    /**
     * 宿主机 Redis 主机（供沙箱内后端连接，物理隔离的沙箱专用实例）
     */
    private String redisHost = "host.docker.internal";

    /**
     * 宿主机沙箱专用 Redis 端口（与平台 Redis 6379 错开）
     */
    private int redisPort = 6380;

    /**
     * MySQL root 用户
     */
    private String mysqlUser = "root";

    /**
     * MySQL root 密码
     */
    private String mysqlPassword = "123456";
}
