package com.yupi.yuaicodemother.model.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 代码生成任务实体类
 *
 * @author yupi
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("gen_task")
public class GenTask implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 任务 ID
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 应用 ID
     */
    @Column("appId")
    private Long appId;

    /**
     * 用户 ID
     */
    @Column("userId")
    private Long userId;

    /**
     * 用户消息/提示词
     */
    private String message;

    /**
     * 代码生成类型
     */
    @Column("codeGenType")
    private String codeGenType;

    /**
     * 任务状态：pending/running/completed/failed/cancelled
     */
    private String status;

    /**
     * 队列中的位置（排队序号）
     */
    @Column("queuePosition")
    private Integer queuePosition;

    /**
     * 已生成的内容（用于断线重连时恢复）
     */
    @Column("generatedContent")
    private String generatedContent;

    /**
     * 错误信息
     */
    @Column("errorMessage")
    private String errorMessage;

    /**
     * 任务开始执行时间
     */
    @Column("startTime")
    private LocalDateTime startTime;

    /**
     * 任务完成时间
     */
    @Column("endTime")
    private LocalDateTime endTime;

    /**
     * 创建时间
     */
    @Column("createTime")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Column("updateTime")
    private LocalDateTime updateTime;

    /**
     * 是否删除
     */
    @Column("isDelete")
    private Integer isDelete;
}
