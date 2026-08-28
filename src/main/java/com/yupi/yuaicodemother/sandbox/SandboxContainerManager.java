package com.yupi.yuaicodemother.sandbox;

import com.yupi.yuaicodemother.constant.AppConstant;
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
import java.util.concurrent.TimeUnit;

/**
 * 沙箱容器管理器
 * 通过 docker CLI 管理 sidecar 容器（统一镜像，卷挂载项目目录到 /workspace）。
 * 负责：
 * 1. 按 appId 启动/停止容器
 * 2. 启动时创建隔离的独立 MySQL 数据库 app_{appId}（A7.2）
 * 3. 容器清理（任务完成/超时/预览结束）
 */
@Slf4j
@Component
public class SandboxContainerManager {

    @Resource
    private SandboxProperties sandboxProperties;

    /**
     * 容器名
     */
    private String containerName(Long appId) {
        return "code-sandbox-" + appId;
    }

    /**
     * 项目目录名（fullstack_{appId}）
     */
    private String projectDirName(Long appId) {
        return "fullstack_" + appId;
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
        String name = containerName(appId);
        try {
            // 检查容器是否已存在
            CommandResult inspect = runDocker(List.of("inspect", name), 30);
            if (inspect.success) {
                log.info("容器已存在: {}", name);
                // 容器已存在时也做健康探测，确保 node 服务真正就绪
                waitForNodeReady(appId);
                return;
            }

            // 计算宿主映射端口
            int hostPort = sandboxProperties.getPreviewPortRangeStart()
                    + (int) (appId % sandboxProperties.getPreviewPortRangeSize());
            int backendHostPort = sandboxProperties.getBackendPortRangeStart()
                    + (int) (appId % sandboxProperties.getPreviewPortRangeSize());
            String workspacePath = AppConstant.CODE_OUTPUT_ROOT_DIR + "/" + projectDirName(appId);

            // docker run -d --name code-sandbox-{appId}
            //   -p {hostPort}:8080       (node 文件服务)
            //   -p {backendHostPort}:8081 (Spring Boot 后端)
            //   --add-host=host.docker.internal:host-gateway
            //   -v {workspacePath}:/workspace
            //   --memory 2g --cpus 2 code-sandbox:latest
            List<String> args = new ArrayList<>(List.of(
                    "run", "-d",
                    "--name", name,
                    "-p", hostPort + ":" + sandboxProperties.getContainerPort(),
                    "-p", backendHostPort + ":" + sandboxProperties.getContainerBackendPort(),
                    "--add-host=host.docker.internal:host-gateway"
            ));
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

            // 创建隔离数据库 app_{appId}
            createIsolatedDatabase(appId);
            log.info("沙箱容器已启动: {}, nodePort: {}, backendPort: {}, workspace: {}", name, hostPort, backendHostPort, workspacePath);

            // 等待 node 服务就绪（确保后续写文件/exec 不会 Connection refused）
            waitForNodeReady(appId);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("启动沙箱容器失败, appId: {}", appId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "启动沙箱容器失败: " + e.getMessage());
        }
    }

    /**
     * 等待沙箱 node 服务健康就绪。
     * 轮询 GET http://{host}:{previewPort}/api/health，直到返回 ok 或超时（30 秒）。
     */
    private void waitForNodeReady(Long appId) {
        int previewPort = sandboxProperties.getPreviewPortRangeStart()
                + (int) (appId % sandboxProperties.getPreviewPortRangeSize());
        String healthUrl = "http://" + sandboxProperties.getHost() + ":" + previewPort + "/api/health";
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                String body = cn.hutool.http.HttpUtil.get(healthUrl, 2000);
                if (body != null && body.contains("\"ok\":true")) {
                    log.info("沙箱 node 服务就绪: {}", healthUrl);
                    return;
                }
            } catch (Exception e) {
                // 未就绪，继续重试
            }
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "沙箱 node 服务未在 30 秒内就绪: " + healthUrl);
    }

    /**
     * 在每个 appId 对应的独立 MySQL 库上执行建库语句（A7.2）
     * 沙箱容器内后端以 application-sandbox.yml 连接 ${APP_DB} = app_{appId}
     */
    private void createIsolatedDatabase(Long appId) {
        String dbName = "app_" + appId;
        // 建库由主服务执行，应使用主服务访问宿主的地址（sandbox.host），而非沙箱容器内后端的地址（sandbox.mysqlHost）
        String url = "jdbc:mysql://"
                + sandboxProperties.getHost() + ":" + sandboxProperties.getMysqlPort()
                + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        try (Connection conn = DriverManager.getConnection(url, sandboxProperties.getMysqlUser(), sandboxProperties.getMysqlPassword());
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + dbName + "` DEFAULT CHARACTER SET utf8mb4");
            log.info("已创建隔离数据库: {}", dbName);
        } catch (Exception e) {
            log.warn("创建隔离数据库失败 {}: {}（容器内后端首次连接时若 schema 不存在会报错，请确认 MySQL 可达）", dbName, e.getMessage());
        }
    }

    /**
     * 停止并删除容器
     */
    public synchronized void stopContainer(Long appId) {
        if (!sandboxProperties.isEnabled()) {
            return;
        }
        String name = containerName(appId);
        try {
            CommandResult inspect = runDocker(List.of("inspect", name), 30);
            if (!inspect.success) {
                return;
            }
            runDocker(List.of("stop", name), 60);
            runDocker(List.of("rm", name), 60);
            log.info("沙箱容器已停止并删除: {}", name);
        } catch (Exception e) {
            log.error("停止沙箱容器失败: {}", name, e);
        }
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
