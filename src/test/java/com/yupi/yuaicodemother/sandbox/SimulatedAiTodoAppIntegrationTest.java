package com.yupi.yuaicodemother.sandbox;

import com.yupi.yuaicodemother.ai.tools.ToolManager;
import com.yupi.yuaicodemother.constant.AppConstant;
import com.yupi.yuaicodemother.service.ProjectDownloadServiceImpl;
import com.yupi.yuaicodemother.testutil.SimulatedToolCallExecutor;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.sql.DataSource;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 模拟 AI 生成 todo 应用的集成测试（需要 Docker + 沙箱镜像）。
 *
 * <p>从 tool-sequences/todo-app.jsonl 读取固定的工具调用序列，等价于 AI 一次性完整输出，
 * 用 {@link SimulatedToolCallExecutor} 反射调用真实 @Tool 方法，驱动：
 * 沙箱拉起 → 沙箱写文件 → 后端编译 → 下载。
 */
@SpringBootTest
@Tag("docker")
class SimulatedAiTodoAppIntegrationTest {

    private static final long APP_ID = 777001L;
    private static final String PROJECT_DIR = "fullstack_" + APP_ID;

    @Autowired
    private ToolManager toolManager;

    @Autowired
    private SandboxContainerManager containerManager;

    @Autowired
    private ProjectDownloadServiceImpl downloadService;

    @Autowired
    private DataSource dataSource;

    @BeforeAll
    static void checkDocker() {
        assumeTrue(isDockerAvailable(), "Docker 不可用，跳过模拟 AI 集成测试");
    }

    @BeforeEach
    void prepare() throws Exception {
        // 插入 fullstack 类型 app，保证 ProjectPathResolver 解析到 fullstack_{appId}
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                    INSERT INTO app (id, appName, codeGenType, userId, priority, isDelete)
                    VALUES (%d, 'test-todo', 'fullstack', 1, 0, 0)
                    ON DUPLICATE KEY UPDATE codeGenType='fullstack'
                    """.formatted(APP_ID));
        }
        // 清理旧容器和目录
        try {
            new ProcessBuilder("docker", "rm", "-f", "code-sandbox-" + APP_ID)
                    .redirectErrorStream(true).start()
                    .waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    @Test
    void simulatedAiGeneratesTodoApp() throws Exception {
        // 1. 模拟 AI 拉起沙箱
        containerManager.ensureContainer(APP_ID);

        // 2. 读取工作序列文件并执行（等价于 AI 输出工具调用序列）
        Path sequenceFile = Paths.get(
                getClass().getClassLoader().getResource("tool-sequences/todo-app.jsonl").toURI());
        SimulatedToolCallExecutor executor = new SimulatedToolCallExecutor(toolManager, APP_ID);
        List<String> results = executor.execute(sequenceFile);

        // 3. 验证所有工具调用都成功（buildProject 返回含 ✅）
        for (String r : results) {
            assertNotNull(r, "工具执行结果不应为 null");
        }
        String buildResult = results.get(results.size() - 1);
        assertTrue(buildResult.contains("✅"), "后端编译应成功，实际: " + buildResult);

        // 4. 验证沙箱写文件落盘到宿主机（卷挂载一致）
        Path hostPom = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, PROJECT_DIR, "backend", "pom.xml");
        Path hostController = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, PROJECT_DIR, "backend",
                "src/main/java/com/example/TodoController.java");
        Path hostFrontend = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, PROJECT_DIR, "frontend", "index.html");
        assertTrue(Files.exists(hostPom), "backend/pom.xml 应落盘");
        assertTrue(Files.exists(hostController), "TodoController.java 应落盘");
        assertTrue(Files.exists(hostFrontend), "frontend/index.html 应落盘");

        // 5. 验证下载 zip 不含 sandbox 文件，含 todo 源码
        MockHttpServletResponse response = new MockHttpServletResponse();
        String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + PROJECT_DIR;
        downloadService.downloadProjectAsZip(projectPath, String.valueOf(APP_ID), response);

        byte[] zipBytes = response.getContentAsByteArray();
        assertTrue(zipBytes.length > 0);
        Set<String> entries = listZipEntries(zipBytes);
        assertTrue(entries.contains("backend/pom.xml"), "应保留 backend/pom.xml");
        assertTrue(entries.contains("backend/src/main/java/com/example/TodoController.java"));
        assertTrue(entries.contains("frontend/index.html"));
        for (String entry : entries) {
            assertFalse(entry.toLowerCase().contains("sandbox"), "下载不应含 sandbox 文件: " + entry);
        }
    }

    @AfterEach
    void cleanup() {
        try {
            new ProcessBuilder("docker", "rm", "-f", "code-sandbox-" + APP_ID)
                    .redirectErrorStream(true).start()
                    .waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        try {
            Files.walk(Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, PROJECT_DIR))
                    .sorted(java.util.Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
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

    private Set<String> listZipEntries(byte[] zipBytes) throws Exception {
        Set<String> entries = new java.util.HashSet<>();
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.add(entry.getName());
                }
            }
        }
        return entries;
    }
}
