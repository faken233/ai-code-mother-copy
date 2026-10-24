package com.yupi.yuaicodemother.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * 代码生成类型枚举。
 * <p>
 * 产品上只有两种模式：
 * - {@link #STATIC}    纯前端项目：同一套 React 模板，但没有 schema/数据库，数据只能放内存或 localStorage
 * - {@link #FULLSTACK} 带存储项目：schema.json 声明表结构，运行时自动建表，数据落 MySQL
 * <p>
 * 其余三个是历史类型，只为"已经生成过的老应用"保留可读性：
 * 不可再被选择、不再参与 AI 路由，预览时会提示重新生成。
 */
@Getter
public enum CodeGenTypeEnum {

    STATIC("纯前端项目", "static"),
    FULLSTACK("带存储项目", "fullstack"),

    @Deprecated
    HTML("原生 HTML 模式（旧）", "html"),
    @Deprecated
    MULTI_FILE("原生多文件模式（旧）", "multi_file"),
    @Deprecated
    VUE_PROJECT("Vue 工程模式（旧）", "vue_project");

    private final String text;
    private final String value;

    CodeGenTypeEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 是否为"跑在沙箱容器里"的类型（两者共用同一套模板与预览链路）
     */
    public boolean isSandboxType() {
        return this == STATIC || this == FULLSTACK;
    }

    /**
     * 是否允许新建（旧类型不可再选）
     */
    public boolean isSelectable() {
        return this == STATIC || this == FULLSTACK;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的value
     * @return 枚举值
     */
    public static CodeGenTypeEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (CodeGenTypeEnum anEnum : CodeGenTypeEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }

    /**
     * 根据 value 获取"可新建"的枚举；旧类型返回 null
     */
    public static CodeGenTypeEnum getSelectableEnumByValue(String value) {
        CodeGenTypeEnum anEnum = getEnumByValue(value);
        return anEnum != null && anEnum.isSelectable() ? anEnum : null;
    }
}
