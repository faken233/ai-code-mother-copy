package com.yupi.yuaicodemother.ai.tools;

import cn.hutool.json.JSONObject;
import com.yupi.yuaicodemother.constant.AppConstant;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * 项目构建工具
 * 支持 AI 调用来构建 Vue 项目，如果构建失败会返回错误信息给 AI 进行修复
 */
@Slf4j
@Component
public class BuildProjectTool extends BaseTool {

    /**
     * npm install 超时时间（秒）
     */
    private static final int NPM_INSTALL_TIMEOUT = 300;

    /**
     * npm build 超时时间（秒）
     */
    private static final int NPM_BUILD_TIMEOUT = 180;

    /**
     * 最大错误输出长度（字符数）
     */
    private static final int MAX_ERROR_LENGTH = 4000;

    @Tool("构建 Vue 项目，执行 npm install 和 npm run build。如果构建失败，会返回详细的错误信息，你需要根据错误信息修复代码后重新调用此工具。")
    public String buildProject(
            @P("是否跳过 npm install 步骤，如果之前已经安装过依赖且只是修改代码，可以设置为 true 加快构建速度")
            boolean skipInstall,
            @ToolMemoryId Long appId
    ) {
        // 构建项目路径
        String projectDirName = "vue_project_" + appId;
        String projectPath = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, projectDirName).toString();
        File projectDir = new File(projectPath);

        // 检查项目目录
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            return "❌ 构建失败：项目目录不存在 - " + projectPath;
        }

        // 检查 package.json
        File packageJsonFile = new File(projectDir, "package.json");
        if (!packageJsonFile.exists()) {
            return "❌ 构建失败：项目目录中没有 package.json 文件，请先创建 package.json";
        }

        log.info("开始构建 Vue 项目：{}", projectPath);
        StringBuilder resultBuilder = new StringBuilder();

        // 执行 npm install（可选跳过）
        if (!skipInstall) {
            log.info("执行 npm install...");
            CommandResult installResult = executeCommand(projectDir, buildCommand("npm") + " install", NPM_INSTALL_TIMEOUT);
            if (!installResult.success) {
                String errorMsg = formatBuildError("npm install", installResult);
                log.error("npm install 执行失败：{}", errorMsg);
                return errorMsg;
            }
            resultBuilder.append("✅ npm install 成功\n");
        } else {
            resultBuilder.append("⏭️ 跳过 npm install\n");
        }

        // 执行 npm run build
        log.info("执行 npm run build...");
        CommandResult buildResult = executeCommand(projectDir, buildCommand("npm") + " run build", NPM_BUILD_TIMEOUT);
        if (!buildResult.success) {
            String errorMsg = formatBuildError("npm run build", buildResult);
            log.error("npm run build 执行失败：{}", errorMsg);
            return errorMsg;
        }
        resultBuilder.append("✅ npm run build 成功\n");

        // 验证 dist 目录
        File distDir = new File(projectDir, "dist");
        if (!distDir.exists() || !distDir.isDirectory()) {
            return "❌ 构建失败：构建完成但 dist 目录未生成，请检查 vite.config.js 或 vue.config.js 配置";
        }

        resultBuilder.append("✅ Vue 项目构建成功，dist 目录已生成");
        log.info("Vue 项目构建成功：{}", projectPath);
        return resultBuilder.toString();
    }

    /**
     * 格式化构建错误信息
     */
    private String formatBuildError(String command, CommandResult result) {
        StringBuilder errorBuilder = new StringBuilder();
        errorBuilder.append("❌ 构建失败：").append(command).append(" 执行失败\n\n");

        if (result.timeout) {
            errorBuilder.append("⏱️ 错误原因：命令执行超时\n");
        } else {
            errorBuilder.append("📋 退出码：").append(result.exitCode).append("\n\n");
        }

        // 优先显示 stderr（错误输出）
        if (result.stderr != null && !result.stderr.isBlank()) {
            errorBuilder.append("🔴 错误输出：\n```\n");
            errorBuilder.append(truncateString(result.stderr, MAX_ERROR_LENGTH));
            errorBuilder.append("\n```\n\n");
        }

        // 也显示 stdout（有时错误信息在标准输出中）
        if (result.stdout != null && !result.stdout.isBlank()) {
            // 只有当 stdout 包含 error 关键字时才显示
            if (result.stdout.toLowerCase().contains("error")) {
                errorBuilder.append("📝 标准输出（包含错误信息）：\n```\n");
                errorBuilder.append(truncateString(result.stdout, MAX_ERROR_LENGTH / 2));
                errorBuilder.append("\n```\n\n");
            }
        }

        errorBuilder.append("请根据以上错误信息修复相关代码，然后重新调用 buildProject 工具（可设置 skipInstall=true 跳过依赖安装）。");
        return errorBuilder.toString();
    }

    /**
     * 截断过长的字符串
     */
    private String truncateString(String str, int maxLength) {
        if (str == null) {
            return "";
        }
        if (str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength) + "\n... (输出过长，已截断)";
    }

    /**
     * 根据操作系统构造命令
     */
    private String buildCommand(String baseCommand) {
        if (isWindows()) {
            return baseCommand + ".cmd";
        }
        return baseCommand;
    }

    /**
     * 操作系统检测
     */
    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }

    /**
     * 执行命令并捕获输出
     *
     * @param workingDir     工作目录
     * @param command        命令字符串
     * @param timeoutSeconds 超时时间（秒）
     * @return 命令执行结果
     */
    private CommandResult executeCommand(File workingDir, String command, int timeoutSeconds) {
        CommandResult result = new CommandResult();
        Process process = null;

        try {
            log.info("在目录 {} 中执行命令: {}", workingDir.getAbsolutePath(), command);

            ProcessBuilder processBuilder = new ProcessBuilder();
            processBuilder.directory(workingDir);

            // 根据操作系统设置命令
            if (isWindows()) {
                processBuilder.command("cmd", "/c", command);
            } else {
                processBuilder.command("sh", "-c", command);
            }

            // 合并错误流到标准输出，方便捕获所有输出
            processBuilder.redirectErrorStream(false);

            process = processBuilder.start();

            // 异步读取标准输出和错误输出
            StringBuilder stdoutBuilder = new StringBuilder();
            StringBuilder stderrBuilder = new StringBuilder();

            Process finalProcess1 = process;
            Thread stdoutThread = Thread.ofVirtual().start(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(finalProcess1.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdoutBuilder.append(line).append("\n");
                    }
                } catch (Exception e) {
                    log.warn("读取标准输出失败: {}", e.getMessage());
                }
            });

            Process finalProcess = process;
            Thread stderrThread = Thread.ofVirtual().start(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(finalProcess.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderrBuilder.append(line).append("\n");
                    }
                } catch (Exception e) {
                    log.warn("读取错误输出失败: {}", e.getMessage());
                }
            });

            // 等待进程完成
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                log.error("命令执行超时（{}秒），强制终止进程", timeoutSeconds);
                process.destroyForcibly();
                result.timeout = true;
                result.success = false;
                return result;
            }

            // 等待输出读取完成
            stdoutThread.join(5000);
            stderrThread.join(5000);

            result.exitCode = process.exitValue();
            result.stdout = stdoutBuilder.toString();
            result.stderr = stderrBuilder.toString();
            result.success = (result.exitCode == 0);

            if (result.success) {
                log.info("命令执行成功: {}", command);
            } else {
                log.error("命令执行失败，退出码: {}", result.exitCode);
            }

        } catch (Exception e) {
            log.error("执行命令失败: {}, 错误信息: {}", command, e.getMessage());
            result.success = false;
            result.stderr = "命令执行异常: " + e.getMessage();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }

        return result;
    }

    /**
     * 命令执行结果
     */
    private static class CommandResult {
        boolean success = false;
        boolean timeout = false;
        int exitCode = -1;
        String stdout = "";
        String stderr = "";
    }

    @Override
    public String getToolName() {
        return "buildProject";
    }

    @Override
    public String getDisplayName() {
        return "构建项目";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        boolean skipInstall = arguments.getBool("skipInstall", false);
        return String.format("[工具调用] %s (skipInstall=%s)", getDisplayName(), skipInstall);
    }
}
