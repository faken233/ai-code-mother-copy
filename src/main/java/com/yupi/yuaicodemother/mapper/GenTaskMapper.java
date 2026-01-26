package com.yupi.yuaicodemother.mapper;

import com.mybatisflex.core.BaseMapper;
import com.yupi.yuaicodemother.model.entity.GenTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * AI 代码生成任务 Mapper 层
 *
 * @author yupi
 */
public interface GenTaskMapper extends BaseMapper<GenTask> {

    /**
     * 获取当前排队任务数量
     *
     * @return 排队任务数量
     */
    @Select("SELECT COUNT(*) FROM gen_task WHERE status IN ('pending', 'running') AND isDelete = 0")
    int countQueueingTasks();

    /**
     * 获取用户在任务前面排队的任务数
     *
     * @param taskId 任务ID
     * @return 排在前面的任务数
     */
    @Select("SELECT COUNT(*) FROM gen_task WHERE id < #{taskId} AND status IN ('pending', 'running') AND isDelete = 0")
    int countTasksAhead(@Param("taskId") Long taskId);

    /**
     * 获取下一个待执行的任务
     *
     * @return 下一个待执行的任务
     */
    @Select("SELECT * FROM gen_task WHERE status = 'pending' AND isDelete = 0 ORDER BY createTime ASC LIMIT 1")
    GenTask getNextPendingTask();

    /**
     * 获取正在执行的任务数量
     *
     * @return 正在执行的任务数量
     */
    @Select("SELECT COUNT(*) FROM gen_task WHERE status = 'running' AND isDelete = 0")
    int countRunningTasks();

    /**
     * 获取用户指定应用的活跃任务
     *
     * @param userId 用户ID
     * @param appId 应用ID
     * @return 活跃任务列表
     */
    @Select("SELECT * FROM gen_task WHERE userId = #{userId} AND appId = #{appId} " +
            "AND status IN ('pending', 'running') AND isDelete = 0 ORDER BY createTime DESC LIMIT 1")
    GenTask getActiveTaskByUserAndApp(@Param("userId") Long userId, @Param("appId") Long appId);

    /**
     * 批量更新超时任务状态
     *
     * @param timeoutMinutes 超时分钟数
     * @return 更新的任务数
     */
    @Update("UPDATE gen_task SET status = 'failed', errorMessage = '任务执行超时', endTime = NOW() " +
            "WHERE status = 'running' AND startTime < DATE_SUB(NOW(), INTERVAL #{timeoutMinutes} MINUTE) AND isDelete = 0")
    int updateTimeoutTasks(@Param("timeoutMinutes") int timeoutMinutes);

    /**
     * 获取用户的待处理/执行中任务列表
     *
     * @param userId 用户ID
     * @return 任务列表
     */
    @Select("SELECT * FROM gen_task WHERE userId = #{userId} AND status IN ('pending', 'running') " +
            "AND isDelete = 0 ORDER BY createTime DESC")
    List<GenTask> getActiveTasksByUser(@Param("userId") Long userId);
}
