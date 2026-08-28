package com.yupi.yuaicodemother.ai.tools;

import com.yupi.yuaicodemother.sandbox.SandboxClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BuildProjectTool 单元测试（mock 沙箱客户端，无需 docker）
 * 验证 scope 参数路由、自动探测、失败信息返回。
 */
class BuildProjectToolTest {

    @Mock
    private SandboxClient sandboxClient;

    @Mock
    private ProjectPathResolver projectPathResolver;

    private BuildProjectTool tool;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        tool = new BuildProjectTool();
        // 反射注入 mock 依赖
        inject(tool, "sandboxClient", sandboxClient);
        inject(tool, "projectPathResolver", projectPathResolver);
    }

    @Test
    void buildBackendRoutesToMvnCompile(@TempDir Path tempDir) throws Exception {
        Path backend = tempDir.resolve("fullstack_1/backend");
        Files.createDirectories(backend);
        Files.writeString(backend.resolve("pom.xml"), "<project/>");

        when(projectPathResolver.resolveProjectRoot(1L)).thenReturn(tempDir.resolve("fullstack_1"));

        SandboxClient.CommandResult ok = new SandboxClient.CommandResult();
        ok.success = true;
        ok.exitCode = 0;
        when(sandboxClient.isEnabled()).thenReturn(true);
        when(sandboxClient.exec(eq("mvn"), anyList(), eq("backend"), anyInt(), eq(1L))).thenReturn(ok);

        String result = tool.buildProject("backend", true, 1L);

        assertTrue(result.contains("✅ 后端编译通过"), "后端编译应成功，实际: " + result);
        verify(sandboxClient).exec(eq("mvn"), anyList(), eq("backend"), anyInt(), eq(1L));
    }

    @Test
    void buildFrontendRoutesToNpm(@TempDir Path tempDir) throws Exception {
        Path frontend = tempDir.resolve("fullstack_1/frontend");
        Files.createDirectories(frontend);
        Files.writeString(frontend.resolve("package.json"), "{}");
        // buildFrontend 成功后还会校验 dist 目录，故需创建
        Files.createDirectories(frontend.resolve("dist"));

        when(projectPathResolver.resolveProjectRoot(1L)).thenReturn(tempDir.resolve("fullstack_1"));

        SandboxClient.CommandResult ok = new SandboxClient.CommandResult();
        ok.success = true;
        ok.exitCode = 0;
        when(sandboxClient.isEnabled()).thenReturn(true);
        when(sandboxClient.exec(eq("npm"), anyList(), eq("frontend"), anyInt(), eq(1L))).thenReturn(ok);

        String result = tool.buildProject("frontend", true, 1L);

        assertTrue(result.contains("✅ 前端构建成功"), "前端构建应成功，实际: " + result);
    }

    @Test
    void buildFailureReturnsError(@TempDir Path tempDir) throws Exception {
        Path backend = tempDir.resolve("fullstack_1/backend");
        Files.createDirectories(backend);
        Files.writeString(backend.resolve("pom.xml"), "<project/>");

        when(projectPathResolver.resolveProjectRoot(1L)).thenReturn(tempDir.resolve("fullstack_1"));

        SandboxClient.CommandResult fail = new SandboxClient.CommandResult();
        fail.success = false;
        fail.exitCode = 1;
        fail.stderr = "compilation error";
        when(sandboxClient.isEnabled()).thenReturn(true);
        when(sandboxClient.exec(eq("mvn"), anyList(), eq("backend"), anyInt(), eq(1L))).thenReturn(fail);

        String result = tool.buildProject("backend", true, 1L);

        assertTrue(result.contains("❌"), "构建失败应包含错误标记，实际: " + result);
        assertTrue(result.contains("compilation error"), "应包含错误信息");
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
