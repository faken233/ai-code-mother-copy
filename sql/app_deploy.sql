-- 应用部署版本：每次「部署」产生一个不可变镜像 + 一个容器（app-dep-{appId}-v{n}）
-- 主服务写；proxy 不读这张表，只读 Redis 路由。
-- 部署库与预览库拆开：fullstack 生产库为 app_{appId}_prod，预览库仍是 app_{appId}。
create table if not exists app_deploy
(
    id             bigint auto_increment comment 'id' primary key,
    app_id         bigint       not null comment '应用 ID',
    deploy_version int          not null comment '部署版本号，从 1 递增',
    image          varchar(255) not null comment '生产镜像，如 app-dep-471559690577387520:v1',
    container_name varchar(128) not null comment '容器名，如 app-dep-471559690577387520-v1',
    host           varchar(128) not null comment '部署域名（不含端口），如 ab12cd.app.localhost',
    upstream       varchar(255) null comment '当前上游地址 http://127.0.0.1:{随机端口}',
    db_name        varchar(64)  null comment '生产库名；static 模式为 null',
    db_user        varchar(64)  null comment '生产库账号',
    db_password    varchar(128) null comment '生产库密码（回滚/重建容器时复用）',
    jwt_secret     varchar(128) null comment 'JWT 密钥（跨版本稳定，避免部署后用户全部掉线）',
    status         varchar(16)  not null comment 'BUILDING / ACTIVE / INACTIVE / FAILED / PRUNED',
    create_time    datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time    datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    unique key uk_app_version (app_id, deploy_version),
    key idx_app_status (app_id, status)
) comment '应用部署版本' collate = utf8mb4_unicode_ci;
