package com.yupi.yuaicodemother.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.yupi.yuaicodemother.common.BaseResponse;
import com.yupi.yuaicodemother.common.ResultUtils;
import com.yupi.yuaicodemother.exception.ErrorCode;
import com.yupi.yuaicodemother.exception.ThrowUtils;
import com.yupi.yuaicodemother.model.dto.gentask.GenTaskCreateRequest;
import com.yupi.yuaicodemother.model.entity.GenTask;
import com.yupi.yuaicodemother.model.entity.User;
import com.yupi.yuaicodemother.model.vo.GenTaskVO;
import com.yupi.yuaicodemother.ratelimter.annotation.RateLimit;
import com.yupi.yuaicodemother.ratelimter.enums.RateLimitType;
import com.yupi.yuaicodemother.service.GenTaskService;
import com.yupi.yuaicodemother.service.UserService;
import com.yupi.yuaicodemother.sse.SseConnectionManager;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

/**
 * AI 代码生成任务控制器
 * 支持任务排队和可重连 SSE
 *
 * @author yupi
 */
@RestController
@RequestMapping("/gen-task")
@Slf4j
public class GenTaskController {

    @Resource
    private GenTaskService genTaskService;

    @Resource
    private UserService userService;

    @Resource
    private SseConnectionManager sseConnectionManager;

    /**
     * 创建生成任务
     * 任务会被加入队列，返回任务信息
     *
     * @param request        请求
     * @param createRequest  创建请求
     * @return 任务信息
     */
    @PostMapping("/create")
    @RateLimit(limitType = RateLimitType.USER, rate = 10, rateInterval = 60, message = "创建任务请求过于频繁，请稍后再试")
    public BaseResponse<GenTaskVO> createTask(@RequestBody GenTaskCreateRequest createRequest,
                                              HttpServletRequest request) {
        // 参数校验
        ThrowUtils.throwIf(createRequest == null, ErrorCode.PARAMS_ERROR);
        // appId 可以为空（首次对话时自动创建应用）
        ThrowUtils.throwIf(StrUtil.isBlank(createRequest.getMessage()),
                ErrorCode.PARAMS_ERROR, "消息不能为空");

        // 获取当前登录用户
        User loginUser = userService.getLoginUser(request);

        // 创建任务
        GenTask task = genTaskService.createTask(createRequest.getAppId(), createRequest.getMessage(), loginUser);

        // 获取队列位置
        int queuePosition = genTaskService.getQueuePosition(task.getId());

        // 构建返回对象
        GenTaskVO taskVO = buildTaskVO(task, queuePosition);

        return ResultUtils.success(taskVO);
    }

    /**
     * 连接 SSE 流
     * 支持断线重连（通过 Last-Event-ID 或 existingContentLength 参数）
     *
     * @param taskId                任务ID
     * @param lastEventId           上次事件ID（可选，用于断线重连）
     * @param existingContentLength 已接收内容长度（可选，用于断点续传）
     * @param request               请求
     * @return SSE 流
     */
    @GetMapping(value = "/stream/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> connectStream(
            @PathVariable Long taskId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(value = "offset", required = false) Integer existingContentLength,
            HttpServletRequest request) {

        // 参数校验
        ThrowUtils.throwIf(taskId == null || taskId <= 0, ErrorCode.PARAMS_ERROR, "任务 ID 错误");

        // 获取当前登录用户
        User loginUser = userService.getLoginUser(request);

        // 验证任务归属
        genTaskService.getTask(taskId, loginUser);

        log.info("建立 SSE 连接，taskId: {}, lastEventId: {}, offset: {}", taskId, lastEventId, existingContentLength);

        // 创建 SSE 连接
        return sseConnectionManager.createConnection(taskId, lastEventId, existingContentLength);
    }

    /**
     * 获取任务状态
     *
     * @param taskId  任务ID
     * @param request 请求
     * @return 任务状态
     */
    @GetMapping("/status/{taskId}")
    public BaseResponse<GenTaskVO> getTaskStatus(@PathVariable Long taskId, HttpServletRequest request) {
        // 参数校验
        ThrowUtils.throwIf(taskId == null || taskId <= 0, ErrorCode.PARAMS_ERROR, "任务 ID 错误");

        // 获取当前登录用户
        User loginUser = userService.getLoginUser(request);

        // 获取任务
        GenTask task = genTaskService.getTask(taskId, loginUser);

        // 获取队列位置
        int queuePosition = genTaskService.getQueuePosition(taskId);

        // 构建返回对象
        GenTaskVO taskVO = buildTaskVO(task, queuePosition);

        return ResultUtils.success(taskVO);
    }

    /**
     * 取消任务
     *
     * @param taskId  任务ID
     * @param request 请求
     * @return 是否成功
     */
    @PostMapping("/cancel/{taskId}")
    public BaseResponse<Boolean> cancelTask(@PathVariable Long taskId, HttpServletRequest request) {
        // 参数校验
        ThrowUtils.throwIf(taskId == null || taskId <= 0, ErrorCode.PARAMS_ERROR, "任务 ID 错误");

        // 获取当前登录用户
        User loginUser = userService.getLoginUser(request);

        // 取消任务
        boolean result = genTaskService.cancelTask(taskId, loginUser);

        return ResultUtils.success(result);
    }

    /**
     * 获取用户的活跃任务列表
     *
     * @param request 请求
     * @return 任务列表
     */
    @GetMapping("/active/list")
    public BaseResponse<List<GenTaskVO>> getActiveTasks(HttpServletRequest request) {
        // 获取当前登录用户
        User loginUser = userService.getLoginUser(request);

        // 获取活跃任务列表
        List<GenTask> tasks = genTaskService.getActiveTasksByUser(loginUser.getId());

        // 转换为 VO 列表
        List<GenTaskVO> taskVOList = tasks.stream()
                .map(task -> buildTaskVO(task, genTaskService.getQueuePosition(task.getId())))
                .collect(Collectors.toList());

        return ResultUtils.success(taskVOList);
    }

    /**
     * 获取任务已生成的内容（用于断线重连后获取完整内容）
     *
     * @param taskId  任务ID
     * @param request 请求
     * @return 已生成的内容
     */
    @GetMapping("/content/{taskId}")
    public BaseResponse<String> getGeneratedContent(@PathVariable Long taskId, HttpServletRequest request) {
        // 参数校验
        ThrowUtils.throwIf(taskId == null || taskId <= 0, ErrorCode.PARAMS_ERROR, "任务 ID 错误");

        // 获取当前登录用户
        User loginUser = userService.getLoginUser(request);

        // 验证任务归属
        genTaskService.getTask(taskId, loginUser);

        // 获取已生成的内容
        String content = genTaskService.getGeneratedContent(taskId);

        return ResultUtils.success(content);
    }

    /**
     * 构建任务 VO
     */
    private GenTaskVO buildTaskVO(GenTask task, int queuePosition) {
        GenTaskVO vo = new GenTaskVO();
        BeanUtil.copyProperties(task, vo);
        vo.setQueuePosition(queuePosition);
        return vo;
    }
}
