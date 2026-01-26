package com.yupi.yuaicodemother.service;

import com.mybatisflex.core.service.IService;
import com.yupi.yuaicodemother.model.entity.GenTask;
import com.yupi.yuaicodemother.model.entity.User;

import java.util.List;

/**
 * AI 代码生成任务服务层接口
 *
 * @author yupi
 */
public interface GenTaskService extends IService<GenTask> {

    /**
     * 创建新的生成任务
     * 如果 appId 为空，会自动创建新应用
     *
     * @param appId     应用ID（可选，为空时自动创建应用）
     * @param message   用户消息
     * @param loginUser 登录用户
     * @return 任务对象
     */
    GenTask createTask(Long appId, String message, User loginUser);

    /**
     * 获取任务详情
     *
     * @param taskId    任务ID
     * @param loginUser 登录用户
     * @return 任务对象
     */
    GenTask getTask(Long taskId, User loginUser);

    /**
     * 获取任务的队列位置
     *
     * @param taskId 任务ID
     * @return 队列位置（0表示正在执行）
     */
    int getQueuePosition(Long taskId);

    /**
     * 更新任务状态为运行中
     *
     * @param taskId 任务ID
     */
    void markTaskRunning(Long taskId);

    /**
     * 更新任务状态为已完成
     *
     * @param taskId 任务ID
     */
    void markTaskCompleted(Long taskId);

    /**
     * 更新任务状态为失败
     *
     * @param taskId       任务ID
     * @param errorMessage 错误信息
     */
    void markTaskFailed(Long taskId, String errorMessage);

    /**
     * 取消任务
     *
     * @param taskId    任务ID
     * @param loginUser 登录用户
     * @return 是否取消成功
     */
    boolean cancelTask(Long taskId, User loginUser);

    /**
     * 追加生成的内容（用于断线重连恢复）
     *
     * @param taskId  任务ID
     * @param content 追加的内容
     */
    void appendGeneratedContent(Long taskId, String content);

    /**
     * 获取任务已生成的内容
     *
     * @param taskId 任务ID
     * @return 已生成的内容
     */
    String getGeneratedContent(Long taskId);

    /**
     * 获取下一个待执行的任务
     *
     * @return 下一个待执行的任务，如果没有则返回 null
     */
    GenTask getNextPendingTask();

    /**
     * 获取当前正在执行的任务数量
     *
     * @return 正在执行的任务数量
     */
    int countRunningTasks();

    /**
     * 获取用户指定应用的活跃任务
     *
     * @param userId 用户ID
     * @param appId  应用ID
     * @return 活跃任务
     */
    GenTask getActiveTaskByUserAndApp(Long userId, Long appId);

    /**
     * 获取用户的所有活跃任务
     *
     * @param userId 用户ID
     * @return 活跃任务列表
     */
    List<GenTask> getActiveTasksByUser(Long userId);

    /**
     * 清理超时任务
     *
     * @param timeoutMinutes 超时分钟数
     * @return 清理的任务数
     */
    int cleanupTimeoutTasks(int timeoutMinutes);
}
