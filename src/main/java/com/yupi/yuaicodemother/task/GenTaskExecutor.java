package com.yupi.yuaicodemother.task;

import com.yupi.yuaicodemother.core.AiCodeGeneratorFacade;
import com.yupi.yuaicodemother.core.handler.StreamHandlerExecutor;
import com.yupi.yuaicodemother.model.entity.App;
import com.yupi.yuaicodemother.model.entity.GenTask;
import com.yupi.yuaicodemother.model.entity.User;
import com.yupi.yuaicodemother.model.enums.ChatHistoryMessageTypeEnum;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import com.yupi.yuaicodemother.model.enums.GenTaskStatusEnum;
import com.yupi.yuaicodemother.monitor.MonitorContext;
import com.yupi.yuaicodemother.monitor.MonitorContextHolder;
import com.yupi.yuaicodemother.service.AppService;
import com.yupi.yuaicodemother.service.ChatHistoryService;
import com.yupi.yuaicodemother.service.GenTaskService;
import com.yupi.yuaicodemother.service.UserService;
import com.yupi.yuaicodemother.sse.SseConnectionManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 任务队列执行器
 * 负责从队列中取出任务并执行
 *
 * @author yupi
 */
@Component
@Slf4j
public class GenTaskExecutor {

    @Value("${gen-task.max-concurrent:3}")
    private int maxConcurrentTasks;

    @Value("${gen-task.poll-interval:2000}")
    private long pollInterval;

    @Value("${gen-task.timeout-minutes:10}")
    private int timeoutMinutes;

    @Resource
    private GenTaskService genTaskService;

    @Resource
    private AppService appService;

    @Resource
    private UserService userService;

    @Resource
    private ChatHistoryService chatHistoryService;

    @Resource
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;

    @Resource
    private SseConnectionManager sseConnectionManager;

    @Resource
    private StreamHandlerExecutor streamHandlerExecutor;

    /**
     * 任务执行线程池（使用虚拟线程）
     */
    private ExecutorService taskExecutor;

    /**
     * 调度线程池（用于轮询任务队列和清理超时任务）
     */
    private ScheduledExecutorService scheduledExecutor;

    /**
     * 是否正在运行
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        // 使用虚拟线程执行器
        taskExecutor = Executors.newVirtualThreadPerTaskExecutor();
        scheduledExecutor = Executors.newScheduledThreadPool(2);

        // 启动任务轮询
        running.set(true);
        scheduledExecutor.scheduleWithFixedDelay(this::pollAndExecuteTasks, 1000, pollInterval, TimeUnit.MILLISECONDS);

        // 启动超时任务清理（每分钟执行一次）
        scheduledExecutor.scheduleWithFixedDelay(this::cleanupTimeoutTasks, 1, 1, TimeUnit.MINUTES);

        // 启动队列位置更新（每5秒更新一次）
        scheduledExecutor.scheduleWithFixedDelay(this::updateQueuePositions, 5, 5, TimeUnit.SECONDS);

        log.info("GenTaskExecutor 初始化完成，最大并发任务数: {}", maxConcurrentTasks);
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        if (scheduledExecutor != null) {
            scheduledExecutor.shutdown();
        }
        if (taskExecutor != null) {
            taskExecutor.shutdown();
        }
        log.info("GenTaskExecutor 已关闭");
    }

    /**
     * 轮询并执行任务
     */
    private void pollAndExecuteTasks() {
        if (!running.get()) {
            return;
        }

        try {
            // 检查当前运行任务数
            int runningCount = genTaskService.countRunningTasks();
            if (runningCount >= maxConcurrentTasks) {
                return;
            }

            // 获取下一个待执行的任务
            GenTask task = genTaskService.getNextPendingTask();
            if (task == null) {
                return;
            }

            // 提交任务执行
            taskExecutor.submit(() -> executeTask(task));

        } catch (Exception e) {
            log.error("轮询任务队列失败: {}", e.getMessage());
        }
    }

    /**
     * 执行单个任务
     */
    private void executeTask(GenTask task) {
        Long taskId = task.getId();
        Long appId = task.getAppId();
        Long userId = task.getUserId();

        try {
            log.info("开始执行任务: taskId={}, appId={}, userId={}", taskId, appId, userId);

            // 更新任务状态为运行中
            genTaskService.markTaskRunning(taskId);

            // 通知 SSE 连接任务开始执行
            sseConnectionManager.sendTaskStatus(taskId, GenTaskStatusEnum.RUNNING.getValue(), 0);

            // 获取应用和用户信息
            App app = appService.getById(appId);
            User user = userService.getById(userId);

            if (app == null || user == null) {
                throw new RuntimeException("应用或用户不存在");
            }

            // 获取代码生成类型
            CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(task.getCodeGenType());
            if (codeGenTypeEnum == null) {
                throw new RuntimeException("无效的代码生成类型");
            }

            // 保存用户消息到对话历史
            chatHistoryService.addChatMessage(appId, task.getMessage(), ChatHistoryMessageTypeEnum.USER.getValue(), userId);

            // 设置监控上下文
            MonitorContextHolder.setContext(
                    MonitorContext.builder()
                            .userId(userId.toString())
                            .appId(appId.toString())
                            .build()
            );

            // 调用 AI 生成代码（流式）
            Flux<String> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream(task.getMessage(), codeGenTypeEnum, appId);

            // 使用 StreamHandlerExecutor 处理流（处理工具调用，保存聊天历史）
            Flux<String> processedStream = streamHandlerExecutor.doExecute(codeStream, chatHistoryService, appId, user, codeGenTypeEnum);

            // 收集并发送流式响应
            StringBuilder contentBuilder = new StringBuilder();

            processedStream.doOnNext(chunk -> {
                // 发送数据块到 SSE 连接
                sseConnectionManager.sendChunk(taskId, chunk);

                // 累积内容
                contentBuilder.append(chunk);

                // 定期保存内容到数据库（每100个字符保存一次）
                if (contentBuilder.length() % 100 == 0) {
                    genTaskService.appendGeneratedContent(taskId, contentBuilder.toString());
                    contentBuilder.setLength(0);
                }
            }).doOnComplete(() -> {
                // 保存剩余内容
                if (!contentBuilder.isEmpty()) {
                    genTaskService.appendGeneratedContent(taskId, contentBuilder.toString());
                }

                // 更新任务状态为已完成
                genTaskService.markTaskCompleted(taskId);

                // 发送完成事件
                sseConnectionManager.sendDoneEvent(taskId);

                log.info("任务执行完成: taskId={}", taskId);
            }).doOnError(error -> {
                handleTaskError(taskId, error);
            }).doFinally(signalType -> {
                MonitorContextHolder.clearContext();
            }).subscribe();

        } catch (Exception e) {
            handleTaskError(taskId, e);
        }
    }

    /**
     * 处理任务执行错误
     */
    private void handleTaskError(Long taskId, Throwable error) {
        String errorMessage = error.getMessage();
        log.error("任务执行失败: taskId={}, error={}", taskId, errorMessage);

        // 更新任务状态为失败
        genTaskService.markTaskFailed(taskId, errorMessage);

        // 发送错误事件
        sseConnectionManager.sendErrorEvent(taskId, errorMessage);
    }

    /**
     * 清理超时任务
     */
    private void cleanupTimeoutTasks() {
        try {
            int cleaned = genTaskService.cleanupTimeoutTasks(timeoutMinutes);
            if (cleaned > 0) {
                log.info("清理了 {} 个超时任务", cleaned);
            }
        } catch (Exception e) {
            log.error("清理超时任务失败: {}", e.getMessage());
        }
    }

    /**
     * 更新所有排队任务的队列位置
     */
    private void updateQueuePositions() {
        try {
            // 获取所有活跃的 SSE 连接对应的任务
            // 为每个排队中的任务发送位置更新
            for (GenTask task : genTaskService.list()) {
                if (GenTaskStatusEnum.PENDING.getValue().equals(task.getStatus())) {
                    Long taskId = task.getId();
                    if (sseConnectionManager.hasConnection(taskId)) {
                        int position = genTaskService.getQueuePosition(taskId);
                        sseConnectionManager.sendQueueUpdate(taskId, position);
                    }
                }
            }
        } catch (Exception e) {
            log.error("更新队列位置失败: {}", e.getMessage());
        }
    }

    /**
     * 手动触发任务执行（用于测试或紧急情况）
     */
    public void triggerExecution() {
        taskExecutor.submit(this::pollAndExecuteTasks);
    }
}
