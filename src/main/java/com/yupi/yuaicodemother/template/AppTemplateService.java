package com.yupi.yuaicodemother.template;

import com.yupi.yuaicodemother.exception.BusinessException;
import com.yupi.yuaicodemother.exception.ErrorCode;
import com.yupi.yuaicodemother.sandbox.SandboxClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 平台模板安装器。
 * <p>
 * 职责边界：
 * - 平台负责：运行时（server/db/auth/query/validate/migrate）、构建配置、前端骨架（入口/API 封装/基础样式）
 * - AI 负责：schema.json、client/src/App.jsx（页面）、routes/custom.js（可选业务逻辑）
 * <p>
 * 生成任务开始时把平台文件写入项目目录；AI 对这些路径的写入会被工具层拒绝。
 */
@Slf4j
@Service
public class AppTemplateService {

    /**
     * 模板源文件（相对模板根） → 项目内目标路径
     */
    private static final Map<String, String> FILES = new LinkedHashMap<>();

    static {
        FILES.put("package.json", "package.json");
        FILES.put("vite.config.js", "vite.config.js");

        FILES.put("runtime/server.js", "server.js");
        FILES.put("runtime/config.js", "config.js");
        FILES.put("runtime/db.js", "db.js");
        FILES.put("runtime/auth.js", "auth.js");
        FILES.put("runtime/migrate.js", "migrate.js");
        FILES.put("runtime/lib/ddl.js", "lib/ddl.js");
        FILES.put("runtime/lib/query.js", "lib/query.js");
        FILES.put("runtime/lib/validate.js", "lib/validate.js");
        FILES.put("runtime/lib/errors.js", "lib/errors.js");
        FILES.put("runtime/routes/api.js", "routes/api.js");

        FILES.put("client/index.html", "client/index.html");
        FILES.put("client/src/main.jsx", "client/src/main.jsx");
        FILES.put("client/src/api.js", "client/src/api.js");
        FILES.put("client/src/styles.css", "client/src/styles.css");
    }

    /**
     * 平台拥有的路径集合（写文件工具据此拒绝 AI 覆盖）
     */
    public static final Set<String> PLATFORM_OWNED = Set.copyOf(FILES.values());

    @Value("${platform.template-dir:}")
    private String configuredTemplateDir;

    @Resource
    private SandboxClient sandboxClient;

    /**
     * 把平台文件写入项目目录（幂等，覆盖旧版本）
     */
    public void install(Long appId) {
        Path root = templateRoot();
        int installed = 0;
        for (Map.Entry<String, String> entry : FILES.entrySet()) {
            Path source = root.resolve(entry.getKey());
            if (!Files.isRegularFile(source)) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "平台模板文件缺失: " + source);
            }
            try {
                String content = Files.readString(source, StandardCharsets.UTF_8);
                sandboxClient.writeFile(entry.getValue(), content, appId);
                installed++;
            } catch (IOException e) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取平台模板失败: " + source + ", " + e.getMessage());
            }
        }
        log.info("已安装平台模板 {} 个文件, appId: {}", installed, appId);
    }

    /**
     * 判断路径是否属于平台（AI 不可写）
     */
    public static boolean isPlatformOwned(String relativePath) {
        return PLATFORM_OWNED.contains(normalize(relativePath));
    }

    /**
     * 规范化相对路径：统一分隔符、去掉 ./ 与前导 /
     */
    public static String normalize(String relativePath) {
        if (relativePath == null) {
            return "";
        }
        String path = relativePath.replace('\\', '/').trim();
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isEmpty()) {
            return "";
        }
        try {
            // 折叠 "." 与 ".."：client/src/./api.js 必须被识别成 client/src/api.js
            return java.nio.file.Paths.get(path).normalize().toString().replace('\\', '/');
        } catch (Exception e) {
            return path;
        }
    }

    private Path templateRoot() {
        String dir = (configuredTemplateDir == null || configuredTemplateDir.isBlank())
                ? System.getProperty("user.dir") + "/templates/node-unified"
                : configuredTemplateDir;
        return Path.of(dir);
    }
}
