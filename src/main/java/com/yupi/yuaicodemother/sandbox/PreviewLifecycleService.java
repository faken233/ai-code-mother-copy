package com.yupi.yuaicodemother.sandbox;

import com.yupi.yuaicodemother.mapper.GenTaskMapper;
import com.yupi.yuaicodemother.service.AppRouteService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 预览生命周期：记录"用户还在不在这个预览页"和"最近有没有改代码"，供回收任务判断。
 * <p>
 * 状态是进程内的：主服务重启后所有应用都变成"没有心跳"，回收任务会按"离开"处理
 * （这是符合预期的——重启后那些容器本来也可能已经不可用）。
 * <p>
 * 休眠时**不删路由**，只把 status 改成 STOPPED：这样 `{code}.pv.localhost` 这个地址保持不变，
 * 用户点"恢复"时复用同一个 code，不会换链接。
 */
@Slf4j
@Service
public class PreviewLifecycleService {

    @Resource
    private SandboxContainerManager sandboxContainerManager;

    @Resource
    private AppRouteService appRouteService;

    @Resource
    private GenTaskMapper genTaskMapper;

    @Value("${preview.lifecycle.leave-timeout-seconds:120}")
    private long leaveTimeoutSeconds;

    @Value("${preview.lifecycle.idle-timeout-seconds:900}")
    private long idleTimeoutSeconds;

    @Value("${preview.lifecycle.heartbeat-seconds:30}")
    private int heartbeatSeconds;

    /**
     * 最近一次心跳（用户还在页面上）
     */
    private final Map<Long, Long> lastSeen = new ConcurrentHashMap<>();

    /**
     * 最近一次"有效改动"（生成任务完成 / 预览启动）
     */
    private final Map<Long, Long> lastModified = new ConcurrentHashMap<>();

    /**
     * 用户仍在页面上
     */
    public void touch(Long appId) {
        if (appId != null) {
            lastSeen.put(appId, System.currentTimeMillis());
        }
    }

    /**
     * 有新内容产生（生成完成 / 预览被主动拉起），重置"空闲计时"
     */
    public void markModified(Long appId) {
        if (appId == null) {
            return;
        }
        long now = System.currentTimeMillis();
        lastModified.put(appId, now);
        lastSeen.put(appId, now);
    }

    public void forget(Long appId) {
        lastSeen.remove(appId);
        lastModified.remove(appId);
    }

    public Long lastSeenOf(Long appId) {
        return lastSeen.get(appId);
    }

    public Long lastModifiedOf(Long appId) {
        return lastModified.get(appId);
    }

    /**
     * 让预览进入休眠：停容器（数据仍在库里/磁盘上），路由标记为 STOPPED 但保留域名。
     */
    public void sleep(Long appId, String reason) {
        try {
            sandboxContainerManager.stopContainer(appId);
        } catch (Exception e) {
            log.warn("休眠时停止容器失败: appId={}, err={}", appId, e.getMessage());
        }
        appRouteService.markPreviewSleeping(appId, reason);
        forget(appId);
        log.info("预览已休眠: appId={}, reason={}", appId, reason);
    }

    /**
     * 该应用是否有正在跑的生成任务（跑任务时绝不回收）
     */
    public boolean hasActiveTask(Long appId) {
        return genTaskMapper.countActiveTasksByApp(appId) > 0;
    }

    /**
     * 该应用是否正在执行（沙箱搭建/代码生成）。
     * 与 {@link #hasActiveTask} 的区别：不包含排队中的任务。
     */
    public boolean hasExecutingTask(Long appId) {
        return genTaskMapper.countExecutingTasksByApp(appId) > 0;
    }

    public long getLeaveTimeoutSeconds() {
        return leaveTimeoutSeconds;
    }

    public long getIdleTimeoutSeconds() {
        return idleTimeoutSeconds;
    }

    public int getHeartbeatSeconds() {
        return heartbeatSeconds;
    }
}
