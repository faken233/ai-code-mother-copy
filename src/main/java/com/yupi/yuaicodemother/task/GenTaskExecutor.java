package com.yupi.yuaicodemother.task;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.yupi.yuaicodemother.ai.model.message.StreamMessageTypeEnum;
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
import com.yupi.yuaicodemother.sandbox.SandboxContainerManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Value("${gen-task.max-retries:3}")
    private int maxRetries;

    @Value("${gen-task.retry-base-delay-ms:3000}")
    private long retryBaseDelayMs;

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

    @Resource
    private SandboxContainerManager sandboxContainerManager;

    /**
     * 调度线程池（用于轮询任务队列和清理超时任务）
     */
    private ScheduledExecutorService scheduledExecutor;

    /**
     * 任务流
     */
    private Sinks.Many<GenTask> taskSink;

    /**
     * 任务流订阅
     */
    private Disposable taskSubscription;

    /**
     * 是否正在运行
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        scheduledExecutor = Executors.newScheduledThreadPool(2);

        // 初始化任务流，并通过 flatMap 控制并发
        taskSink = Sinks.many().unicast().onBackpressureBuffer();
        taskSubscription = taskSink.asFlux()
            .flatMap(task -> executeTask(task)
                .subscribeOn(Schedulers.boundedElastic()), maxConcurrentTasks)
            .subscribe();

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
        if (taskSubscription != null) {
            taskSubscription.dispose();
        }
        if (taskSink != null) {
            taskSink.tryEmitComplete();
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
            // 获取下一个待执行的任务
            GenTask task = genTaskService.getNextPendingTask();
            if (task == null) {
                return;
            }

            // 推送到任务流，由 WebFlux 控制并发
            Sinks.EmitResult emitResult = taskSink.tryEmitNext(task);
            if (emitResult.isFailure()) {
                log.warn("任务入队失败: taskId={}, reason={}", task.getId(), emitResult);
            }

        } catch (Exception e) {
            log.error("轮询任务队列失败: {}", e.getMessage());
        }
    }

    /**
     * 执行单个任务
     */
    private Mono<Void> executeTask(GenTask task) {
        Long taskId = task.getId();
        Long appId = task.getAppId();
        Long userId = task.getUserId();

        return Mono.defer(() -> {
            try {
                log.info("开始执行任务: taskId={}, appId={}, userId={}", taskId, appId, userId);

                // 获取应用和用户信息（先于沙箱搭建，需要 codeGenType 判断是否全栈）
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

                // 全栈项目：先显式搭建沙箱（用户可见阶段：沙箱搭建中）
                if (codeGenTypeEnum == CodeGenTypeEnum.FULLSTACK) {
                    genTaskService.markTaskSandboxBuilding(taskId);
                    sseConnectionManager.sendTaskStatus(taskId, GenTaskStatusEnum.SANDBOX_BUILDING.getValue(), 0);
                    log.info("任务 {} 进入沙箱搭建阶段，开始搭建沙箱", taskId);
                    sandboxContainerManager.ensureContainer(appId);
                    log.info("任务 {} 沙箱搭建完成，开始代码生成", taskId);
                }

                // 更新任务状态为运行中（代码生成中）
                genTaskService.markTaskRunning(taskId);
                sseConnectionManager.sendTaskStatus(taskId, GenTaskStatusEnum.RUNNING.getValue(), 0);

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
                Flux<String> processedStream = streamHandlerExecutor.doExecute(codeStream, chatHistoryService, appId, user, codeGenTypeEnum)
                    // 工具调用 / 文件写入等阻塞操作统一切到弹性线程池
                    .publishOn(Schedulers.boundedElastic());

                // 对上游瞬时错误（过载 / 限流 / 临时不可用）做指数退避重试
                AtomicInteger retryCount = new AtomicInteger(0);
                processedStream = processedStream.retryWhen(Retry.backoff(maxRetries, Duration.ofMillis(retryBaseDelayMs))
                    .filter(throwable -> {
                        boolean retryable = isRetryableError(throwable);
                        if (retryable) {
                            log.warn("检测到可重试错误，准备重试，taskId: {}, error: {}", taskId, throwable.getMessage());
                        }
                        return retryable;
                    })
                    .doBeforeRetry(signal -> {
                        int attempt = retryCount.incrementAndGet();
                        log.info("正在重试任务，taskId: {}, 第 {} 次重试", taskId, attempt);
                    }));

                // 收集并发送流式响应
                StringBuilder contentBuilder = new StringBuilder();

                return processedStream.doOnNext(chunk -> {
                    // 根据消息类型分发到不同 SSE 事件（工具块会额外持久化为 tool_call 历史）
                    sendStreamChunk(taskId, chunk, contentBuilder, appId, userId);
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
                }).then().onErrorResume(error -> Mono.empty());

            } catch (Exception e) {
                handleTaskError(taskId, e);
                MonitorContextHolder.clearContext();
                return Mono.empty();
            }
        });
    }

    /**
     * 根据消息类型分发流式数据到不同的 SSE 事件
     *
     * <p>正文增量走默认 data 事件并持久化；思考块、工具请求、工具执行结果
     * 分别走 thinking / tool_request / tool_executed 命名事件，前端据此渲染可折叠块。
     *
     * @param taskId         任务ID
     * @param chunk          结构化 JSON 消息块
     * @param contentBuilder 正文累积器（用于持久化）
     */
    private void sendStreamChunk(Long taskId, String chunk, StringBuilder contentBuilder, Long appId, Long userId) {
        try {
            JSONObject json = JSONUtil.parseObj(chunk);
            String type = json.getStr("type");
            StreamMessageTypeEnum typeEnum = StreamMessageTypeEnum.getEnumByValue(type);
            if (typeEnum == null) {
                // 兼容非结构化消息，按正文处理
                sseConnectionManager.sendChunk(taskId, chunk);
                appendContent(contentBuilder, taskId, chunk);
                return;
            }

            switch (typeEnum) {
                case AI_RESPONSE -> {
                    String data = json.getStr("data");
                    if (data == null) {
                        data = "";
                    }
                    sseConnectionManager.sendChunk(taskId, data);
                    appendContent(contentBuilder, taskId, data);
                }
                case TOOL_EXECUTED -> {
                    // 工具执行完毕：只发一个 tool_executed 事件，前端用一个块展示（含完成标记）
                    sseConnectionManager.sendEvent(taskId, "tool_executed", chunk);
                    // 额外持久化工具块（摘要+结果），供历史还原
                    persistToolCall(chunk, appId, userId);
                }
                case THINKING, TOOL_REQUEST -> {
                    // 思考块和独立的工具请求块已不再传输前端，仅记录日志
                    log.debug("忽略不传输的消息类型: {}", type);
                }
                default -> {
                    log.warn("未知消息类型: {}", type);
                }
            }
        } catch (Exception e) {
            log.warn("解析流式消息失败，按正文处理。taskId: {}, error: {}", taskId, e.getMessage());
            sseConnectionManager.sendChunk(taskId, chunk);
            appendContent(contentBuilder, taskId, chunk);
        }
    }

    /**
     * 持久化工具调用块（messageType=tool_call）。
     * 只存摘要和结果，不存完整参数（避免大段文件内容占用存储）。
     */
    private void persistToolCall(String chunk, Long appId, Long userId) {
        try {
            JSONObject json = JSONUtil.parseObj(chunk);
            JSONObject persisted = new JSONObject();
            persisted.set("name", json.getStr("name"));
            persisted.set("displayName", json.getStr("displayName"));
            persisted.set("summary", json.getStr("summary"));
            persisted.set("result", json.getStr("result"));
            persisted.set("status", "completed");
            chatHistoryService.addChatMessage(appId, persisted.toString(), ChatHistoryMessageTypeEnum.TOOL_CALL.getValue(), userId);
        } catch (Exception e) {
            log.warn("持久化工具调用块失败, appId: {}, error: {}", appId, e.getMessage());
        }
    }

    /**
     * 累积正文并定期持久化到数据库
     */
    private void appendContent(StringBuilder contentBuilder, Long taskId, String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        contentBuilder.append(content);
        // 每 100 个字符保存一次，降低写库频率
        if (contentBuilder.length() >= 100) {
            genTaskService.appendGeneratedContent(taskId, contentBuilder.toString());
            contentBuilder.setLength(0);
        }
    }

    /**
     * 判断错误是否为可重试的瞬时错误（上游过载 / 限流 / 临时不可用）
     */
    private boolean isRetryableError(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        String message = throwable.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("overloaded")
                || lower.contains("temporarily unavailable")
                || lower.contains("rate limit")
                || lower.contains("rate_limit")
                || lower.contains("too many requests")
                || lower.contains("service unavailable")
                || lower.contains("503")
                || lower.contains("429");
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
        scheduledExecutor.execute(this::pollAndExecuteTasks);
    }
}
