package com.yupi.yuaicodemother.sandbox;

import com.yupi.yuaicodemother.constant.AppConstant;
import com.yupi.yuaicodemother.exception.BusinessException;
import com.yupi.yuaicodemother.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全功能预览服务（A8）
 * 预览全栈项目时，在沙箱容器内启动后端，注入隔离环境变量：
 *  - APP_DB = app_{appId}（独立 MySQL 库）
 *  - REDIS_DB = appId % 16（独立 Redis db）
 *  - MYSQL_HOST / REDIS_HOST = host.docker.internal
 * 后端以 application-sandbox.yml 配置运行。
 */
@Slf4j
@Service
public class PreviewService {

    @Resource
    private SandboxProperties sandboxProperties;

    @Resource
    private SandboxContainerManager sandboxContainerManager;

    /**
     * 记录每个 appId 的后端预览进程，用于停止/超时清理
     */
    private final Map<Long, Process> previewProcessMap = new ConcurrentHashMap<>();

    /**
     * 启动预览：确保容器运行，并启动后端（沙箱 profile）
     *
     * @param appId 应用 ID
     */
    public void startPreview(Long appId) {
        if (!sandboxProperties.isEnabled()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "沙箱未启用，预览服务不可用");
        }
        // 1. 确保容器运行（含卷挂载 + 建库）
        sandboxContainerManager.ensureContainer(appId);

        // 2. 计算隔离环境变量
        // key 前缀隔离（app_{appId}:），沙箱专用 Redis 统一 db0，突破 db 数 16 上限
        String appDb = "app_" + appId;
        String redisKeyPrefix = "app_" + appId + ":";

        // 3. 找到后端目录（宿主机卷路径）
        String projectRoot = AppConstant.CODE_OUTPUT_ROOT_DIR + "/fullstack_" + appId;
        File backendDir = new File(projectRoot, "backend");
        if (!backendDir.exists() || !backendDir.isDirectory()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "后端目录不存在，请先生成全栈项目");
        }

        // 4. 若该 appId 已有预览进程，先停止旧进程
        stopPreview(appId);

        // 5. 启动后端进程（沙箱 profile，容器内 8081 端口与宿主映射一致）
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "exec",
                    "-e", "APP_DB=" + appDb,
                    "-e", "REDIS_DB=0",
                    "-e", "REDIS_KEY_PREFIX=" + redisKeyPrefix,
                    "-e", "MYSQL_HOST=" + sandboxProperties.getMysqlHost(),
                    "-e", "MYSQL_PORT=" + sandboxProperties.getMysqlPort(),
                    "-e", "MYSQL_USER=" + sandboxProperties.getMysqlUser(),
                    "-e", "MYSQL_PASSWORD=" + sandboxProperties.getMysqlPassword(),
                    "-e", "REDIS_HOST=" + sandboxProperties.getRedisHost(),
                    "-e", "REDIS_PORT=" + sandboxProperties.getRedisPort(),
                    "code-sandbox-" + appId,
                    "mvn", "-q",
                    "spring-boot:run",
                    "-Dspring-boot.run.profiles=sandbox",
                    "-Dspring-boot.run.arguments=--server.port=" + sandboxProperties.getContainerBackendPort()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            previewProcessMap.put(appId, process);

            // 后台消费输出，避免阻塞容器
            Thread.ofVirtual().start(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("[preview][{}] {}", appId, line);
                    }
                } catch (Exception ignored) {
                }
            });

            log.info("预览已启动，appId: {}, appDb: {}, redisKeyPrefix: {}", appId, appDb, redisKeyPrefix);
        } catch (Exception e) {
            log.error("启动预览后端失败, appId: {}", appId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "启动预览后端失败: " + e.getMessage());
        }
    }

    /**
     * 停止预览（结束后端进程）
     */
    public void stopPreview(Long appId) {
        Process process = previewProcessMap.remove(appId);
        if (process != null) {
            try {
                process.destroyForcibly();
                log.info("已停止预览进程, appId: {}", appId);
            } catch (Exception e) {
                log.warn("停止预览进程失败, appId: {}", appId, e.getMessage());
            }
        }
    }

    /**
     * 停止预览并清理容器
     */
    public void stopPreviewAndContainer(Long appId) {
        stopPreview(appId);
        sandboxContainerManager.stopContainer(appId);
    }

    /**
     * 判断该 appId 是否已在预览
     */
    public boolean isPreviewing(Long appId) {
        return previewProcessMap.containsKey(appId);
    }
}
