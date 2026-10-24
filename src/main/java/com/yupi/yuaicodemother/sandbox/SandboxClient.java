package com.yupi.yuaicodemother.sandbox;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.yupi.yuaicodemother.ai.tools.ProjectPathResolver;
import com.yupi.yuaicodemother.exception.BusinessException;
import com.yupi.yuaicodemother.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

/**
 * 代码沙箱客户端
 * 当沙箱启用时，通过 HTTP 与容器内 Node 服务交互读写文件/执行命令；
 * 沙箱未启用时，降级为本地直写（保持原有逻辑）。
 *
 * <p>关键约定：容器启动时用 {@code -v tmp/code_output/fullstack_{appId}:/workspace} 卷挂载，
 * 因此容器内 {@code /workspace/xxx} 即宿主机 {@code tmp/code_output/fullstack_{appId}/xxx}，
 * 两者是同一份文件。AI 传入的 {@code relativeFilePath}（如 {@code backend/pom.xml}）
 * 相对项目根目录，在容器内经归一化后落在 {@code /workspace/backend/pom.xml}。
 *
 * <p>懒拉起：任何需要沙箱的操作（writeFile/readFile/exec 等）前，先 ensureContainer(appId)，
 * 避免 AI 直接调工具时容器尚未启动导致 Connection refused。
 */
@Slf4j
@Component
public class SandboxClient {

    @Resource
    private SandboxProperties sandboxProperties;

    @Resource
    private ProjectPathResolver projectPathResolver;

    @Resource
    @Lazy
    private SandboxContainerManager sandboxContainerManager;

    @Resource
    private ToolTokenService toolTokenService;

    /**
     * 是否启用沙箱
     */
    public boolean isEnabled() {
        return sandboxProperties.isEnabled();
    }

    /**
     * 确保沙箱容器已就绪（懒拉起，幂等）。
     * 沙箱未启用时不做任何事（走本地直写）。
     */
    private void ensureContainer(Long appId) {
        if (isEnabled() && sandboxContainerManager != null) {
            sandboxContainerManager.ensureContainer(appId);
        }
    }

    /**
     * 工具通道的宿主端口（映射容器内 node 文件服务 8080）。
     * 以 docker port 回读为准，不再按 appId 取模。
     */
    public int getPreviewPort(Long appId) {
        return sandboxContainerManager.toolHostPort(appId);
    }

    /**
     * 构建沙箱容器内 HTTP 服务地址（通过宿主映射端口访问）
     * host 由 sandbox.host 控制：本地直跑=localhost，compose 内=host.docker.internal
     */
    private String buildBaseUrl(Long appId) {
        return "http://" + sandboxProperties.getHost() + ":" + getPreviewPort(appId);
    }

    /**
     * 向沙箱发送 POST 请求，返回 JSON 中的 data 字段
     */
    private JSONObject post(Long appId, String path, Map<String, Object> payload) {
        return post(appId, path, payload, 60000);
    }

    private JSONObject post(Long appId, String path, Map<String, Object> payload, int timeoutMs) {
        // 懒拉起：确保沙箱容器已就绪，避免 AI 首次调工具时容器未启动
        ensureContainer(appId);
        String url = buildBaseUrl(appId) + path;
        try {
            HttpResponse response = HttpRequest.post(url)
                    // 工具通道鉴权：令牌由容器启动时注入，见 ToolTokenService
                    .header("X-Tool-Token", toolTokenService.tokenOf(appId))
                    .body(JSONUtil.toJsonStr(payload))
                    .timeout(timeoutMs)
                    .execute();
            JSONObject json = JSONUtil.parseObj(response.body());
            if (!json.getBool("ok", false)) {
                log.error("沙箱接口返回失败: {} -> {}", path, json.getStr("error"));
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "沙箱操作失败: " + json.getStr("error"));
            }
            return json.getJSONObject("data");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("沙箱接口调用异常: {} -> {}", url, e.getMessage());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "沙箱服务不可用: " + e.getMessage());
        }
    }

    /**
     * 重启运行中的应用进程（不重新构建），等待应用健康。
     * 生成结束后的热更新入口；schema 变更在应用启动时由 migrate 自动应用。
     */
    public JSONObject restartApp(Long appId) {
        return post(appId, "/api/app/restart", Map.of(), 120_000);
    }

    /**
     * 重建 + 重启应用。
     *
     * @param forceBuild true 时执行 npm run build；false 时只校验已有构建产物
     *                   （生成流程已由平台 BuildProjectTool 构建过，避免重复构建）
     */
    public JSONObject rebuildApp(Long appId, boolean forceBuild) {
        return post(appId, "/api/app/rebuild", Map.of("forceBuild", forceBuild), 240_000);
    }

    /**
     * 本地直写的项目根目录（沙箱未启用时使用）
     */
    private Path resolveLocalRoot(Long appId) {
        return projectPathResolver.resolveProjectRoot(appId);
    }

    /**
     * 写入文件
     */
    public void writeFile(String relativeFilePath, String content, Long appId) {
        if (!isEnabled()) {
            Path path = resolveLocalRoot(appId).resolve(relativeFilePath);
            try {
                Files.createDirectories(path.getParent());
                Files.write(path, content.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (Exception e) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "写入文件失败: " + e.getMessage());
            }
            return;
        }
        post(appId, "/api/files/write", Map.of("relativeFilePath", relativeFilePath, "content", content));
    }

    /**
     * 读取文件
     */
    public String readFile(String relativeFilePath, Long appId) {
        if (!isEnabled()) {
            Path path = resolveLocalRoot(appId).resolve(relativeFilePath);
            try {
                if (!Files.exists(path) || !Files.isRegularFile(path)) {
                    return "错误：文件不存在或不是文件 - " + relativeFilePath;
                }
                return Files.readString(path);
            } catch (Exception e) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取文件失败: " + e.getMessage());
            }
        }
        JSONObject data = post(appId, "/api/files/read", Map.of("relativeFilePath", relativeFilePath));
        return data.getStr("content");
    }

    /**
     * 修改文件（返回处理结果文案）
     */
    public String modifyFile(String relativeFilePath, String oldContent, String newContent, Long appId) {
        if (!isEnabled()) {
            Path path = resolveLocalRoot(appId).resolve(relativeFilePath);
            try {
                if (!Files.exists(path) || !Files.isRegularFile(path)) {
                    return "错误：文件不存在或不是文件 - " + relativeFilePath;
                }
                String original = Files.readString(path);
                if (!original.contains(oldContent)) {
                    return "警告：文件中未找到要替换的内容，文件未修改 - " + relativeFilePath;
                }
                String modified = original.replace(oldContent, newContent);
                Files.writeString(path, modified, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                return "文件修改成功: " + relativeFilePath;
            } catch (Exception e) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "修改文件失败: " + e.getMessage());
            }
        }
        post(appId, "/api/files/modify",
                Map.of("relativeFilePath", relativeFilePath, "oldContent", oldContent, "newContent", newContent));
        return "文件修改成功: " + relativeFilePath;
    }

    /**
     * 删除文件
     */
    public String deleteFile(String relativeFilePath, Long appId) {
        if (!isEnabled()) {
            Path path = resolveLocalRoot(appId).resolve(relativeFilePath);
            try {
                if (!Files.exists(path)) {
                    return "警告：文件不存在，无需删除 - " + relativeFilePath;
                }
                if (!Files.isRegularFile(path)) {
                    return "错误：指定路径不是文件，无法删除 - " + relativeFilePath;
                }
                Files.delete(path);
                return "文件删除成功: " + relativeFilePath;
            } catch (Exception e) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "删除文件失败: " + e.getMessage());
            }
        }
        post(appId, "/api/files/delete", Map.of("relativeFilePath", relativeFilePath));
        return "文件删除成功: " + relativeFilePath;
    }

    /**
     * 读取目录结构
     */
    public String listDir(String relativeDirPath, Long appId) {
        if (!isEnabled()) {
            Path root = resolveLocalRoot(appId);
            Path path = root.resolve(relativeDirPath == null ? "" : relativeDirPath);
            try {
                if (!Files.exists(path) || !Files.isDirectory(path)) {
                    return "错误：目录不存在或不是目录 - " + relativeDirPath;
                }
                StringBuilder sb = new StringBuilder("项目目录结构:\n");
                try (var stream = Files.list(path)) {
                    stream.sorted().forEach(p -> sb.append(p.getFileName()).append("\n"));
                }
                return sb.toString();
            } catch (Exception e) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取目录失败: " + e.getMessage());
            }
        }
        JSONObject data = post(appId, "/api/files/list", Map.of("relativeDirPath", relativeDirPath == null ? "" : relativeDirPath));
        cn.hutool.json.JSONArray files = data.getJSONArray("files");
        StringBuilder sb = new StringBuilder("项目目录结构:\n");
        if (files != null) {
            for (Object item : files) {
                JSONObject obj = JSONUtil.parseObj(item.toString());
                int depth = obj.getStr("path").split("/").length;
                sb.append("  ".repeat(Math.max(0, depth - 1))).append(obj.getStr("path")).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 执行命令
     */
    public CommandResult exec(String cmd, List<String> args, String cwd, int timeoutMs, Long appId) {
        if (!isEnabled()) {
            log.warn("沙箱未启用，无法执行命令: {}", cmd);
            CommandResult result = new CommandResult();
            result.success = false;
            result.stderr = "沙箱未启用，命令执行不可用";
            return result;
        }
        JSONObject data = post(appId, "/api/exec",
                Map.of("cmd", cmd, "args", args == null ? List.of() : args,
                        "cwd", cwd == null ? "" : cwd, "timeoutMs", timeoutMs));
        CommandResult result = new CommandResult();
        result.success = data.getInt("code", -1) == 0;
        result.exitCode = data.getInt("code", -1);
        result.stdout = data.getStr("stdout", "");
        result.stderr = data.getStr("stderr", "");
        result.timedOut = data.getBool("timedOut", false);
        return result;
    }

    /**
     * 命令执行结果
     */
    public static class CommandResult {
        public boolean success = false;
        public boolean timedOut = false;
        public int exitCode = -1;
        public String stdout = "";
        public String stderr = "";
    }
}
