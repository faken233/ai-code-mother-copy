package com.yupi.yuaicodemother.sandbox;

import cn.hutool.core.util.RandomUtil;
import com.yupi.yuaicodemother.ai.tools.ProjectPathResolver;
import com.yupi.yuaicodemother.exception.BusinessException;
import com.yupi.yuaicodemother.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 沙箱容器管理器
 * 通过 docker CLI 管理 sidecar 容器（统一镜像，卷挂载项目目录到 /workspace）。
 * 负责：
 * 1. 按 appId 启动/停止容器
 * 2. 启动时为每个应用创建独立 MySQL 库 + 独立账号（只授权 app_{appId}.*）
 * 3. 注入容器级环境变量（APP_PORT / APP_DB / DB_* / JWT_SECRET），供 start.sh 拉起应用
 * 4. 容器清理（任务完成/超时/预览结束）
 * <p>
 * 端口策略（P3 起）：
 * - 工具通道 8080（文件服务）、应用通道 3000（一体化 node 服务）
 * - 宿主端口由内核随机分配（{@code -p 127.0.0.1::8080}），启动后 {@code docker port} 回读并缓存
 * - 两个端口都只绑 sandbox.bind-address（默认 127.0.0.1），不暴露到局域网
 * - 旧格式后端 8081 已删除：旧格式项目不再支持预览
 */
@Slf4j
@Component
public class SandboxContainerManager {

    @Resource
    private SandboxProperties sandboxProperties;

    @Resource
    private ToolTokenService toolTokenService;

    /**
     * 目录名统一由它解析：{codeGenType}_{appId}。
     * 不能让沙箱自己写死 "fullstack_"，否则纯前端应用会读写到错误的目录。
     */
    @Resource
    private ProjectPathResolver projectPathResolver;

    /**
     * 容器名。预览容器用 app-pv-{appId}，部署容器用 app-dep-{appId}-v{n}（P5）。
     */
    public String containerNameOf(Long appId) {
        return "app-pv-" + appId;
    }

    /**
     * 项目目录名（{codeGenType}_{appId}，如 static_123 / fullstack_123）
     */
    private String projectDirName(Long appId) {
        return projectPathResolver.resolveProjectDirName(appId);
    }

    /**
     * 工具通道（容器内 8080）的宿主端口。
     * <p>
     * 不再用 appId 取模算出来——那套公式在应用数超过端口段时必然撞端口，
     * 且容器重建后端口可能变化。改为 docker run 时交给内核分配随机端口，再回读。
     */
    public int toolHostPort(Long appId) {
        return hostPortOf(appId, sandboxProperties.getContainerPort());
    }

    /**
     * 应用通道（容器内 3000）的宿主端口，同样以 docker port 回读为准。
     */
    public int appHostPort(Long appId) {
        return hostPortOf(appId, sandboxProperties.getAppContainerPort());
    }

    /**
     * 应用访问基地址（主服务视角）
     */
    public String appBaseUrl(Long appId) {
        return "http://" + sandboxProperties.getHost() + ":" + appHostPort(appId);
    }

    /**
     * Docker daemon 是否可用。
     * <p>
     * 对账任务在扫描前先问一次：守护进程短暂不可用时，所有 inspect 都会失败，
     * 不能把"查不到容器"误判成"容器已被删"而误休眠全部预览。
     */
    public boolean dockerAvailable() {
        return runDocker(List.of("info"), 15).success;
    }

    /**
     * 容器是否存在（对账任务用）
     */
    public boolean containerExists(Long appId) {
        return runDocker(List.of("inspect", containerNameOf(appId)), 20).success;
    }

    /**
     * 容器是否正在运行（对账任务用；不存在时返回 false）
     */
    public boolean isContainerRunning(Long appId) {
        CommandResult result = runDocker(
                List.of("inspect", "-f", "{{.State.Running}}", containerNameOf(appId)), 20);
        return result.success && "true".equalsIgnoreCase(result.stdout.trim());
    }

    /**
     * 丢弃端口缓存后重新回读应用地址。
     * <p>
     * 对账任务专用：容器被 docker restart / 手工重建后宿主端口会变，
     * 但 30s 缓存里还是旧端口，必须先失效再问 docker。
     */
    public String refreshAppBaseUrl(Long appId) {
        invalidatePorts(appId);
        return appBaseUrl(appId);
    }

    /**
     * 端口回读缓存：appId → (容器内端口 → 宿主端口 + 读取时间)。
     * <p>
     * 必须带 TTL：随机宿主端口在 {@code docker restart} 后会被 Docker 重新分配
     * （实测：58148 → 58180），缓存不打时间戳就会一直拿着旧端口。
     * 另外 {@link #stopContainer} 与容器已存在时的 {@code ensureContainer} 都会主动清缓存。
     */
    private final Map<Long, Map<Integer, CachedPort>> portCache = new ConcurrentHashMap<>();

    /** 端口缓存有效期：只用来削峰，过期就重新 docker port */
    private static final long PORT_CACHE_TTL_MS = 30_000L;

    private record CachedPort(int port, long readAt) {
    }

    /**
     * 回读容器端口映射。容器不存在或端口未发布时抛错，而不是猜一个端口。
     */
    private int hostPortOf(Long appId, int containerPort) {
        long now = System.currentTimeMillis();
        Map<Integer, CachedPort> cached = portCache.computeIfAbsent(appId, k -> new ConcurrentHashMap<>());
        CachedPort entry = cached.get(containerPort);
        if (entry != null && now - entry.readAt() < PORT_CACHE_TTL_MS) {
            return entry.port();
        }
        int resolved = resolveHostPort(appId, containerPort);
        cached.put(containerPort, new CachedPort(resolved, now));
        return resolved;
    }

    /**
     * 丢弃某应用的端口缓存。容器重启/重建后调用，强制下一次 docker port 回读。
     */
    public void invalidatePorts(Long appId) {
        portCache.remove(appId);
    }

    private int resolveHostPort(Long appId, int containerPort) {
        String name = containerNameOf(appId);
        CommandResult result = runDocker(List.of("port", name, containerPort + "/tcp"), 15);
        String output = result.success ? result.stdout.trim() : "";
        // 输出形如：127.0.0.1:54321  或  0.0.0.0:54321 / [::]:54321（多行时取第一行）
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int idx = trimmed.lastIndexOf(':');
            if (idx > 0) {
                try {
                    return Integer.parseInt(trimmed.substring(idx + 1));
                } catch (NumberFormatException ignored) {
                    // 继续看下一行
                }
            }
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                "读取容器端口失败: " + name + " " + containerPort + "/tcp（" + (result.success ? output : result.stderr) + "）");
    }

    /**
     * 执行 docker 命令并返回输出
     */
    private CommandResult runDocker(List<String> args, int timeoutSeconds) {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.addAll(args);
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                } catch (Exception ignored) {
                }
            });
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(false, output.toString(), "docker 命令超时");
            }
            reader.join(3000);
            int exitCode = process.exitValue();
            return new CommandResult(exitCode == 0, output.toString(), exitCode == 0 ? "" : output.toString());
        } catch (Exception e) {
            log.error("执行 docker 命令失败: {}", e.getMessage(), e);
            return new CommandResult(false, "", e.getMessage());
        }
    }

    /**
     * 确保容器运行：不存在则启动，已存在则复用
     *
     * @param appId 应用 ID
     */
    public synchronized void ensureContainer(Long appId) {
        if (!sandboxProperties.isEnabled()) {
            log.info("沙箱未启用，跳过容器启动，appId: {}", appId);
            return;
        }
        String name = containerNameOf(appId);
        try {
            CommandResult inspect = runDocker(List.of("inspect", name), 30);
            if (inspect.success) {
                if (isContainerRunning(appId)) {
                    log.info("容器已存在且运行中: {}", name);
                    waitForNodeReady(appId);
                    return;
                }
                // 宿主机重启后容器可能停在 Exited 状态：先尝试 start（保留原 env / 卷挂载）
                log.info("容器已存在但已停止，尝试启动: {}", name);
                CommandResult startResult = runDocker(List.of("start", name), 60);
                if (startResult.success) {
                    invalidatePorts(appId);
                    waitForNodeReady(appId);
                    return;
                }
                // start 失败（端口被占等）→ 删掉重建，代码在卷里、数据在库里，不会丢
                log.warn("启动已存在容器失败，删除后重建: {}, err={}", name, startResult.stderr);
                runDocker(List.of("rm", "-f", name), 60);
                invalidatePorts(appId);
            }

            String bind = sandboxProperties.getBindAddress();
            String workspacePath = sandboxProperties.getWorkspaceHostDir() + "/" + projectDirName(appId);

            // 先建库建账号：凭据要在 docker run 时注入，容器重建后也保持一致（由容器 env 持有）
            DatabaseCredentials credentials = null;
            try {
                credentials = provisionDatabase(appId);
            } catch (Exception e) {
                // 建库失败不阻塞生成（生成只需要工具通道），但预览会因缺库失败，这里必须显式告警
                log.warn("为应用 {} 建库/建账号失败，容器将以无数据库配置启动: {}", appId, e.getMessage());
            }

            // 端口交给内核分配（-p 127.0.0.1::容器端口），run 完再 docker port 回读。
            // 只映射两个通道：8080 工具通道（文件/exec）、3000 应用通道。
            // 8081（旧 Spring Boot 后端）已删除——旧格式项目不再支持预览。
            List<String> args = new ArrayList<>(List.of(
                    "run", "-d",
                    "--name", name,
                    "-p", bind + "::" + sandboxProperties.getContainerPort(),
                    "-p", bind + "::" + sandboxProperties.getAppContainerPort(),
                    "--label", "app.id=" + appId,
                    "--label", "app.kind=preview",
                    "--add-host=host.docker.internal:host-gateway"
            ));
            args.addAll(buildEnvArgs(appId, credentials));
            args.add("-v");
            args.add(workspacePath + ":/workspace");
            args.add("--memory");
            args.add("2g");
            args.add("--cpus");
            args.add("2");
            args.add(sandboxProperties.getImage());

            CommandResult runResult = runDocker(args, 120);
            if (!runResult.success) {
                log.error("启动沙箱容器失败: {}", runResult.stderr);
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "启动沙箱容器失败: " + runResult.stderr);
            }
            // 端口以 docker port 为准（回读内部带缓存），日志里打印实际映射
            log.info("沙箱容器已启动: {}, toolPort: {}, appPort: {}, workspace: {}",
                    name, toolHostPort(appId), appHostPort(appId), workspacePath);

            // 工具通道就绪后，生成/修改文件才可用
            waitForNodeReady(appId);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("启动沙箱容器失败, appId: {}", appId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "启动沙箱容器失败: " + e.getMessage());
        }
    }

    /**
     * 容器级环境变量：start.sh 拉起 node server.js 时直接继承，无需再 exec -e。
     * 注意不要设置 PORT —— 那个变量属于文件服务（8080）。
     */
    private List<String> buildEnvArgs(Long appId, DatabaseCredentials credentials) {
        List<String> env = new ArrayList<>();
        env.add("-e");
        env.add("APP_PORT=" + sandboxProperties.getAppContainerPort());
        env.add("-e");
        env.add("DB_HOST=" + sandboxProperties.getMysqlHost());
        env.add("-e");
        env.add("DB_PORT=" + sandboxProperties.getMysqlPort());
        env.add("-e");
        env.add("JWT_SECRET=" + RandomUtil.randomString(32));
        // 工具通道鉴权令牌（HMAC(secret, appId)），容器内 app.js 校验 X-Tool-Token
        env.add("-e");
        env.add("TOOL_TOKEN=" + toolTokenService.tokenOf(appId));
        if (credentials != null) {
            env.add("-e");
            env.add("APP_DB=" + credentials.database());
            env.add("-e");
            env.add("DB_USER=" + credentials.username());
            env.add("-e");
            env.add("DB_PASS=" + credentials.password());
        }
        return env;
    }

    /**
     * 等待沙箱 node 文件服务健康就绪（工具通道）。
     */
    private void waitForNodeReady(Long appId) {
        String healthUrl = "http://" + sandboxProperties.getHost() + ":" + toolHostPort(appId) + "/api/health";
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                String body = cn.hutool.http.HttpUtil.get(healthUrl, 2000);
                if (body != null && body.contains("\"ok\":true")) {
                    log.info("沙箱工具通道就绪: {}", healthUrl);
                    return;
                }
            } catch (Exception e) {
                // 未就绪，继续重试
            }
            sleepQuietly(1000L);
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "沙箱 node 服务未在 30 秒内就绪: " + healthUrl);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 建库 + 建独立账号（只授权本应用库）：
     * - 库：app_{appId}
     * - 账号：app_{appId}，随机密码，只在容器 env 中出现
     * 这样即使生成代码存在注入，也跨不到别的应用的库。
     */
    private DatabaseCredentials provisionDatabase(Long appId) {
        String dbName = "app_" + appId;
        String username = dbName;
        String password = RandomUtil.randomString(16);
        String url = "jdbc:mysql://"
                + sandboxProperties.getHost() + ":" + sandboxProperties.getMysqlPort()
                + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        try (Connection conn = DriverManager.getConnection(url,
                sandboxProperties.getMysqlUser(), sandboxProperties.getMysqlPassword());
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + dbName + "` DEFAULT CHARACTER SET utf8mb4");
            stmt.executeUpdate("CREATE USER IF NOT EXISTS '" + username + "'@'%' IDENTIFIED BY '" + password + "'");
            stmt.executeUpdate("ALTER USER '" + username + "'@'%' IDENTIFIED BY '" + password + "'");
            stmt.executeUpdate("GRANT ALL PRIVILEGES ON `" + dbName + "`.* TO '" + username + "'@'%'");
            stmt.executeUpdate("FLUSH PRIVILEGES");
            log.info("已准备隔离数据库与账号: {}.{}(仅授权本库)", dbName, username);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "创建隔离数据库失败: " + e.getMessage());
        }
        return new DatabaseCredentials(dbName, username, password);
    }

    /**
     * 停止并删除容器
     */
    public synchronized void stopContainer(Long appId) {
        if (!sandboxProperties.isEnabled()) {
            return;
        }
        String name = containerNameOf(appId);
        try {
            CommandResult inspect = runDocker(List.of("inspect", name), 30);
            if (!inspect.success) {
                return;
            }
            runDocker(List.of("stop", name), 60);
            runDocker(List.of("rm", name), 60);
            portCache.remove(appId); // 容器没了，缓存的宿主端口立即失效
            log.info("沙箱容器已停止并删除: {}", name);
        } catch (Exception e) {
            log.error("停止沙箱容器失败: {}", name, e);
        }
    }

    /**
     * 应用数据库凭据
     */
    public record DatabaseCredentials(String database, String username, String password) {
    }

    /**
     * docker 命令执行结果
     */
    private static class CommandResult {
        boolean success;
        String stdout;
        String stderr;

        CommandResult(boolean success, String stdout, String stderr) {
            this.success = success;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }
}
