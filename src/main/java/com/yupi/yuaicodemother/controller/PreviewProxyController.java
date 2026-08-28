package com.yupi.yuaicodemother.controller;

import com.yupi.yuaicodemother.constant.AppConstant;
import com.yupi.yuaicodemother.sandbox.SandboxClient;
import com.yupi.yuaicodemother.sandbox.SandboxProperties;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.HandlerMapping;

import java.io.File;

/**
 * 全栈预览控制器（A8）
 * 统一承载预览页的静态托管 + API 反代：
 *  - 静态资源：从用户项目 frontend/dist 托管（浏览器加载页面）
 *  - /api/**：反代到沙箱容器内的后端（隔离库 + Redis db）
 * <p>
 * 由于主服务 context-path=/api，本控制器映射 /preview/{appId}/**，
 * 实际访问 URL 为 {deployHost}/api/preview/{appId}/...
 * <p>
 * 隔离只存在于预览编排层（appId 路由到容器、容器连 app_{appId} 库）。
 * 用户下载的源码含完全相同的前端（相对 api 路径），部署后不经此控制器，直接连自己的后端。
 * 因此源码零隔离痕迹。
 */
@Slf4j
@RestController
@RequestMapping("/preview/{appId}")
public class PreviewProxyController {

    @Resource
    private SandboxClient sandboxClient;

    @Resource
    private SandboxProperties sandboxProperties;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 处理预览页请求：静态资源或 API 反代
     */
    @RequestMapping(value = "/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS, RequestMethod.HEAD})
    public ResponseEntity<?> handle(@PathVariable Long appId,
                                    @RequestBody(required = false) String body,
                                    @RequestHeader HttpHeaders headers,
                                    HttpServletRequest request) {
        if (!sandboxProperties.isEnabled()) {
            return ResponseEntity.status(503).body("{\"error\":\"沙箱未启用，预览不可用\"}");
        }

        String restPath = resolveRestPath(request);
        if (restPath == null) {
            return ResponseEntity.badRequest().build();
        }

        // API 请求 → 反代到容器后端
        if (restPath.startsWith("/api") || restPath.equals("/api")) {
            return proxyToBackend(appId, body, headers, request, restPath);
        }

        // 静态资源 → 从 frontend/dist 托管
        return serveStatic(appId, restPath);
    }

    /**
     * 从请求中解析剥离 /api/preview/{appId} 前缀后的路径
     */
    private String resolveRestPath(HttpServletRequest request) {
        String resourcePath = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        if (resourcePath == null || resourcePath.isEmpty()) {
            resourcePath = "/";
        }
        return resourcePath;
    }

    /**
     * 反代到容器后端
     */
    private ResponseEntity<?> proxyToBackend(Long appId, String body, HttpHeaders headers,
                                             HttpServletRequest request, String restPath) {
        int backendPort = sandboxClient.getBackendPort(appId);
        String backendUrl = "http://" + sandboxProperties.getHost() + ":" + backendPort + restPath;

        HttpHeaders forwardHeaders = new HttpHeaders();
        forwardHeaders.set(HttpHeaders.HOST, sandboxProperties.getHost() + ":" + backendPort);
        if (body != null) {
            forwardHeaders.setContentType(MediaType.APPLICATION_JSON);
        }

        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    backendUrl,
                    HttpMethod.valueOf(request.getMethod()),
                    new HttpEntity<>(body != null ? body.getBytes() : null, forwardHeaders),
                    byte[].class
            );
            HttpHeaders respHeaders = new HttpHeaders();
            if (response.getHeaders().getContentType() != null) {
                respHeaders.setContentType(response.getHeaders().getContentType());
            }
            return ResponseEntity.status(response.getStatusCode().value())
                    .headers(respHeaders)
                    .body(response.getBody() == null ? new byte[0] : response.getBody());
        } catch (Exception e) {
            log.error("预览反代失败, appId: {}, uri: {}, error: {}", appId, restPath, e.getMessage());
            return ResponseEntity.status(502).body("{\"error\":\"后端服务不可用，请稍后重试\"}".getBytes());
        }
    }

    /**
     * 从用户项目 frontend/dist 托管静态资源
     */
    private ResponseEntity<?> serveStatic(Long appId, String restPath) {
        // 目录访问或无路径 → 返回 index.html（hash 路由，确保 SPA 可刷新）
        String resourcePath = restPath;
        if (resourcePath.equals("/") || resourcePath.isEmpty()) {
            resourcePath = "/index.html";
        }

        // 首页请求（无扩展名）→ 回退 index.html，保证 hash 路由刷新可用
        if (!resourcePath.contains(".")) {
            resourcePath = "/index.html";
        }

        String projectRoot = AppConstant.CODE_OUTPUT_ROOT_DIR + "/fullstack_" + appId;
        String baseDir = projectRoot + "/frontend/dist";
        File file = new File(baseDir + resourcePath);

        // 若资源不存在，回退到 index.html（SPA 兜底）
        if (!file.exists() || !file.isFile()) {
            file = new File(baseDir + "/index.html");
            if (!file.exists()) {
                return ResponseEntity.status(404).body("{\"error\":\"前端未构建，请先构建项目\"}".getBytes());
            }
        }

        org.springframework.core.io.Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header("Content-Type", getContentType(file.getPath()))
                .body(resource);
    }

    private String getContentType(String filePath) {
        if (filePath.endsWith(".html")) return "text/html; charset=UTF-8";
        if (filePath.endsWith(".css")) return "text/css; charset=UTF-8";
        if (filePath.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (filePath.endsWith(".json")) return "application/json; charset=UTF-8";
        if (filePath.endsWith(".png")) return "image/png";
        if (filePath.endsWith(".jpg") || filePath.endsWith(".jpeg")) return "image/jpeg";
        if (filePath.endsWith(".svg")) return "image/svg+xml";
        if (filePath.endsWith(".ico")) return "image/x-icon";
        if (filePath.endsWith(".woff")) return "font/woff";
        if (filePath.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }
}
