package com.yupi.yuaicodemother.deploy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * 启动时保证部署表存在。
 * <p>
 * 项目没有引入 Flyway，历史上 app_access_route 靠手工执行 sql。部署是核心路径，
 * 少一张表就整条链路不可用，所以在启动时做一次幂等建表（DDL 与 sql/app_deploy.sql 一致）。
 * 没权限时只告警不阻断启动——沙箱/部署关闭的环境照样能跑。
 */
@Slf4j
@Component
public class DeploySchemaInitializer {

    private static final String DDL = """
            create table if not exists app_deploy
            (
                id             bigint auto_increment comment 'id' primary key,
                app_id         bigint       not null comment '应用 ID',
                deploy_version int          not null comment '部署版本号，从 1 递增',
                image          varchar(255) not null comment '生产镜像',
                container_name varchar(128) not null comment '容器名',
                host           varchar(128) not null comment '部署域名（不含端口）',
                upstream       varchar(255) null comment '当前上游地址',
                db_name        varchar(64)  null comment '生产库名；static 模式为 null',
                db_user        varchar(64)  null comment '生产库账号',
                db_password    varchar(128) null comment '生产库密码',
                jwt_secret     varchar(128) null comment 'JWT 密钥',
                status         varchar(16)  not null comment 'BUILDING / ACTIVE / INACTIVE / FAILED / PRUNED',
                create_time    datetime default CURRENT_TIMESTAMP not null comment '创建时间',
                update_time    datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
                unique key uk_app_version (app_id, deploy_version),
                key idx_app_status (app_id, status)
            ) comment '应用部署版本' collate = utf8mb4_unicode_ci
            """;

    public DeploySchemaInitializer(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(DDL);
            log.info("app_deploy 表已就绪");
        } catch (Exception e) {
            log.warn("初始化 app_deploy 表失败（部署功能可能不可用，请手工执行 sql/app_deploy.sql）: {}", e.getMessage());
        }
    }
}
