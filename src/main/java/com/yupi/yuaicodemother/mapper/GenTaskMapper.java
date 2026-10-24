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
     * 统计某应用的活跃任务数（pending / running / sandbox_building）
     * 回收任务用它判断"生成中，不要动容器"
     */
    @Select("SELECT COUNT(*) FROM gen_task WHERE appId = #{appId} AND isDelete = 0 " +
            "AND status IN ('pending', 'running', 'sandbox_building')")
    int countActiveTasksByApp(@Param("appId") Long appId);

    /**
     * 统计某应用"正在执行"的任务数（running / sandbox_building）。
     * 路由对账用：排队中的任务不算执行中，不能因为它而保留一条指向死容器的路由。
     */
    @Select("SELECT COUNT(*) FROM gen_task WHERE appId = #{appId} AND isDelete = 0 " +
            "AND status IN ('running', 'sandbox_building')")
    int countExecutingTasksByApp(@Param("appId") Long appId);

    /**
     * 标记任务进入沙箱搭建阶段。
     * <p>
     * startTime 只在为空时写：沙箱搭建与后续代码生成共用同一个超时起点，
     * 否则 startTime 为 null 时 {@code updateTimeoutTasks} 永远扫不到 sandbox_building。
     */
    @Update("UPDATE gen_task SET status = 'sandbox_building', startTime = IFNULL(startTime, NOW()), " +
            "updateTime = NOW() WHERE id = #{taskId} AND isDelete = 0")
    int markSandboxBuilding(@Param("taskId") Long taskId);

    /**
     * 标记任务进入代码生成阶段（不重置 startTime，避免沙箱搭建耗时绕过超时）。
     */
    @Update("UPDATE gen_task SET status = 'running', startTime = IFNULL(startTime, NOW()), " +
            "updateTime = NOW() WHERE id = #{taskId} AND isDelete = 0")
    int markRunning(@Param("taskId") Long taskId);

    /**
     * 批量更新超时任务状态
     * <p>
     * P6：除 running 外也覆盖 sandbox_building——沙箱搭建卡死时任务会永远停在该状态。
     * startTime 为空的历史数据不参与（避免误杀刚创建、尚未真正执行的任务）。
     *
     * @param timeoutMinutes 超时分钟数
     * @return 更新的任务数
     */
    @Update("UPDATE gen_task SET status = 'failed', errorMessage = '任务执行超时', endTime = NOW() " +
            "WHERE status IN ('running', 'sandbox_building') AND startTime IS NOT NULL " +
            "AND startTime < DATE_SUB(NOW(), INTERVAL #{timeoutMinutes} MINUTE) AND isDelete = 0")
    int updateTimeoutTasks(@Param("timeoutMinutes") int timeoutMinutes);

    /**
     * 获取用户的待处理/执行中任务列表（含沙箱搭建中）
     *
     * @param userId 用户ID
     * @return 任务列表
     */
    @Select("SELECT * FROM gen_task WHERE userId = #{userId} AND status IN ('pending', 'running', 'sandbox_building') " +
            "AND isDelete = 0 ORDER BY createTime DESC")
    List<GenTask> getActiveTasksByUser(@Param("userId") Long userId);
}
