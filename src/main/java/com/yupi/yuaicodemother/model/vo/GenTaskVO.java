package com.yupi.yuaicodemother.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 生成任务 VO
 *
 * @author yupi
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenTaskVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 任务 ID
     */
    private Long id;

    /**
     * 应用 ID
     */
    private Long appId;

    /**
     * 任务状态
     */
    private String status;

    /**
     * 队列位置（0表示正在执行，-1表示已完成/失败）
     */
    private Integer queuePosition;

    /**
     * 代码生成类型
     */
    private String codeGenType;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 开始执行时间
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;
}
