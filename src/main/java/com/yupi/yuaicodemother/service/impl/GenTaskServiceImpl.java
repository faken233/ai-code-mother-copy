package com.yupi.yuaicodemother.service.impl;

import cn.hutool.core.util.StrUtil;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.yupi.yuaicodemother.exception.BusinessException;
import com.yupi.yuaicodemother.exception.ErrorCode;
import com.yupi.yuaicodemother.exception.ThrowUtils;
import com.yupi.yuaicodemother.mapper.GenTaskMapper;
import com.yupi.yuaicodemother.model.dto.app.AppAddRequest;
import com.yupi.yuaicodemother.model.entity.App;
import com.yupi.yuaicodemother.model.entity.GenTask;
import com.yupi.yuaicodemother.model.entity.User;
import com.yupi.yuaicodemother.model.enums.GenTaskStatusEnum;
import com.yupi.yuaicodemother.service.AppService;
import com.yupi.yuaicodemother.service.GenTaskService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 代码生成任务服务层实现
 *
 * @author yupi
 */
@Service
@Slf4j
public class GenTaskServiceImpl extends ServiceImpl<GenTaskMapper, GenTask> implements GenTaskService {

    @Resource
    @Lazy
    private AppService appService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GenTask createTask(Long appId, String message, User loginUser) {
        // 参数校验
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "消息不能为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);

        App app;
        
        // 如果 appId 为空或无效，则自动创建新应用
        if (appId == null || appId <= 0) {
            // 创建新应用
            AppAddRequest appAddRequest = new AppAddRequest();
            appAddRequest.setInitPrompt(message);
            appId = appService.createApp(appAddRequest, loginUser);
            app = appService.getById(appId);
            log.info("首次对话，自动创建应用，appId: {}, userId: {}", appId, loginUser.getId());
        } else {
            // 查询已有应用信息
            app = appService.getById(appId);
            ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");

            // 权限校验：仅本人可以操作自己的应用
            if (!app.getUserId().equals(loginUser.getId())) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");
            }
        }

        // 检查用户是否已有该应用的活跃任务
        GenTask existingTask = this.getActiveTaskByUserAndApp(loginUser.getId(), appId);
        if (existingTask != null) {
            // 如果已有活跃任务，直接返回
            log.info("用户 {} 已有应用 {} 的活跃任务 {}", loginUser.getId(), appId, existingTask.getId());
            return existingTask;
        }

        // 创建新任务
        GenTask genTask = GenTask.builder()
                .appId(appId)
                .userId(loginUser.getId())
                .message(message)
                .codeGenType(app.getCodeGenType())
                .status(GenTaskStatusEnum.PENDING.getValue())
                .queuePosition(0)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .isDelete(0)
                .build();

        boolean saved = this.save(genTask);
        ThrowUtils.throwIf(!saved, ErrorCode.OPERATION_ERROR, "创建任务失败");

        log.info("创建生成任务成功，taskId: {}, appId: {}, userId: {}", genTask.getId(), appId, loginUser.getId());
        return genTask;
    }

    @Override
    public GenTask getTask(Long taskId, User loginUser) {
        ThrowUtils.throwIf(taskId == null || taskId <= 0, ErrorCode.PARAMS_ERROR, "任务 ID 错误");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);

        GenTask task = this.getById(taskId);
        ThrowUtils.throwIf(task == null, ErrorCode.NOT_FOUND_ERROR, "任务不存在");

        // 权限校验：仅本人可以查看自己的任务
        if (!task.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限查看该任务");
        }

        return task;
    }

    @Override
    public int getQueuePosition(Long taskId) {
        if (taskId == null || taskId <= 0) {
            return -1;
        }
        GenTask task = this.getById(taskId);
        if (task == null) {
            return -1;
        }
        if (GenTaskStatusEnum.RUNNING.getValue().equals(task.getStatus())) {
            return 0; // 正在执行
        }
        if (!GenTaskStatusEnum.PENDING.getValue().equals(task.getStatus())) {
            return -1; // 非排队状态
        }
        // 计算排在前面的任务数
        return this.mapper.countTasksAhead(taskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markTaskRunning(Long taskId) {
        GenTask updateTask = new GenTask();
        updateTask.setId(taskId);
        updateTask.setStatus(GenTaskStatusEnum.RUNNING.getValue());
        updateTask.setStartTime(LocalDateTime.now());
        updateTask.setUpdateTime(LocalDateTime.now());
        this.updateById(updateTask);
        log.info("任务 {} 开始执行", taskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markTaskCompleted(Long taskId) {
        GenTask updateTask = new GenTask();
        updateTask.setId(taskId);
        updateTask.setStatus(GenTaskStatusEnum.COMPLETED.getValue());
        updateTask.setEndTime(LocalDateTime.now());
        updateTask.setUpdateTime(LocalDateTime.now());
        this.updateById(updateTask);
        log.info("任务 {} 执行完成", taskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markTaskFailed(Long taskId, String errorMessage) {
        GenTask updateTask = new GenTask();
        updateTask.setId(taskId);
        updateTask.setStatus(GenTaskStatusEnum.FAILED.getValue());
        updateTask.setErrorMessage(errorMessage);
        updateTask.setEndTime(LocalDateTime.now());
        updateTask.setUpdateTime(LocalDateTime.now());
        this.updateById(updateTask);
        log.error("任务 {} 执行失败: {}", taskId, errorMessage);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelTask(Long taskId, User loginUser) {
        GenTask task = this.getTask(taskId, loginUser);

        // 只能取消排队中的任务
        if (!GenTaskStatusEnum.PENDING.getValue().equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "只能取消排队中的任务");
        }

        GenTask updateTask = new GenTask();
        updateTask.setId(taskId);
        updateTask.setStatus(GenTaskStatusEnum.CANCELLED.getValue());
        updateTask.setEndTime(LocalDateTime.now());
        updateTask.setUpdateTime(LocalDateTime.now());
        boolean updated = this.updateById(updateTask);

        if (updated) {
            log.info("任务 {} 已取消", taskId);
        }
        return updated;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void appendGeneratedContent(Long taskId, String content) {
        if (taskId == null || StrUtil.isBlank(content)) {
            return;
        }
        GenTask task = this.getById(taskId);
        if (task == null) {
            return;
        }
        String existingContent = task.getGeneratedContent();
        String newContent = (existingContent == null ? "" : existingContent) + content;

        GenTask updateTask = new GenTask();
        updateTask.setId(taskId);
        updateTask.setGeneratedContent(newContent);
        updateTask.setUpdateTime(LocalDateTime.now());
        this.updateById(updateTask);
    }

    @Override
    public String getGeneratedContent(Long taskId) {
        if (taskId == null) {
            return null;
        }
        GenTask task = this.getById(taskId);
        return task != null ? task.getGeneratedContent() : null;
    }

    @Override
    public GenTask getNextPendingTask() {
        return this.mapper.getNextPendingTask();
    }

    @Override
    public int countRunningTasks() {
        return this.mapper.countRunningTasks();
    }

    @Override
    public GenTask getActiveTaskByUserAndApp(Long userId, Long appId) {
        if (userId == null || appId == null) {
            return null;
        }
        return this.mapper.getActiveTaskByUserAndApp(userId, appId);
    }

    @Override
    public List<GenTask> getActiveTasksByUser(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return this.mapper.getActiveTasksByUser(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int cleanupTimeoutTasks(int timeoutMinutes) {
        int count = this.mapper.updateTimeoutTasks(timeoutMinutes);
        if (count > 0) {
            log.warn("清理了 {} 个超时任务", count);
        }
        return count;
    }
}
