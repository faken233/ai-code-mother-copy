-- 应用访问路由：proxy 的按域名转发依据
-- 主服务写（DB + Redis），proxy 只读 Redis。
-- PREVIEW 与 DEPLOY 共用一张表，用 kind 区分；host 即访问域名（不含端口）。
create table if not exists app_access_route
(
    host        varchar(128) not null comment '访问域名（不含端口），如 k3m9x2q7.pv.localhost',
    app_id      bigint       not null comment '应用 ID',
    kind        varchar(16)  not null comment 'PREVIEW / DEPLOY',
    upstream    varchar(255) not null comment '上游地址，如 http://127.0.0.1:20925',
    status      varchar(16)  not null comment 'READY / STARTING / STOPPED',
    expire_at   datetime     null comment '预览路由过期时间；部署为 null',
    update_time datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    primary key (host),
    -- 一个应用在一个环境里只能有一个入口：避免并发预览产生两条路由、两个域名
    unique key uk_app_kind (app_id, kind)
) comment '应用访问路由' collate = utf8mb4_unicode_ci;
