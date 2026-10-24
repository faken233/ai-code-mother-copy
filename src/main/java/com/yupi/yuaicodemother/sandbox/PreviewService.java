package com.yupi.yuaicodemother.sandbox;

import com.yupi.yuaicodemother.exception.BusinessException;
import com.yupi.yuaicodemother.exception.ErrorCode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 预览服务：只负责"确保容器与应用就绪"。
 * <p>
 * P3 起删除了旧的内存进程模型：它靠 {@code docker exec mvn spring-boot:run} 启动容器内的
 * Spring Boot 后端（8081），再用固定公式的宿主端口反代。8081 映射已删除，旧格式项目
 * 不再支持预览——生成侧现在只产出一体化应用（单端口 node），没有这个分支存在的必要。
 */
@Slf4j
@Service
public class PreviewService {

    @Resource
    private SandboxProperties sandboxProperties;

    @Resource
    private SandboxContainerManager sandboxContainerManager;

    @Resource
    private SandboxAppLauncher sandboxAppLauncher;

    /**
     * 启动预览：确保容器运行、应用进程就绪。
     *
     * @param appId 应用 ID
     */
    public void startPreview(Long appId) {
        if (!sandboxProperties.isEnabled()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "沙箱未启用，预览服务不可用");
        }
        if (!sandboxAppLauncher.isUnifiedProject(appId)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "旧格式（Vue + Spring Boot）项目已不再支持预览，请重新生成一体化应用");
        }
        sandboxAppLauncher.start(appId);
        log.info("一体化应用预览已就绪（单端口，进程属容器）, appId: {}", appId);
    }

    /**
     * 停止预览并删除容器（应用删除 / 回收时调用）。
     */
    public void stopPreviewAndContainer(Long appId) {
        sandboxContainerManager.stopContainer(appId);
    }
}
