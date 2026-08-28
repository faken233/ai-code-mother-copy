package com.yupi.yuaicodemother.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 下载过滤器单元测试（无需 docker）
 * 验证用户下载的源码 zip 不含任何 sandbox 相关文件、不含构建产物，即"零隔离痕迹"。
 */
class ProjectDownloadServiceTest {

    private final ProjectDownloadServiceImpl downloadService = new ProjectDownloadServiceImpl();

    @Test
    void downloadExcludesSandboxAndBuildArtifacts(@TempDir Path tempDir) throws Exception {
        // 构造一个模拟的全栈项目目录
        Path projectRoot = tempDir.resolve("fullstack_1");
        writeFile(projectRoot.resolve("README.md"), "readme");
        writeFile(projectRoot.resolve("backend/pom.xml"), "<project/>");
        writeFile(projectRoot.resolve("backend/src/main/resources/application.yml"), "spring:\n  datasource:\n    url: jdbc:mysql://localhost:3306/app_db");
        // 沙箱专用配置（必须被排除）
        writeFile(projectRoot.resolve("backend/src/main/resources/application-sandbox.yml"), "spring:\n  datasource:\n    url: ${MYSQL_HOST}");
        writeFile(projectRoot.resolve("backend/src/main/java/com/example/SandboxConfig.java"), "class SandboxConfig {}");
        writeFile(projectRoot.resolve("frontend/index.html"), "html");
        // 构建产物（必须被排除）
        writeFile(projectRoot.resolve("backend/target/app.jar"), "jar");
        writeFile(projectRoot.resolve("frontend/node_modules/vue/index.js"), "js");
        writeFile(projectRoot.resolve("frontend/dist/index.html"), "built");
        writeFile(projectRoot.resolve(".git/config"), "git");

        MockHttpServletResponse response = new MockHttpServletResponse();
        downloadService.downloadProjectAsZip(projectRoot.toString(), "test", response);

        byte[] zipBytes = response.getContentAsByteArray();
        assertTrue(zipBytes.length > 0, "zip 不应为空");

        java.util.Set<String> entries = listZipEntries(zipBytes);

        // 允许保留的文件
        assertTrue(entries.contains("README.md"), "应保留 README.md");
        assertTrue(entries.contains("backend/pom.xml"), "应保留 backend/pom.xml");
        assertTrue(entries.contains("backend/src/main/resources/application.yml"), "应保留 application.yml");
        assertTrue(entries.contains("frontend/index.html"), "应保留 frontend/index.html");

        // 必须排除 sandbox 相关文件
        for (String entry : entries) {
            assertFalse(entry.toLowerCase().contains("sandbox"),
                    "zip 不应包含任何 sandbox 相关文件，实际含: " + entry);
        }

        // 必须排除构建产物
        assertFalse(entries.stream().anyMatch(e -> e.contains("node_modules")), "应排除 node_modules");
        assertFalse(entries.stream().anyMatch(e -> e.contains("target/")), "应排除 target");
        assertFalse(entries.stream().anyMatch(e -> e.contains("dist/")), "应排除 dist");
        assertFalse(entries.stream().anyMatch(e -> e.contains(".git/")), "应排除 .git");
    }

    private void writeFile(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private java.util.Set<String> listZipEntries(byte[] zipBytes) throws Exception {
        java.util.Set<String> entries = new java.util.HashSet<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
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
