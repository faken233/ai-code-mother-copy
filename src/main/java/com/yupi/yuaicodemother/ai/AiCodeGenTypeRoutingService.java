package com.yupi.yuaicodemother.ai;

import dev.langchain4j.service.SystemMessage;

/**
 * AI代码生成类型智能路由服务
 * 使用结构化输出直接返回枚举类型
 *
 * @author yupi
 */
public interface AiCodeGenTypeRoutingService {

    /**
     * 根据用户需求智能选择代码生成类型。
     * <p>
     * 刻意返回 String 而不是枚举：LC4j 对枚举返回值走结构化输出（response_format/json_schema），
     * 当前网关对该模式返回的 body 无法被解析（实测只拿到 "{ "），会让路由永远走兜底。
     * 这里让模型输出一个词，由调用方用 {@code RouteTypeParser} 归一化。
     *
     * @param userPrompt 用户输入的需求描述
     * @return STATIC 或 FULLSTACK（可能夹带少量说明文字）
     */
    @SystemMessage(fromResource = "prompt/codegen-routing-system-prompt.txt")
    String routeCodeGenType(String userPrompt);
}
