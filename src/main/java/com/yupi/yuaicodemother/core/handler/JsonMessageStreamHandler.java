package com.yupi.yuaicodemother.core.handler;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.yupi.yuaicodemother.ai.model.message.*;
import com.yupi.yuaicodemother.ai.tools.BaseTool;
import com.yupi.yuaicodemother.ai.tools.ToolManager;
import com.yupi.yuaicodemother.model.entity.User;
import com.yupi.yuaicodemother.model.enums.ChatHistoryMessageTypeEnum;
import com.yupi.yuaicodemother.service.ChatHistoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.Set;

/**
 * JSON 消息流处理器
 * 处理 VUE_PROJECT 类型的复杂流式响应，包含思考过程和工具调用信息
 *
 * <p>输出为带 type 字段的 JSON 消息，供上层按消息类型分发为不同的 SSE 事件：
 * <ul>
 *   <li>thinking —— 思考过程增量（仅前端展示，不持久化）</li>
 *   <li>tool_request —— 工具调用请求（结构化）</li>
 *   <li>tool_executed —— 工具执行结果（结构化）</li>
 *   <li>ai_response —— 正文增量</li>
 * </ul>
 */
@Slf4j
@Component
public class JsonMessageStreamHandler {

    @Resource
    private ToolManager toolManager;

    /**
     * 处理 TokenStream（VUE_PROJECT）
     * 解析 JSON 消息并重组为带类型的结构化消息，同时收集对话历史
     *
     * @param originFlux         原始流
     * @param chatHistoryService 聊天历史服务
     * @param appId              应用ID
     * @param loginUser          登录用户
     * @return 处理后的结构化消息流
     */
    public Flux<String> handle(Flux<String> originFlux,
                               ChatHistoryService chatHistoryService,
                               long appId, User loginUser) {
        // 收集数据用于生成后端记忆格式
        StringBuilder chatHistoryStringBuilder = new StringBuilder();
        // 用于跟踪已经见过的工具ID，判断是否是第一次调用
        Set<String> seenToolIds = new HashSet<>();
        return originFlux
                .map(chunk -> handleJsonMessageChunk(chunk, chatHistoryStringBuilder, seenToolIds))
                .filter(StrUtil::isNotEmpty)
                .doOnComplete(() -> {
                    // 流式响应完成后，添加 AI 消息到对话历史
                    String aiResponse = chatHistoryStringBuilder.toString();
                    chatHistoryService.addChatMessage(appId, aiResponse, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                })
                .doOnError(error -> {
                    // 如果AI回复失败，也要记录错误消息
                    String errorMessage = "AI回复失败: " + error.getMessage();
                    chatHistoryService.addChatMessage(appId, errorMessage, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                });
    }

    /**
     * 解析并重组单个 TokenStream 数据块
     */
    private String handleJsonMessageChunk(String chunk, StringBuilder chatHistoryStringBuilder, Set<String> seenToolIds) {
        StreamMessage streamMessage = JSONUtil.toBean(chunk, StreamMessage.class);
        StreamMessageTypeEnum typeEnum = StreamMessageTypeEnum.getEnumByValue(streamMessage.getType());
        switch (typeEnum) {
            case AI_RESPONSE -> {
                AiResponseMessage aiMessage = JSONUtil.toBean(chunk, AiResponseMessage.class);
                String data = aiMessage.getData();
                chatHistoryStringBuilder.append(data);
                return JSONUtil.toJsonStr(aiMessage);
            }
            case THINKING -> {
                // 思考过程仅用于前端展示，不写入对话历史
                return chunk;
            }
            case TOOL_REQUEST -> {
                ToolRequestMessage toolRequestMessage = JSONUtil.toBean(chunk, ToolRequestMessage.class);
                String toolId = toolRequestMessage.getId();
                String toolName = toolRequestMessage.getName();
                // 同一个工具 ID 只发送一次工具请求事件
                if (toolId != null && !seenToolIds.contains(toolId)) {
                    seenToolIds.add(toolId);
                    return buildToolRequestEvent(toolRequestMessage);
                }
                return "";
            }
            case TOOL_EXECUTED -> {
                ToolExecutedMessage toolExecutedMessage = JSONUtil.toBean(chunk, ToolExecutedMessage.class);
                BaseTool tool = toolManager.getTool(toolExecutedMessage.getName());
                if (tool == null) {
                    log.error("未找到工具: {}", toolExecutedMessage.getName());
                    return "";
                }
                // 持久化工具执行结果（格式化文本，供下次对话作为上下文）
                JSONObject arguments = JSONUtil.parseObj(toolExecutedMessage.getArguments());
                String historyOutput = String.format("\n\n%s\n\n", tool.generateToolExecutedResult(arguments));
                chatHistoryStringBuilder.append(historyOutput);
                // 发送结构化工具执行事件
                return buildToolExecutedEvent(toolExecutedMessage, tool);
            }
            default -> {
                log.error("不支持的消息类型: {}", typeEnum);
                return "";
            }
        }
    }

    /**
     * 构建工具请求事件 JSON
     * 只发送工具名和关键参数摘要，不把完整参数（如文件内容）发给前端
     */
    private String buildToolRequestEvent(ToolRequestMessage message) {
        BaseTool tool = toolManager.getTool(message.getName());
        JSONObject event = new JSONObject();
        event.set("type", StreamMessageTypeEnum.TOOL_REQUEST.getValue());
        event.set("id", message.getId());
        event.set("name", message.getName());
        event.set("displayName", tool != null ? tool.getDisplayName() : message.getName());
        event.set("summary", extractArgumentSummary(message.getName(), message.getArguments()));
        return event.toString();
    }

    /**
     * 构建工具执行事件 JSON
     */
    private String buildToolExecutedEvent(ToolExecutedMessage message, BaseTool tool) {
        JSONObject event = new JSONObject();
        event.set("type", StreamMessageTypeEnum.TOOL_EXECUTED.getValue());
        event.set("id", message.getId());
        event.set("name", message.getName());
        event.set("displayName", tool.getDisplayName());
        event.set("summary", extractArgumentSummary(message.getName(), message.getArguments()));
        event.set("result", message.getResult());
        return event.toString();
    }

    /**
     * 从工具参数中提取简短摘要（如写文件时只保留文件路径），避免把完整内容发给前端
     */
    private String extractArgumentSummary(String toolName, String arguments) {
        if (StrUtil.isBlank(arguments)) {
            return "";
        }
        try {
            JSONObject jsonArguments = JSONUtil.parseObj(arguments);
            // 优先取文件路径/目标地址类字段
            String[] pathKeys = {"relativeFilePath", "filePath", "path", "fileName", "name", "target", "url", "pagePath"};
            for (String key : pathKeys) {
                String value = jsonArguments.getStr(key);
                if (StrUtil.isNotBlank(value)) {
                    return value;
                }
            }
            // 没有路径字段时，截取参数的前 100 个字符作为摘要
            String raw = arguments;
            return raw.length() > 100 ? raw.substring(0, 100) + "..." : raw;
        } catch (Exception e) {
            String raw = arguments;
            return raw.length() > 100 ? raw.substring(0, 100) + "..." : raw;
        }
    }
}
