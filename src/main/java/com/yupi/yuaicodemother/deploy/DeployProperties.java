package com.yupi.yuaicodemother.deploy;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 部署容器化配置。
 * <p>
 * 与预览容器完全隔离：部署容器只暴露应用端口（3000），不带工具通道；不参与预览回收。
 */
@Data
@Component
@ConfigurationProperties(prefix = "deploy")
public class DeployProperties {

    /**
     * 是否启用容器化部署（关闭时 deployApp 直接报错，不再走旧的本地文件托管）
     */
    private boolean enabled = true;

    /**
     * 部署镜像的基础镜像。要求内置 node + npm，且能直接运行一体化应用。
     * 默认值与本地已有镜像一致，避免部署时临时拉取。
     */
    private String baseImage = "node:18-alpine";

    /**
     * 保留多少个历史版本（含当前版本）。更老的容器/镜像/快照会被清理，
     * 但保留 DB 记录（状态 PRUNED），回滚到被清理的版本会给出明确提示。
     */
    private int keepVersions = 5;

    /**
     * docker build 超时（秒）
     */
    private int buildTimeoutSeconds = 600;

    /**
     * 容器启动后等待应用健康（含 migrate）的超时（秒）
     */
    private int healthTimeoutSeconds = 120;

    /**
     * 部署容器内存限制
     */
    private String memory = "512m";

    /**
     * 部署容器 CPU 限制
     */
    private String cpus = "1";
}
