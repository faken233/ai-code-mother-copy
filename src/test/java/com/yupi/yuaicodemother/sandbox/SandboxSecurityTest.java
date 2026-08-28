package com.yupi.yuaicodemother.sandbox;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 沙箱安全测试（需要 Docker + code-sandbox 镜像）
 * 验证路径穿越防护、命令白名单、正常命令执行。
 */
@SpringBootTest
@Tag("docker")
class SandboxSecurityTest {

    @Autowired
    private SandboxContainerManager containerManager;

    @Autowired
    private SandboxClient sandboxClient;

    @BeforeAll
    static void checkDocker() {
        assumeTrue(isDockerAvailable(), "Docker 不可用，跳过沙箱安全测试");
    }

    @BeforeEach
    void ensureContainer() {
        containerManager.ensureContainer(888001L);
    }

    @Test
    void pathTraversalIsRejected() {
        // 路径穿越应抛业务异常（沙箱 HTTP 服务返回 403，SandboxClient 抛 BusinessException）
        assertThrows(Exception.class, () -> sandboxClient.readFile("../../etc/passwd", 888001L),
                "路径穿越应被拒绝");
    }

    @Test
    void forbiddenCommandIsRejected() {
        var result = sandboxClient.exec("rm", java.util.List.of("-rf", "/"), "", 5000, 888001L);
        assertFalse(result.success, "rm 命令应被拒绝");
        assertTrue(result.stderr.contains("命令不被允许"), "应返回命令不被允许信息");
    }

    @Test
    void nodeVersionExecutes() {
        var result = sandboxClient.exec("node", java.util.List.of("-v"), "", 10000, 888001L);
        assertTrue(result.success, "node -v 应正常执行");
        assertTrue(result.stdout.contains("v"), "应返回 node 版本号");
    }

    @AfterAll
    static void cleanup() {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "rm", "-f", "code-sandbox-888001");
            pb.redirectErrorStream(true);
            pb.start().waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    private static boolean isDockerAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "info");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            return p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
