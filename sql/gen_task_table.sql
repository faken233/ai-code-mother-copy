-- AI 代码生成任务表
create table if not exists gen_task
(
    id               bigint auto_increment comment '任务ID' primary key,
    appId            bigint                                 not null comment '应用ID',
    userId           bigint                                 not null comment '用户ID',
    message          text                                   not null comment '用户消息/提示词',
    codeGenType      varchar(64)                            not null comment '代码生成类型',
    status           varchar(32) default 'pending'          not null comment '任务状态：pending/running/completed/failed/cancelled',
    queuePosition    int         default 0                  not null comment '队列中的位置',
    generatedContent mediumtext                             null comment '已生成的内容（用于断线重连）',
    errorMessage     varchar(1024)                          null comment '错误信息',
    startTime        datetime                               null comment '任务开始执行时间',
    endTime          datetime                               null comment '任务完成时间',
    createTime       datetime    default CURRENT_TIMESTAMP  not null comment '创建时间',
    updateTime       datetime    default CURRENT_TIMESTAMP  not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete         tinyint     default 0                  not null comment '是否删除',
    INDEX idx_appId (appId),
    INDEX idx_userId (userId),
    INDEX idx_status (status),
    INDEX idx_createTime (createTime),
    INDEX idx_status_createTime (status, createTime)
) comment 'AI代码生成任务' collate = utf8mb4_unicode_ci;
