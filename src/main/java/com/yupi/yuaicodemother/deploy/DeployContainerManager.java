package com.yupi.yuaicodemother.deploy;

import cn.hutool.http.HttpUtil;
import com.yupi.yuaicodemother.exception.BusinessException;
import com.yupi.yuaicodemother.exception.ErrorCode;
import com.yupi.yuaicodemother.sandbox.SandboxProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 部署容器管理：构建生产镜像、启动/停止容器、回读宿主端口、探活。
 * <p>
 * 与预览容器的区别（改代码前先看）：
 * 1. 容器名 {@code app-dep-{appId}-v{n}}，只发布应用端口 3000，没有工具通道 8080；
 * 2. 镜像 {@code app-dep-{appId}:v{n}} 一旦构建就不变，回滚只是把路由指回旧容器；
 * 3. {@code --restart unless-stopped}，正式上线的应用不参与预览回收；
 * 4. 宿主端口仍是内核随机分配 + {@code docker port} 回读（docker start 后会变）。
 */
@Slf4j
@Component
public class DeployContainerManager {

    @Resource
    private DeployProperties deployProperties;

    @Resource
    private SandboxProperties sandboxProperties;

    @Resource
    private DockerCli dockerCli;

    public String imageOf(Long appId, int version) {
        return "app-dep-" + appId + ":v" + version;
    }

    public String containerOf(Long appId, int version) {
        return "app-dep-" + appId + "-v" + version;
    }

    /**
     * 构建生产镜像。上下文就是版本快照目录，Dockerfile 由部署服务写进去。
     */
    public void buildImage(Long appId, int version, Path contextDir) {
        List<String> args = List.of(
                "build",
                "--label", "app.id=" + appId,
                "--label", "app.kind=deploy",
                "--label", "app.version=" + version,
                "-f", contextDir.resolve("Dockerfile").toString(),
                "-t", imageOf(appId, version),
                contextDir.toString());
        // BuildKit 在受限环境（无法写 ~/.docker/buildx）会直接失败；部署链路固定用 legacy builder。
        DockerCli.Result result = dockerCli.run(args, deployProperties.getBuildTimeoutSeconds(),
                Map.of("DOCKER_BUILDKIT", "0"));
        if (!result.success()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "构建部署镜像失败: " + DockerCli.tail(result.output(), 20));
        }
        log.info("部署镜像构建完成: appId={}, version={}, image={}", appId, version, imageOf(appId, version));
    }

    /**
     * 启动一个新容器，返回可访问的上游地址。
     */
    public String runContainer(Long appId, int version, Map<String, String> env) {
        String name = containerOf(appId, version);
        String bind = sandboxProperties.getBindAddress();
        int appPort = sandboxProperties.getAppContainerPort();

        List<String> args = new ArrayList<>(List.of(
                "run", "-d",
                "--name", name,
                "--label", "app.id=" + appId,
                "--label", "app.kind=deploy",
                "--label", "app.version=" + version,
                "--restart", "unless-stopped",
                "--add-host=host.docker.internal:host-gateway",
                "-p", bind + "::" + appPort,
                "--memory", deployProperties.getMemory(),
                "--cpus", deployProperties.getCpus()));
        env.forEach((k, v) -> {
            args.add("-e");
            args.add(k + "=" + v);
        });
        args.add(imageOf(appId, version));

        DockerCli.Result result = dockerCli.run(args, 120);
        if (!result.success()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "启动部署容器失败: " + DockerCli.tail(result.output(), 20));
        }
        log.info("部署容器已启动: {} (image={})", name, imageOf(appId, version));
        return upstreamOf(name);
    }

    /**
     * 启动已存在但处于停止状态的容器（回滚主路径）。
     */
    public String startContainer(String containerName) {
        DockerCli.Result result = dockerCli.run(List.of("start", containerName), 60);
        if (!result.success()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "启动历史部署容器失败: " + DockerCli.tail(result.output(), 10));
        }
        return upstreamOf(containerName);
    }

    public void restartContainer(String containerName) {
        DockerCli.Result result = dockerCli.run(List.of("restart", containerName), 90);
        if (!result.success()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "重启部署容器失败: " + DockerCli.tail(result.output(), 10));
        }
    }

    public void stopContainer(String containerName) {
        DockerCli.Result result = dockerCli.run(List.of("stop", containerName), 60);
        if (!result.success()) {
            log.warn("停止部署容器失败: {} -> {}", containerName, DockerCli.tail(result.output(), 5));
        }
    }

    public void removeContainer(String containerName) {
        DockerCli.Result result = dockerCli.run(List.of("rm", "-f", containerName), 60);
        if (!result.success()) {
            log.warn("删除部署容器失败: {} -> {}", containerName, DockerCli.tail(result.output(), 5));
        }
    }

    public void removeImage(String image) {
        DockerCli.Result result = dockerCli.run(List.of("rmi", image), 60);
        if (!result.success()) {
            log.warn("删除部署镜像失败: {} -> {}", image, DockerCli.tail(result.output(), 5));
        }
    }

    public boolean containerExists(String containerName) {
        return dockerCli.run(List.of("inspect", containerName), 30).success();
    }

    public boolean containerRunning(String containerName) {
        DockerCli.Result result = dockerCli.run(
                List.of("inspect", "-f", "{{.State.Running}}", containerName), 30);
        return result.success() && "true".equalsIgnoreCase(result.output().strip());
    }

    public boolean imageExists(String image) {
        return dockerCli.run(List.of("image", "inspect", image), 30).success();
    }

    /**
     * 回读容器内应用端口的宿主映射，返回 http://127.0.0.1:{port}。
     * 容器刚启动/刚 start 时端口可能还没登记，短轮询几次。
     */
    public String upstreamOf(String containerName) {
        int containerPort = sandboxProperties.getAppContainerPort();
        for (int i = 0; i < 10; i++) {
            DockerCli.Result result = dockerCli.run(
                    List.of("port", containerName, containerPort + "/tcp"), 15);
            String output = result.success() ? result.output().strip() : "";
            for (String line : output.split("\\R")) {
                String trimmed = line.strip();
                int idx = trimmed.lastIndexOf(':');
                if (idx > 0) {
                    try {
                        int port = Integer.parseInt(trimmed.substring(idx + 1));
                        return "http://" + sandboxProperties.getHost() + ":" + port;
                    } catch (NumberFormatException ignored) {
                        // 下一行
                    }
                }
            }
            sleepQuietly(500L);
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                "读取部署容器端口失败: " + containerName + " " + containerPort + "/tcp");
    }

    /**
     * 应用健康判定：必须命中 "app":true（避免把别的服务当成应用）。
     */
    public boolean healthy(String upstream) {
        try {
            String body = HttpUtil.get(upstream + "/api/health", 3000);
            return body != null && body.contains("\"app\":true");
        } catch (Exception e) {
            return false;
        }
    }

    public void waitHealthy(String upstream, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (healthy(upstream)) {
                log.info("部署应用已就绪: {}", upstream);
                return;
            }
            sleepQuietly(1000L);
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                "部署应用未在 " + timeoutSeconds + " 秒内就绪: " + upstream);
    }

    /**
     * 取容器日志尾部，失败时带进错误信息里，避免"部署失败但不知道为什么"。
     */
    public String logs(String containerName, int tailLines) {
        DockerCli.Result result = dockerCli.run(
                List.of("logs", "--tail", String.valueOf(tailLines), containerName), 30);
        return DockerCli.tail(result.output(), tailLines);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
