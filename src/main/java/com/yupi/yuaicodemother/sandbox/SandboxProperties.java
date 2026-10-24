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
     * 宿主机侧工作区根目录（用于 docker run -v 挂载源路径）。
     * 本地直跑时与 workspaceRoot 一致（user.dir 即宿主机路径）；
     * 主服务跑在 compose 容器内时，user.dir 是容器内路径（/app/tmp/code_output），
     * 而 docker CLI 打的是宿主机 daemon，必须用宿主机真实路径（经 SANDBOX_WORKSPACE_HOST_DIR 注入）。
     */
    private String workspaceHostDir = System.getProperty("user.dir") + "/tmp/code_output";

    /**
     * 沙箱容器内 HTTP 服务（node 文件服务）端口
     */
    private int containerPort = 8080;

    /**
     * 沙箱容器内一体化应用端口（node server.js）
     */
    private int appContainerPort = 3000;

    /**
     * 宿主机端口绑定地址。默认只绑本机：
     * 工具通道暴露 /api/exec（容器内任意命令执行），绑 0.0.0.0 等于把 RCE 送到局域网。
     */
    private String bindAddress = "127.0.0.1";

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

    /**
     * 工具通道令牌签名密钥。
     * 容器启动时按 HMAC-SHA256(secret, appId) 计算 TOOL_TOKEN 注入，
     * 工具通道（文件 / exec / rebuild）校验 X-Tool-Token，防止本机任意进程调用映射端口。
     * 必须跨主服务重启保持稳定，否则已有容器的令牌会全部失效。
     */
    private String toolTokenSecret = "local-dev-tool-token-secret";
}
