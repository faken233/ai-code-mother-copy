package com.yupi.yuaicodemother.ai.model.message;

import dev.langchain4j.model.chat.response.PartialThinking;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 思考过程消息
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class ThinkingMessage extends StreamMessage {

    private String data;

    public ThinkingMessage(PartialThinking partialThinking) {
        super(StreamMessageTypeEnum.THINKING.getValue());
        this.data = partialThinking.text();
    }

    public ThinkingMessage(String text) {
        super(StreamMessageTypeEnum.THINKING.getValue());
        this.data = text;
    }
}
