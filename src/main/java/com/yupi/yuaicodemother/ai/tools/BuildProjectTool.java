package com.yupi.yuaicodemother.ai.tools;

import cn.hutool.json.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 项目构建工具
 * <p>
 * 两种项目形态：
 * - 一体化项目（根目录有 schema.json + server.js）：校验声明 → npm run build → node --check → 检查 public/dist
 * - 旧项目（frontend/ + backend/）：保持原有 npm / mvn 构建
 */
@Slf4j
@Component
public class BuildProjectTool extends BaseTool {

    @Resource
    private ProjectPathResolver projectPathResolver;

    @Resource
    private com.yupi.yuaicodemother.sandbox.SandboxClient sandboxClient;

    /**
     * npm install 超时时间（秒）
     */
    private static final int NPM_INSTALL_TIMEOUT = 300;

    /**
     * npm build 超时时间（秒）
     */
    private static final int NPM_BUILD_TIMEOUT = 180;

    /**
     * mvn compile 超时时间（秒）
     */
    private static final int MVN_BUILD_TIMEOUT = 300;

    /**
     * node 语法检查超时时间（秒）
     */
    private static final int NODE_CHECK_TIMEOUT = 60;

    /**
     * schema.json 允许的字段类型
     */
    private static final java.util.Set<String> ALLOWED_TYPES = java.util.Set.of(
            "string", "text", "int", "bigint", "decimal", "bool", "datetime", "date", "json", "enum");

    /**
     * 平台自动补的系统字段，schema 里不允许声明
     */
    private static final java.util.Set<String> SYSTEM_COLUMNS = java.util.Set.of(
            "id", "user_id", "created_at", "updated_at");

    /**
     * 最大错误输出长度（字符数）
     */
    private static final int MAX_ERROR_LENGTH = 4000;

    @Tool("构建并校验项目。一体化项目会校验 schema.json、扫描违禁用法、执行 npm run build 与 node 语法检查；旧项目按 scope 走前端/后端构建。如果失败会返回详细错误，你需要据此修复后重新调用此工具。")
    public String buildProject(
            @P("构建范围：frontend（前端）/ backend（后端），可选，缺省自动探测")
            String scope,
            @P("是否跳过依赖安装步骤，如果之前已经安装过依赖且只是修改代码，可以设置为 true 加快构建速度")
            boolean skipInstall,
            @ToolMemoryId Long appId
    ) {
        // 构建项目路径（按 codeGenType 自动适配）
        String projectPath = projectPathResolver.resolveProjectRoot(appId).toString();
        File projectDir = new File(projectPath);

        // 检查项目目录
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            return "❌ 构建失败：项目目录不存在 - " + projectPath;
        }

        // 一体化项目：根目录有 schema.json 即认为是新形态
        if (new File(projectDir, "schema.json").exists()) {
            return buildUnified(projectDir, skipInstall, appId);
        }

        // 自动探测构建范围
        String effectiveScope = scope;
        if (effectiveScope == null || effectiveScope.isBlank()) {
            if (new File(projectDir, "package.json").exists() || new File(projectDir, "frontend").exists()) {
                effectiveScope = "frontend";
            } else {
                effectiveScope = "backend";
            }
        }

        if ("frontend".equals(effectiveScope)) {
            return buildFrontend(projectDir, skipInstall, appId);
        } else if ("backend".equals(effectiveScope)) {
            return buildBackend(projectDir, appId);
        } else {
            return "❌ 构建失败：未知的构建范围 - " + scope;
        }
    }

    /**
     * 一体化项目构建：校验声明 → 违禁扫描 → 依赖 → 前端构建 → 服务端语法检查
     */
    private String buildUnified(File projectDir, boolean skipInstall, Long appId) {
        StringBuilder output = new StringBuilder();

        String schemaError = validateSchema(projectDir, projectPathResolver.resolveCodeGenType(appId));
        if (schemaError != null) {
            return schemaError;
        }
        output.append("✅ schema.json 校验通过\n");

        String scanError = scanForbidden(projectDir);
        if (scanError != null) {
            return scanError;
        }
        output.append("✅ 未发现违禁用法（裸 SQL / 硬编码地址）\n");

        boolean hasModules = new File(projectDir, "node_modules").exists();
        if (!skipInstall && !hasModules) {
            CommandResult installResult = execShell(projectDir,
                    "unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY; "
                            + "npm install --registry=https://registry.npmmirror.com --no-audit --no-fund",
                    NPM_INSTALL_TIMEOUT, appId);
            if (!installResult.success) {
                return formatBuildError("npm install", installResult);
            }
            output.append("✅ npm install 成功\n");
        } else {
            output.append(hasModules ? "⏭️ 依赖已存在，跳过安装\n" : "⏭️ 跳过依赖安装\n");
        }

        CommandResult buildResult = execShell(projectDir, "npm run build", NPM_BUILD_TIMEOUT, appId);
        if (!buildResult.success) {
            return formatBuildError("npm run build", buildResult);
        }
        output.append("✅ npm run build 成功\n");

        File indexFile = new File(projectDir, "public/dist/index.html");
        if (!indexFile.exists()) {
            return "❌ 构建失败：public/dist/index.html 未生成。请不要修改 vite.config.js（平台文件），检查 client/src/App.jsx 是否可编译。";
        }
        output.append("✅ public/dist/index.html 已生成\n");

        CommandResult checkResult = execShell(projectDir, "node --check server.js", NODE_CHECK_TIMEOUT, appId);
        if (!checkResult.success) {
            return formatBuildError("node --check server.js", checkResult);
        }
        output.append("✅ server.js 语法检查通过");
        return output.toString();
    }

    /**
     * 校验 schema.json：结构、表名、字段类型、系统字段。
     * <p>
     * 纯前端（STATIC）与带存储（FULLSTACK）的契约相反：
     * - STATIC：tables 必须为空、auth 必须关闭（数据只能放内存/localStorage）
     * - FULLSTACK：至少声明一张表
     */
    private String validateSchema(File projectDir, CodeGenTypeEnum codeGenType) {
        File schemaFile = new File(projectDir, "schema.json");
        if (!schemaFile.exists()) {
            return "❌ 缺少 schema.json：你必须先声明应用信息";
        }
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(schemaFile);
        } catch (Exception e) {
            return "❌ schema.json 不是合法 JSON: " + e.getMessage();
        }
        JsonNode tables = root.get("tables");
        boolean emptyTables = tables == null || !tables.isObject() || tables.isEmpty();
        if (codeGenType == CodeGenTypeEnum.STATIC) {
            if (root.path("auth").path("enabled").asBoolean(false)) {
                return "❌ 纯前端项目不能开启登录（schema.auth.enabled 必须为 false）";
            }
            if (!emptyTables) {
                return "❌ 纯前端项目不能声明数据表（schema.tables 必须为空对象）";
            }
            return null;
        }
        if (emptyTables) {
            return "❌ schema.json 缺少 tables，至少要声明一张表";
        }
        Iterator<String> tableNames = tables.fieldNames();
        while (tableNames.hasNext()) {
            String table = tableNames.next();
            if (!table.matches("[a-z][a-z0-9_]{1,40}")) {
                return "❌ 表名不合法：" + table + "（小写字母开头，只含小写字母、数字、下划线）";
            }
            JsonNode meta = tables.get(table);
            JsonNode columns = meta.get("columns");
            if (columns == null || !columns.isObject() || columns.isEmpty()) {
                return "❌ 表 " + table + " 缺少 columns";
            }
            Iterator<String> columnNames = columns.fieldNames();
            while (columnNames.hasNext()) {
                String column = columnNames.next();
                if (SYSTEM_COLUMNS.contains(column)) {
                    return "❌ 表 " + table + " 声明了系统字段 " + column + "：id/user_id/created_at/updated_at 由平台自动生成，请删除";
                }
                if (!column.matches("[a-z][a-z0-9_]{0,40}")) {
                    return "❌ 字段名不合法：" + table + "." + column + "（小写字母开头，只含小写字母、数字、下划线）";
                }
                JsonNode def = columns.get(column);
                String type = def.path("type").asText("");
                if (!ALLOWED_TYPES.contains(type)) {
                    return "❌ 字段 " + table + "." + column + " 的类型不受支持：" + type
                            + "，可选 " + ALLOWED_TYPES;
                }
                if ("enum".equals(type)) {
                    JsonNode values = def.get("values");
                    if (values == null || !values.isArray() || values.isEmpty()) {
                        return "❌ 枚举字段 " + table + "." + column + " 必须提供非空 values 数组";
                    }
                }
            }
        }
        return null;
    }

    /**
     * 违禁用法扫描：自定义路由里出现裸 SQL、页面里出现硬编码地址，都会让应用在预览/短链下失效
     */
    private String scanForbidden(File projectDir) {
        File customRoute = new File(projectDir, "routes/custom.js");
        if (customRoute.exists()) {
            try {
                String content = Files.readString(customRoute.toPath(), StandardCharsets.UTF_8);
                for (String pattern : List.of("pool.", "execute(", "SELECT ", "INSERT ", "UPDATE ", "DELETE ", "process.env.DB")) {
                    if (content.contains(pattern)) {
                        return "❌ routes/custom.js 出现禁用写法：" + pattern
                                + "。自定义接口只能用 ctx.db 的 list/get/insert/update/remove/count，禁止裸 SQL";
                    }
                }
            } catch (Exception e) {
                return "❌ 读取 routes/custom.js 失败：" + e.getMessage();
            }
        }

        File clientSrc = new File(projectDir, "client/src");
        if (clientSrc.isDirectory()) {
            java.util.List<File> sources = new java.util.ArrayList<>();
            collectFiles(clientSrc, sources);
            for (File file : sources) {
                // 平台文件本身不参与扫描（例如 api.js 注释里就提到 /api/preview/）
                String relative = projectDir.toPath().relativize(file.toPath()).toString().replace('\\', '/');
                if (com.yupi.yuaicodemother.template.AppTemplateService.isPlatformOwned(relative)) {
                    continue;
                }
                try {
                    String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                    for (String pattern : List.of("localhost:", "127.0.0.1", "/api/preview/")) {
                        if (content.contains(pattern)) {
                            return "❌ 前端出现硬编码地址：" + pattern + "（" + file.getName()
                                    + "）。接口必须用 ./api 相对路径，禁止写死主机/端口/appId";
                        }
                    }
                } catch (Exception e) {
                    return "❌ 读取前端文件失败：" + e.getMessage();
                }
            }
        }
        return null;
    }

    private void collectFiles(File dir, java.util.List<File> result) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                collectFiles(file, result);
            } else if (file.getName().endsWith(".js") || file.getName().endsWith(".jsx")
                    || file.getName().endsWith(".ts") || file.getName().endsWith(".tsx")) {
                result.add(file);
            }
        }
    }

    /**
     * 构建前端项目
     */
    private String buildFrontend(File projectDir, boolean skipInstall, Long appId) {
        File frontendDir = new File(projectDir, "frontend");
        if (new File(projectDir, "package.json").exists()) {
            // 项目根目录即前端
            frontendDir = projectDir;
        }
        if (!new File(frontendDir, "package.json").exists()) {
            return "❌ 构建失败：项目目录中没有 package.json 文件，请先创建前端 package.json";
        }

        StringBuilder resultBuilder = new StringBuilder();
        // 执行 npm install（可选跳过）
        if (!skipInstall) {
            CommandResult installResult = executeCommand(frontendDir, buildCommand("npm") + " install", NPM_INSTALL_TIMEOUT, appId);
            if (!installResult.success) {
                return formatBuildError("npm install", installResult);
            }
            resultBuilder.append("✅ npm install 成功\n");
        } else {
            resultBuilder.append("⏭️ 跳过 npm install\n");
        }

        // 执行 npm run build
        CommandResult buildResult = executeCommand(frontendDir, buildCommand("npm") + " run build", NPM_BUILD_TIMEOUT, appId);
        if (!buildResult.success) {
            return formatBuildError("npm run build", buildResult);
        }
        resultBuilder.append("✅ npm run build 成功\n");

        // 验证 dist 目录
        File distDir = new File(frontendDir, "dist");
        if (!distDir.exists() || !distDir.isDirectory()) {
            return "❌ 构建失败：构建完成但 dist 目录未生成，请检查 vite.config.js 或 vue.config.js 配置";
        }

        resultBuilder.append("✅ 前端构建成功，dist 目录已生成");
        log.info("前端构建成功：{}", frontendDir.getPath());
        return resultBuilder.toString();
    }

    /**
     * 构建后端项目
     */
    private String buildBackend(File projectDir, Long appId) {
        File backendDir = new File(projectDir, "backend");
        if (new File(projectDir, "pom.xml").exists()) {
            // 项目根目录即后端
            backendDir = projectDir;
        }
        if (!new File(backendDir, "pom.xml").exists()) {
            return "❌ 构建失败：项目目录中没有 pom.xml 文件，请先创建后端 pom.xml";
        }

        StringBuilder resultBuilder = new StringBuilder();
        // 执行 mvn -q compile
        CommandResult compileResult = executeCommand(backendDir, buildCommand("mvn") + " -q compile", MVN_BUILD_TIMEOUT, appId);
        if (!compileResult.success) {
            return formatBuildError("mvn compile", compileResult);
        }
        resultBuilder.append("✅ mvn compile 成功\n");
        resultBuilder.append("✅ 后端编译通过");
        log.info("后端编译通过：{}", backendDir.getPath());
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
     * 用 sh -c 执行脚本。
     * <p>
     * 沙箱的命令白名单只有 npm/mvn/java/node/javac/sh/bash，没有 env，
     * 所以"临时去掉 http_proxy"这类操作要交给 sh 自己 unset。
     */
    private CommandResult execShell(File workingDir, String script, int timeoutSeconds, Long appId) {
        CommandResult result = new CommandResult();
        try {
            if (sandboxClient.isEnabled()) {
                String name = workingDir.getName();
                String cwd = ("frontend".equals(name) || "backend".equals(name)) ? name : "";
                com.yupi.yuaicodemother.sandbox.SandboxClient.CommandResult sandboxResult =
                        sandboxClient.exec("sh", List.of("-c", script), cwd, timeoutSeconds * 1000, appId);
                result.success = sandboxResult.success;
                result.timeout = sandboxResult.timedOut;
                result.exitCode = sandboxResult.exitCode;
                result.stdout = sandboxResult.stdout;
                result.stderr = sandboxResult.stderr;
                return result;
            }

            ProcessBuilder pb = new ProcessBuilder("sh", "-c", script);
            pb.directory(workingDir);
            pb.redirectErrorStream(false);
            Process process = pb.start();
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Thread t1 = Thread.ofVirtual().start(() -> out.append(new String(readAll(process.getInputStream()), StandardCharsets.UTF_8)));
            Thread t2 = Thread.ofVirtual().start(() -> err.append(new String(readAll(process.getErrorStream()), StandardCharsets.UTF_8)));
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                result.timeout = true;
                return result;
            }
            t1.join(3000);
            t2.join(3000);
            result.exitCode = process.exitValue();
            result.stdout = out.toString();
            result.stderr = err.toString();
            result.success = result.exitCode == 0;
            return result;
        } catch (Exception e) {
            result.success = false;
            result.stderr = "命令执行异常: " + e.getMessage();
            return result;
        }
    }

    private byte[] readAll(java.io.InputStream in) {
        try {
            return in.readAllBytes();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    /**
     * 执行命令并捕获输出
     *
     * @param workingDir     工作目录
     * @param command        命令字符串
     * @param timeoutSeconds 超时时间（秒）
     * @param appId          应用 ID（用于沙箱路由）
     * @return 命令执行结果
     */
    private CommandResult executeCommand(File workingDir, String command, int timeoutSeconds, Long appId) {
        CommandResult result = new CommandResult();
        Process process = null;

        try {
            log.info("在目录 {} 中执行命令: {}", workingDir.getAbsolutePath(), command);

            // 沙箱启用时，走沙箱 exec（容器内执行 npm/mvn）
            if (sandboxClient.isEnabled()) {
                String[] parts = command.split("\\s+");
                String cmd = parts[0];
                java.util.List<String> args = new java.util.ArrayList<>();
                for (int i = 1; i < parts.length; i++) {
                    args.add(parts[i]);
                }
                String cwd = workingDir.getName().equals("frontend") || workingDir.getName().equals("backend")
                        ? workingDir.getName()
                        : "";
                com.yupi.yuaicodemother.sandbox.SandboxClient.CommandResult sandboxResult =
                        sandboxClient.exec(cmd, args, cwd, timeoutSeconds * 1000, appId);
                result.success = sandboxResult.success;
                result.timeout = sandboxResult.timedOut;
                result.exitCode = sandboxResult.exitCode;
                result.stdout = sandboxResult.stdout;
                result.stderr = sandboxResult.stderr;
                return result;
            }

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
        String scope = arguments.getStr("scope");
        if (scope == null) {
            scope = "auto";
        }
        return String.format("[工具调用] %s (scope=%s, skipInstall=%s)", getDisplayName(), scope, skipInstall);
    }
}
