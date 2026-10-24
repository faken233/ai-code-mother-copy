package com.yupi.yuaicodemother.deploy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * docker CLI 的薄封装（部署链路专用）。
 * <p>
 * 预览链路仍走 {@code SandboxContainerManager} 里那套实现，这里不共用，
 * 避免改部署时误伤已验收的预览行为。两个类的共同点只有：都通过 docker CLI 操作宿主机 daemon。
 */
@Slf4j
@Component
public class DockerCli {

    /**
     * 执行 docker 命令，捕获合并后的 stdout/stderr。
     *
     * @param args           不含 "docker" 前缀的参数
     * @param timeoutSeconds 超时秒数
     * @param extraEnv       额外环境变量（如 DOCKER_BUILDKIT=0）
     */
    public Result run(List<String> args, int timeoutSeconds, Map<String, String> extraEnv) {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.addAll(args);
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            if (extraEnv != null) {
                pb.environment().putAll(extraEnv);
            }
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
                return new Result(false, output + "\n[docker] 命令超时: docker " + String.join(" ", args));
            }
            reader.join(3000);
            int exitCode = process.exitValue();
            return new Result(exitCode == 0, output.toString());
        } catch (Exception e) {
            log.error("执行 docker 命令失败: {}", e.getMessage(), e);
            return new Result(false, e.getMessage());
        }
    }

    public Result run(List<String> args, int timeoutSeconds) {
        return run(args, timeoutSeconds, Map.of());
    }

    /**
     * 取输出末尾若干行，用于错误信息与日志。
     */
    public static String tail(String output, int lines) {
        if (output == null || output.isBlank()) {
            return "";
        }
        String[] all = output.strip().split("\\R");
        int from = Math.max(0, all.length - lines);
        return String.join("\n", java.util.Arrays.copyOfRange(all, from, all.length));
    }

    public record Result(boolean success, String output) {
    }
}
