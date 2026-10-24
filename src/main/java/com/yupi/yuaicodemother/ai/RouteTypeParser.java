package com.yupi.yuaicodemother.ai;

import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;

import java.util.Locale;

/**
 * 把 AI 路由的原始输出归一化成两种模式。
 * <p>
 * 为什么要容错：模型偶尔会带一句解释（"这是 FULLSTACK，因为需要存数据"），
 * 直接按严格 JSON 解析会失败；这里只认关键词，解析不出就按纯前端处理（成本更低、可用性更高）。
 */
public final class RouteTypeParser {

    private RouteTypeParser() {
    }

    public static CodeGenTypeEnum parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return CodeGenTypeEnum.STATIC;
        }
        String upper = raw.toUpperCase(Locale.ROOT);
        if (upper.contains("FULLSTACK")) {
            return CodeGenTypeEnum.FULLSTACK;
        }
        if (upper.contains("STATIC")) {
            return CodeGenTypeEnum.STATIC;
        }
        // 旧枚举名（HTML / MULTI_FILE / VUE_PROJECT）统一收敛到纯前端
        return CodeGenTypeEnum.STATIC;
    }
}
