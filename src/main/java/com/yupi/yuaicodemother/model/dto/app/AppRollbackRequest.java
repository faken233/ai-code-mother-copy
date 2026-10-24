package com.yupi.yuaicodemother.model.dto.app;

import lombok.Data;

import java.io.Serializable;

/**
 * 回滚部署请求（P5）。
 * <p>
 * version 为空时回滚到上一个版本；指定时回滚到指定版本。
 */
@Data
public class AppRollbackRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标版本号；为空 = 上一个版本
     */
    private Integer version;
}
