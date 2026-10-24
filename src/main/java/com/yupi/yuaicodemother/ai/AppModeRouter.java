package com.yupi.yuaicodemother.ai;

import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 「自动识别」模式的路由器：纯规则，不调大模型。
 * <p>
 * 为什么不用 AI 路由：
 * 1. 这是一个二分类问题（要不要存数据），关键词规则足够准，且可解释、可测试；
 * 2. 实测当前网关对 LC4j 的结构化输出返回的 body 只有 "{ "，解析必然失败，
 *    结果就是每次创建应用都白调一次模型再走兜底（还会静默把所有应用判成同一个类型）；
 * 3. 省掉一次 1 秒级的同步模型调用，创建应用更快。
 * <p>
 * 判断原则：**能命中"要存东西"的词就 FULLSTACK，否则 STATIC**。
 * 纯前端成本更低、上线更快；只有当需求明确需要持久化时才升级。
 */
@Component
public class AppModeRouter {

    /**
     * 命中即 FULLSTACK 的关键词（数据要落库）
     */
    private static final List<String> STORAGE_KEYWORDS = List.of(
            // 记录类
            "记账", "账本", "台账", "账单", "开销", "收支", "流水", "记录", "历史",
            // 清单类
            "待办", "todo", "清单", "任务管理", "计划", "打卡", "习惯",
            // 内容类
            "留言", "评论", "博客", "文章", "笔记", "日记", "收藏", "点赞",
            // 业务系统
            "库存", "订单", "客户", "crm", "会员", "预约", "工单", "报销", "审批",
            // 通用信号
            "保存", "持久化", "数据库", "后台管理", "管理系统", "增删改查", "统计", "报表",
            "登录", "注册", "账号", "用户系统", "多用户", "权限"
    );

    /**
     * 路由：命中存储关键词 → FULLSTACK；否则 STATIC
     */
    public CodeGenTypeEnum route(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return CodeGenTypeEnum.STATIC;
        }
        String text = prompt.toLowerCase(Locale.ROOT);
        for (String keyword : STORAGE_KEYWORDS) {
            if (text.contains(keyword)) {
                return CodeGenTypeEnum.FULLSTACK;
            }
        }
        return CodeGenTypeEnum.STATIC;
    }
}
