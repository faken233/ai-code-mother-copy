package com.yupi.yuaicodemother.model.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 应用部署版本 实体类。
 * <p>
 * 每次部署产生一行：一个不可变镜像 {@code app-dep-{appId}:v{n}} 对应一个容器
 * {@code app-dep-{appId}-v{n}}，域名固定为 {@code {deployKey}.app.localhost}。
 * 回滚就是把路由指回上一条记录的上游，数据仍在同一个生产库里。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("app_deploy")
public class AppDeploy implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    private Long appId;

    /**
     * 版本号，从 1 递增
     */
    private Integer deployVersion;

    /**
     * 生产镜像，如 app-dep-471559690577387520:v1
     */
    private String image;

    /**
     * 容器名，如 app-dep-471559690577387520-v1
     */
    private String containerName;

    /**
     * 部署域名（不含端口）
     */
    private String host;

    /**
     * 当前上游地址 http://127.0.0.1:{随机端口}
     */
    private String upstream;

    /**
     * 生产库名；static 模式为 null
     */
    private String dbName;

    private String dbUser;

    /**
     * 生产库密码：回滚 / 容器重建时需要复用，不能每次随机
     */
    private String dbPassword;

    /**
     * JWT 密钥：跨版本稳定
     */
    private String jwtSecret;

    /**
     * BUILDING / ACTIVE / INACTIVE / FAILED / PRUNED
     */
    private String status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
