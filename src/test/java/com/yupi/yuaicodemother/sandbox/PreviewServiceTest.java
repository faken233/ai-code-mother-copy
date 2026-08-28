package com.yupi.yuaicodemother.sandbox;

import com.yupi.yuaicodemother.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * PreviewService 单元测试（mock 容器管理器，无需 docker）
 * 验证沙箱预览启动的前置校验与隔离配置默认值。
 */
class PreviewServiceTest {

    @Mock
    private SandboxContainerManager sandboxContainerManager;

    private SandboxProperties sandboxProperties;
    private PreviewService previewService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        sandboxProperties = new SandboxProperties();
        previewService = new PreviewService();
        inject(previewService, "sandboxProperties", sandboxProperties);
        inject(previewService, "sandboxContainerManager", sandboxContainerManager);
    }

    @Test
    void startPreviewThrowsWhenDisabled() {
        sandboxProperties.setEnabled(false);
        BusinessException ex = assertThrows(BusinessException.class, () -> previewService.startPreview(1L));
        assertTrue(ex.getMessage().contains("沙箱未启用"), "应提示沙箱未启用");
    }

    @Test
    void startPreviewThrowsWhenBackendDirMissing(@TempDir Path tempDir) {
        sandboxProperties.setEnabled(true);
        // startPreview 先 ensureContainer（mock 不创建目录），随后检查后端目录不存在则抛异常
        BusinessException ex = assertThrows(BusinessException.class, () -> previewService.startPreview(1L));
        verify(sandboxContainerManager, times(1)).ensureContainer(1L);
        assertTrue(ex.getMessage().contains("后端目录不存在"), "实际: " + ex.getMessage());
    }

    @Test
    void sandboxPropertiesDefaultsAreIsolated() {
        // 验证沙箱专用中间件端口与平台错开（物理隔离的关键默认值）
        assertEquals(3307, sandboxProperties.getMysqlPort(), "沙箱 MySQL 端口应为 3307");
        assertEquals(6380, sandboxProperties.getRedisPort(), "沙箱 Redis 端口应为 6380");
        assertEquals("host.docker.internal", sandboxProperties.getMysqlHost());
        assertEquals("host.docker.internal", sandboxProperties.getRedisHost());
        assertEquals(8081, sandboxProperties.getContainerBackendPort());
    }

    private void inject(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
