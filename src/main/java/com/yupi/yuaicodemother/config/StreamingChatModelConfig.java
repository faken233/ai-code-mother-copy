package com.yupi.yuaicodemother.config;

import com.yupi.yuaicodemother.monitor.AiModelMonitorListener;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import java.time.Duration;
import java.util.List;

/**
 * 流式对话模型配置
 */
@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.streaming-chat-model")
@Data
public class StreamingChatModelConfig {

    @Resource
    private AiModelMonitorListener aiModelMonitorListener;

    private String baseUrl;

    private String apiKey;

    private String modelName;

    private Integer maxTokens;

    private Double temperature;

    private Double topP;

    private List<String> stop;

    private Double frequencyPenalty;

    private Double presencePenalty;

    private Duration timeout;

    private boolean logRequests;

    private boolean logResponses;

    /**
     * 是否返回思考/推理内容（默认开启，用于前端折叠展示）
     */
    private Boolean returnThinking = true;

    /**
     * 流式模型
     */
    @Bean
    @Scope("prototype")
    public StreamingChatModel streamingChatModelPrototype() {
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .topP(topP)
                .stop(stop)
                .frequencyPenalty(frequencyPenalty)
                .presencePenalty(presencePenalty)
                .timeout(timeout)
                .returnThinking(returnThinking)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .listeners(List.of(aiModelMonitorListener))
                .build();
    }
}
