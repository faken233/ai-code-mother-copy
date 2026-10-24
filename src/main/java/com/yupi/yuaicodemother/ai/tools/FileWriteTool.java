package com.yupi.yuaicodemother.ai.tools;

import cn.hutool.core.io.FileUtil;
import com.yupi.yuaicodemother.template.AppTemplateService;
import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 文件写入工具
 * 支持 AI 通过工具调用的方式写入文件
 */
@Slf4j
@Component
public class FileWriteTool extends BaseTool {

    @Resource
    private com.yupi.yuaicodemother.sandbox.SandboxClient sandboxClient;

    @Tool("写入文件到指定路径")
    public String writeFile(
            @P("文件的相对路径")
            String relativeFilePath,
            @P("要写入文件的内容")
            String content,
            @ToolMemoryId Long appId
    ) {
        if (AppTemplateService.isPlatformOwned(relativeFilePath)) {
            return "❌ 该文件由平台维护，禁止覆盖：" + relativeFilePath
                    + "。你只需要写 schema.json、client/src/App.jsx（页面）以及可选的 routes/custom.js。";
        }
        try {
            // 通过沙箱客户端写文件（沙箱开启走 HTTP，否则本地直写）
            sandboxClient.writeFile(relativeFilePath, content, appId);
            log.info("成功写入文件: {}", relativeFilePath);
            // 注意要返回相对路径，不能让 AI 把文件绝对路径返回给用户
            return "文件写入成功: " + relativeFilePath;
        } catch (Exception e) {
            String errorMessage = "文件写入失败: " + relativeFilePath + ", 错误: " + e.getMessage();
            log.error(errorMessage, e);
            return errorMessage;
        }
    }

    @Override
    public String getToolName() {
        return "writeFile";
    }

    @Override
    public String getDisplayName() {
        return "写入文件";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String relativeFilePath = arguments.getStr("relativeFilePath");
        String suffix = FileUtil.getSuffix(relativeFilePath);
        String content = arguments.getStr("content");
        return String.format("""
                        [工具调用] %s %s
                        ```%s
                        %s
                        ```
                        """, getDisplayName(), relativeFilePath, suffix, content);
    }
}
