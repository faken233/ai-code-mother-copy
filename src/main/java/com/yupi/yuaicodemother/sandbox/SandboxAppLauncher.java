package com.yupi.yuaicodemother.sandbox;

import cn.hutool.http.HttpUtil;
import com.yupi.yuaicodemother.exception.BusinessException;
import com.yupi.yuaicodemother.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 一体化应用启动器（替代旧的 PreviewService 内存进程模型）。
 * <p>
 * 关键差别：
 * - 不再用 Process 句柄跟踪应用进程，主服务重启不会丢"句柄"（本来就没有）
 * - 启动方式：容器入口 start.sh 会尝试拉起；若容器先于代码存在，这里用 docker exec -d 兜底
 * - 探活区分两个通道：工具通道（文件服务）与应用通道（app:true）
 */
@Slf4j
@Component
public class SandboxAppLauncher {

    /**
     * 一体化应用的入口文件，存在即认为是新格式项目
     */
    public static final String APP_ENTRY = "server.js";

    @Resource
    private SandboxProperties sandboxProperties;

    @Resource
    private SandboxContainerManager sandboxContainerManager;

    @Resource
    private com.yupi.yuaicodemother.ai.tools.ProjectPathResolver projectPathResolver;

    /**
     * 是否为一体化（单端口 Node）项目
     */
    public boolean isUnifiedProject(Long appId) {
        if (appId == null) {
            return false;
        }
        // 目录名由 codeGenType 决定（static_123 / fullstack_123），不能写死 fullstack_
        File entry = projectPathResolver.resolveProjectRoot(appId).resolve(APP_ENTRY).toFile();
        return entry.isFile();
    }

    /**
     * 确保容器与应用进程都在运行，并等到应用健康。
     */
    public void start(Long appId) {
        if (!sandboxProperties.isEnabled()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "沙箱未启用，无法启动预览");
        }
        // 随机宿主端口在 docker restart 后会被重新分配，预览前先强制回读一次
        sandboxContainerManager.invalidatePorts(appId);
        sandboxContainerManager.ensureContainer(appId);
        if (isAppReady(appId)) {
            log.info("应用已在运行, appId: {}", appId);
            return;
        }
        if (!hasDatabaseEnv(appId)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "容器缺少数据库配置（建库时可能失败），请删除容器 " + sandboxContainerManager.containerNameOf(appId) + " 后重试");
        }
        // 容器刚创建时 start.sh 可能已经在拉起应用，先给它几秒，避免重复启动（会产生僵尸进程 + 端口冲突）
        if (waitReadyQuietly(appId, 8)) {
            log.info("应用由容器入口拉起，无需 exec 兜底, appId: {}", appId);
            return;
        }
        execDetached(appId);
        waitForAppReady(appId, 60);
    }

    /**
     * 静默等待应用就绪（用于判断容器入口是否已经拉起应用）
     */
    private boolean waitReadyQuietly(Long appId, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (isAppReady(appId)) {
                return true;
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * 应用通道健康检查：必须命中 "app":true，避免误判成文件服务
     */
    public boolean isAppReady(Long appId) {
        try {
            String body = HttpUtil.get(sandboxContainerManager.appBaseUrl(appId) + "/api/health", 2000);
            return body != null && body.contains("\"app\":true");
        } catch (Exception e) {
            return false;
        }
    }

    private void waitForAppReady(Long appId, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        String url = sandboxContainerManager.appBaseUrl(appId) + "/api/health";
        while (System.currentTimeMillis() < deadline) {
            if (isAppReady(appId)) {
                log.info("应用通道就绪: {}", url);
                return;
            }
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                "应用未在 " + timeoutSeconds + " 秒内就绪: " + url + "（查看日志：docker exec " + sandboxContainerManager.containerNameOf(appId) + " tail -50 /tmp/app.log）");
    }

    /**
     * 容器内后台启动应用。exec -d 启动的进程属容器，主服务退出/重启都不影响它。
     */
    private void execDetached(Long appId) {
        String name = sandboxContainerManager.containerNameOf(appId);
        List<String> cmd = List.of("docker", "exec", "-d", name, "sh", "-c", "cd /workspace && exec node server.js");
        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "启动应用命令超时");
            }
            if (process.exitValue() != 0) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "启动应用命令失败，exit=" + process.exitValue());
            }
            log.info("已在容器内后台启动应用: {}", name);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "启动应用失败: " + e.getMessage());
        }
    }

    /**
     * 容器是否注入了数据库配置（防止"建库失败但容器已建"导致应用永远起不来）
     */
    private boolean hasDatabaseEnv(Long appId) {
        String name = sandboxContainerManager.containerNameOf(appId);
        try {
            Process process = new ProcessBuilder("docker", "inspect", "-f",
                    "{{range .Config.Env}}{{println .}}{{end}}", name).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor(10, TimeUnit.SECONDS);
            return output.contains("APP_DB=");
        } catch (Exception e) {
            log.warn("读取容器环境变量失败: {}", e.getMessage());
            return false;
        }
    }
}
