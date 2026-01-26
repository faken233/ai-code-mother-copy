package com.yupi.yuaicodemother.model.dto.gentask;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 创建生成任务请求
 *
 * @author yupi
 */
@Data
public class GenTaskCreateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 应用 ID（可选，首次对话时为空，系统会自动创建应用）
     */
    private Long appId;

    /**
     * 用户消息/提示词
     */
    private String message;
}
