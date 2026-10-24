package com.yupi.yuaicodemother.sandbox;

import com.yupi.yuaicodemother.exception.BusinessException;
import com.yupi.yuaicodemother.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * 工具通道令牌。
 * <p>
 * 每个应用的令牌 = HMAC-SHA256(secret, appId) 的十六进制串，容器启动时以 TOOL_TOKEN 注入。
 * 主服务调用工具通道（文件 / exec / rebuild / restart）时带上 X-Tool-Token。
 * <p>
 * 为什么不用每次启动随机生成：主服务重启后已有容器的令牌会全部失效，
 * 预览容器又可能长期存活，所以 secret 必须来自稳定配置（.env / application.yml）。
 */
@Slf4j
@Component
public class ToolTokenService {

    private static final String DEFAULT_SECRET = "local-dev-tool-token-secret";

    @Resource
    private SandboxProperties sandboxProperties;

    @PostConstruct
    public void warnOnDefaultSecret() {
        String secret = sandboxProperties.getToolTokenSecret();
        if (secret == null || secret.isBlank()) {
            log.error("sandbox.tool-token-secret 未配置：工具通道将拒绝所有请求（容器仍可启动，但无法读写文件/执行命令）");
        } else if (DEFAULT_SECRET.equals(secret)) {
            log.warn("沙箱工具通道正在使用内置默认密钥，生产/共享环境请设置 SANDBOX_TOOL_TOKEN_SECRET");
        }
    }

    /**
     * 计算某应用的工具通道令牌
     */
    public String tokenOf(Long appId) {
        String secret = sandboxProperties.getToolTokenSecret();
        if (secret == null || secret.isBlank()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "未配置 sandbox.tool-token-secret，无法访问工具通道");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(String.valueOf(appId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "计算工具通道令牌失败: " + e.getMessage());
        }
    }
}
