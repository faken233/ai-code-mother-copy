package com.yupi.yuaicodemother.model.enums;

import lombok.Getter;

/**
 * AI 生成任务状态枚举
 *
 * @author yupi
 */
@Getter
public enum GenTaskStatusEnum {

    PENDING("pending", "排队中"),
    RUNNING("running", "执行中"),
    COMPLETED("completed", "已完成"),
    FAILED("failed", "失败"),
    CANCELLED("cancelled", "已取消");

    private final String value;
    private final String text;

    GenTaskStatusEnum(String value, String text) {
        this.value = value;
        this.text = text;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值
     * @return 枚举对象
     */
    public static GenTaskStatusEnum getEnumByValue(String value) {
        if (value == null) {
            return null;
        }
        for (GenTaskStatusEnum statusEnum : GenTaskStatusEnum.values()) {
            if (statusEnum.getValue().equals(value)) {
                return statusEnum;
            }
        }
        return null;
    }
}
