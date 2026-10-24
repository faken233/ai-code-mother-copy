package com.yupi.yuaicodemother.langgraph4j.node;

import com.yupi.yuaicodemother.ai.AppModeRouter;
import com.yupi.yuaicodemother.langgraph4j.state.WorkflowContext;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import com.yupi.yuaicodemother.utils.SpringContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 智能路由工作节点
 */
@Slf4j
public class RouterNode {

    public static AsyncNodeAction<MessagesState<String>> create() {
        return node_async(state -> {
            WorkflowContext context = WorkflowContext.getContext(state);
            log.info("执行节点: 智能路由");

            // 与创建应用时同一套规则路由（关键词），保证两处判定一致
            AppModeRouter router = SpringContextUtil.getBean(AppModeRouter.class);
            CodeGenTypeEnum generationType = router.route(context.getOriginalPrompt());
            log.info("规则路由完成，选择类型: {} ({})", generationType.getValue(), generationType.getText());

            // 更新状态
            context.setCurrentStep("智能路由");
            context.setGenerationType(generationType);
            return WorkflowContext.saveContext(context);
        });
    }
}