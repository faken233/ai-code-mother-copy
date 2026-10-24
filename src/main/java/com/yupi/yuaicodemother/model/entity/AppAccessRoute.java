package com.yupi.yuaicodemother.model.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 应用访问路由 实体类。
 * <p>
 * 主服务写入（MySQL 为准 + Redis 缓存），proxy 只读 Redis。
 * host 是主键：一个域名对应一个上游容器，预览和部署各占一个域名。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("app_access_route")
public class AppAccessRoute implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 访问域名（不含端口）
     */
    @Id(keyType = KeyType.None)
    private String host;

    /**
     * 应用 ID
     */
    private Long appId;

    /**
     * 类型：PREVIEW / DEPLOY
     */
    private String kind;

    /**
     * 上游地址，如 http://127.0.0.1:20925
     */
    private String upstream;

    /**
     * 状态：READY / STARTING / STOPPED
     */
    private String status;

    /**
     * 预览路由过期时间；部署为 null
     */
    private LocalDateTime expireAt;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
