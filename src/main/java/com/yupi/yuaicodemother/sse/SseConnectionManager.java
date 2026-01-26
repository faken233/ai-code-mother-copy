package com.yupi.yuaicodemother.sse;

import cn.hutool.json.JSONUtil;
import com.yupi.yuaicodemother.model.entity.GenTask;
import com.yupi.yuaicodemother.model.enums.GenTaskStatusEnum;
import com.yupi.yuaicodemother.service.GenTaskService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 连接管理器
 * 负责管理所有的 SSE 连接，支持断线重连
 *
 * @author yupi
 */
@Component
@Slf4j
public class SseConnectionManager {

    /**
     * 存储所有活跃的 SSE 连接
     * key: taskId
     * value: FluxSink
     */
    private final ConcurrentHashMap<Long, FluxSink<ServerSentEvent<String>>> connections = new ConcurrentHashMap<>();

    /**
     * 存储任务对应的内容缓存（用于断线重连时追加新内容）
     * key: taskId
     * value: 已发送内容的长度（用于计算断点位置）
     */
    private final ConcurrentHashMap<Long, Integer> contentOffsets = new ConcurrentHashMap<>();

    @Resource
    private GenTaskService genTaskService;

    /**
     * 创建 SSE 连接
     *
     * @param taskId           任务ID
     * @param lastEventId      上次事件ID（用于断线重连）
     * @param existingContent  已接收的内容长度（用于断点续传）
     * @return Flux<ServerSentEvent<String>>
     */
    public Flux<ServerSentEvent<String>> createConnection(Long taskId, String lastEventId, Integer existingContent) {
        return Flux.create(sink -> {
            // 注册连接
            registerConnection(taskId, sink);

            // 处理断线重连：发送已生成但客户端未收到的内容
            if (existingContent != null && existingContent > 0) {
                handleReconnect(taskId, existingContent, sink);
            }

            // 设置连接关闭时的清理逻辑
            sink.onDispose(() -> {
                log.info("SSE 连接断开，taskId: {}", taskId);
                unregisterConnection(taskId);
            });

            sink.onCancel(() -> {
                log.info("SSE 连接取消，taskId: {}", taskId);
                unregisterConnection(taskId);
            });
        });
    }

    /**
     * 注册连接
     */
    private void registerConnection(Long taskId, FluxSink<ServerSentEvent<String>> sink) {
        // 如果已有连接，先关闭旧连接
        FluxSink<ServerSentEvent<String>> oldSink = connections.put(taskId, sink);
        if (oldSink != null) {
            log.info("关闭旧的 SSE 连接，taskId: {}", taskId);
            try {
                oldSink.complete();
            } catch (Exception e) {
                log.warn("关闭旧连接时出错: {}", e.getMessage());
            }
        }
        log.info("SSE 连接注册成功，taskId: {}", taskId);
    }

    /**
     * 注销连接
     */
    private void unregisterConnection(Long taskId) {
        connections.remove(taskId);
        // 不清理 contentOffsets，保留断点位置以支持重连
    }

    /**
     * 处理断线重连
     */
    private void handleReconnect(Long taskId, Integer existingContentLength, FluxSink<ServerSentEvent<String>> sink) {
        try {
            // 获取任务的完整已生成内容
            String fullContent = genTaskService.getGeneratedContent(taskId);
            if (fullContent != null && fullContent.length() > existingContentLength) {
                // 发送客户端未收到的内容
                String missedContent = fullContent.substring(existingContentLength);
                log.info("断线重连，补发内容长度: {}, taskId: {}", missedContent.length(), taskId);

                // 发送重连恢复事件
                sink.next(ServerSentEvent.<String>builder()
                        .event("reconnect")
                        .data(JSONUtil.toJsonStr(Map.of("missedContent", missedContent)))
                        .build());

                // 更新偏移量
                contentOffsets.put(taskId, fullContent.length());
            }

            // 检查任务状态
            GenTask task = genTaskService.getById(taskId);
            if (task != null) {
                // 发送当前状态
                sendTaskStatus(taskId, task.getStatus(), genTaskService.getQueuePosition(taskId));

                // 如果任务已完成或失败，发送结束事件
                if (GenTaskStatusEnum.COMPLETED.getValue().equals(task.getStatus())) {
                    sendDoneEvent(taskId);
                } else if (GenTaskStatusEnum.FAILED.getValue().equals(task.getStatus())) {
                    sendErrorEvent(taskId, task.getErrorMessage());
                } else if (GenTaskStatusEnum.CANCELLED.getValue().equals(task.getStatus())) {
                    sendCancelledEvent(taskId);
                }
            }
        } catch (Exception e) {
            log.error("处理断线重连失败，taskId: {}, error: {}", taskId, e.getMessage());
        }
    }

    /**
     * 向指定任务的连接发送数据块
     *
     * @param taskId 任务ID
     * @param chunk  数据块
     */
    public void sendChunk(Long taskId, String chunk) {
        FluxSink<ServerSentEvent<String>> sink = connections.get(taskId);
        if (sink != null) {
            try {
                Map<String, String> wrapper = Map.of("d", chunk);
                String jsonData = JSONUtil.toJsonStr(wrapper);
                sink.next(ServerSentEvent.<String>builder()
                        .data(jsonData)
                        .build());

                // 更新偏移量
                int currentOffset = contentOffsets.getOrDefault(taskId, 0);
                contentOffsets.put(taskId, currentOffset + chunk.length());
            } catch (Exception e) {
                log.error("发送数据块失败，taskId: {}, error: {}", taskId, e.getMessage());
            }
        }
    }

    /**
     * 发送任务状态更新
     *
     * @param taskId        任务ID
     * @param status        状态
     * @param queuePosition 队列位置
     */
    public void sendTaskStatus(Long taskId, String status, int queuePosition) {
        FluxSink<ServerSentEvent<String>> sink = connections.get(taskId);
        if (sink != null) {
            try {
                Map<String, Object> statusData = Map.of(
                        "status", status,
                        "queuePosition", queuePosition
                );
                sink.next(ServerSentEvent.<String>builder()
                        .event("status")
                        .data(JSONUtil.toJsonStr(statusData))
                        .build());
            } catch (Exception e) {
                log.error("发送状态更新失败，taskId: {}, error: {}", taskId, e.getMessage());
            }
        }
    }

    /**
     * 发送完成事件
     *
     * @param taskId 任务ID
     */
    public void sendDoneEvent(Long taskId) {
        FluxSink<ServerSentEvent<String>> sink = connections.get(taskId);
        if (sink != null) {
            try {
                sink.next(ServerSentEvent.<String>builder()
                        .event("done")
                        .data("")
                        .build());
                sink.complete();
            } catch (Exception e) {
                log.error("发送完成事件失败，taskId: {}, error: {}", taskId, e.getMessage());
            } finally {
                cleanupTask(taskId);
            }
        }
    }

    /**
     * 发送错误事件
     *
     * @param taskId       任务ID
     * @param errorMessage 错误信息
     */
    public void sendErrorEvent(Long taskId, String errorMessage) {
        FluxSink<ServerSentEvent<String>> sink = connections.get(taskId);
        if (sink != null) {
            try {
                sink.next(ServerSentEvent.<String>builder()
                        .event("error")
                        .data(JSONUtil.toJsonStr(Map.of("message", errorMessage != null ? errorMessage : "未知错误")))
                        .build());
                sink.complete();
            } catch (Exception e) {
                log.error("发送错误事件失败，taskId: {}, error: {}", taskId, e.getMessage());
            } finally {
                cleanupTask(taskId);
            }
        }
    }

    /**
     * 发送取消事件
     *
     * @param taskId 任务ID
     */
    public void sendCancelledEvent(Long taskId) {
        FluxSink<ServerSentEvent<String>> sink = connections.get(taskId);
        if (sink != null) {
            try {
                sink.next(ServerSentEvent.<String>builder()
                        .event("cancelled")
                        .data("")
                        .build());
                sink.complete();
            } catch (Exception e) {
                log.error("发送取消事件失败，taskId: {}, error: {}", taskId, e.getMessage());
            } finally {
                cleanupTask(taskId);
            }
        }
    }

    /**
     * 发送队列位置更新（心跳）
     *
     * @param taskId        任务ID
     * @param queuePosition 队列位置
     */
    public void sendQueueUpdate(Long taskId, int queuePosition) {
        FluxSink<ServerSentEvent<String>> sink = connections.get(taskId);
        if (sink != null) {
            try {
                sink.next(ServerSentEvent.<String>builder()
                        .event("queue")
                        .data(JSONUtil.toJsonStr(Map.of("position", queuePosition)))
                        .build());
            } catch (Exception e) {
                log.error("发送队列更新失败，taskId: {}, error: {}", taskId, e.getMessage());
            }
        }
    }

    /**
     * 检查连接是否存在
     *
     * @param taskId 任务ID
     * @return 是否存在连接
     */
    public boolean hasConnection(Long taskId) {
        return connections.containsKey(taskId);
    }

    /**
     * 清理任务相关资源
     *
     * @param taskId 任务ID
     */
    private void cleanupTask(Long taskId) {
        connections.remove(taskId);
        contentOffsets.remove(taskId);
    }

    /**
     * 获取当前活跃连接数
     *
     * @return 活跃连接数
     */
    public int getActiveConnectionCount() {
        return connections.size();
    }
}
