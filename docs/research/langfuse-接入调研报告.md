# Langfuse 接入技术调研报告
## 面向「Spring Boot 3 + LangChain4j」AI 代码生成平台的 AI 执行链路监控

> 调研时间：2026-09-10（UTC）
> 调研范围：Langfuse v4 / LangChain4j 1.18.x / Spring Boot 3.5.x / OpenTelemetry Java 1.49.0 + 2.31.1
> 所有结论均标注来源 URL；无法核实的内容显式标注「未验证」。
> 文档可在 Langfuse 官网 URL 后加 `.md` 直接取到纯 Markdown 原文（例如 `https://langfuse.com/integrations/native/opentelemetry.md`），本报告中的 Langfuse 事实多以此方式核对。

---

# 0. 结论摘要（给决策者的 12 行）

1. **不存在官方的 `dev.langchain4j:langchain4j-langfuse` 模块**。Langfuse 官方对 Java 的定位是「用 OpenTelemetry 自己埋点」（[langfuse-java README](https://github.com/langfuse/langfuse-java)）。社区里也没有可用的 LangChain4j→Langfuse 第三方库。
2. **但存在一条与本项目版本完全对齐的官方路径**：`dev.langchain4j:langchain4j-observation:1.18.1-beta28`（依赖 `langchain4j-core:1.18.1` + micrometer 1.16.5），它提供 `ObservationChatModelListener`——把每次 ChatModel 调用变成一个 **Micrometer Observation**。
3. 项目已有 `micrometer-registry-prometheus`，只需再加 `spring-boot-starter-actuator` + `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`，Micrometer Observation 就会**自动**变成 OTel span，并通过 OTLP 直发 Langfuse。**现有 `AiModelMonitorListener` 一行都不用改**；starter 自动装配的 `openAiChatModel`/`openAiStreamingChatModel` 会自动挂上新 listener；唯一要动的是手写的 `RoutingAiModelConfig.routingChatModelPrototype()`（补一行 `.listeners(...)`）。
4. Langfuse 服务端把 `gen_ai.operation.name=chat` 映射为 **GENERATION**（见 Langfuse 源码 `ObservationTypeMapper.ts`），而 `langchain4j-observation` 的默认 convention 恰好就产出这个属性 → 开箱即可得到「生成」类型的观测。
5. **有 3 个字段必须自己补**（`langchain4j-observation` 默认不带，见 §5.2）：observation 的 **input / output 内容**、**token 用量**（它写的是裸 `input_tokens`，Langfuse 只认 `gen_ai.usage.*`）、**TTFT/首 token 时间**。
6. **⭐ 可以零风险先做一步**：单独加 `dev.langchain4j:langchain4j-micrometer-metrics:1.18.1-beta28`，立刻在**现有 Grafana** 上得到 `gen_ai.client.token.usage`（按 input/output/model 分组）——**零新组件、不碰 Langfuse**，用来先摸清 token 成本基线（§6.4）。
7. **不要走 `POST /api/public/ingestion`**：该接口已被官方标记 deprecated，Langfuse Cloud 于 2026-11-16 进入 v4-only 后**拒绝 trace/observation 事件**；self-host v4 在 v4-only write 模式下同样拒绝（[ingestion.yml 规格](https://raw.githubusercontent.com/langfuse/langfuse/main/fern/apis/server/definition/ingestion.yml)）。
8. **不要指望 OTel Java Agent「零代码」能给你 LLM 链路**：Java Agent 没有 LangChain4j 的 instrumentation（Maven Central `io.opentelemetry.instrumentation` 下无 langchain4j 模块），只能产出 HTTP/JDBC/Spring 等基础设施 span，Langfuse 里看到的是一堆普通 span，没有 generation、没有 token、没有 prompt。
9. **唯一的替代强选项是 Arize Phoenix**：`com.arize:openinference-instrumentation-langchain4j:0.1.8` 是 Maven Central 上**唯一官方发布的 LangChain4j 埋点库**（开箱即含 messages/token），单容器部署；**但 Phoenix 服务端许可是 ELv2（非 OSI 开源）**，对外 SaaS 前必须过法务。Langfuse 与 Phoenix 都吃 OTLP，可以用一个 OTel Collector **扇出同时喂两边做 PoC**，再决定（§6.5）。
10. 自托管 v4 需要 **6 个容器**：`langfuse-web`、`langfuse-worker`、`clickhouse-server:25.12`、`postgres:17`、`redis:7`、`minio`；官方最低规格 web/worker/PG 各 2C4G、**ClickHouse 2C8G**，单机 compose 建议 **≥4 vCPU / 16 GiB / 100 GiB**。本项目 docker compose 已占用的 **3306(mysql)/6379(redis)** 与服务名 `redis`/`postgres` 都会冲突（§7.2）。
11. **最大运维风险是数据无限增长**：self-host 默认**永不删除**数据，Langfuse 内置的 Data Retention 功能在 self-host 属于 **Enterprise Edition**（[Data Retention](https://langfuse.com/docs/administration/data-retention)）→ 必须自己在 ClickHouse 层做 TTL，**并且要治理 ClickHouse 系统日志表**（社区实测 `system.trace_log` 66.86 GiB vs 业务数据 <52 MiB）。另外 **ClickHouse 丢失没有内置的从 S3 回灌机制**，必须做快照。
12. **最大技术风险是版本兼容**：`langchain4j-observation:1.18.1-beta28` 编译时依赖 micrometer **1.16.5**，而 Spring Boot 3.5.4 的 BOM 把 micrometer 固定在 **1.15.2**（`micrometer-bom` 1.15.2 / `micrometer-tracing-bom` 1.5.2 import）。二者 API 大概率兼容但**必须实测**（见 §7.3）。

---

# 1. Langfuse 是什么

## 1.1 定位

Langfuse 是一个开源的 **LLM 工程平台**，三大块能力：

| 模块 | 内容 |
|---|---|
| Observability（追踪） | trace / observation 树、成本与 token 统计、延迟、session、用户、评分 |
| Prompt Management | 版本化 prompt、Playground、prompt 与 trace 关联 |
| Evaluation | LLM-as-a-Judge、代码评估器（Python/TS）、数据集与实验（dataset / experiment） |

核心 OSS 采用 MIT 许可、自托管免费（[Billable Units 文档](https://langfuse.com/docs/administration/billable-units) 明确写 "Self-hosted Langfuse (OSS) is free under the MIT license"）。Langfuse 官方同时提供 Cloud 托管版。

**当前版本节点（关键）**：

- **Langfuse v4 已于 2026-08-17 GA**（[changelog](https://langfuse.com/changelog/2026-08-17-langfuse-v4)）。
- **Langfuse Cloud 于 2026-11-16 变为 v4-only**，届时旧的 API、功能、旧 ingestion 全部移除。
- **自托管没有强制切换日期**，v3 会继续收到安全补丁直到 **2027 年 1 月**。
- 自托管 docker-compose 官方镜像 tag 已经统一为 **`:4`**。

## 1.2 核心数据模型（v4 = observations-first）

来源：<https://langfuse.com/docs/observability/data-model>

三个核心概念：

| 概念 | 定义 |
|---|---|
| **Observation** | 应用中的单个步骤：LLM 调用、工具调用、检索……可嵌套。类型（`langfuse.observation.type`）取值为 `span` / `generation` / `event` / `embedding` / `agent` / `tool` / `chain` / `retriever` / `guardrail` / `evaluator` |
| **Trace** | 一次完整请求/操作，是共享同一个 `trace_id` 的所有 observation 的逻辑分组 |
| **Session** | 可选，把多个 trace 归组（例如一轮多轮对话） |

**v4 的结构性变化（会影响你的查询与 SDK 用法）**：

- Langfuse **只存一张 observations 表**，每一行同时持有 observation 级数据和**trace 级属性的副本**。所以 `user_id`、`session_id`、`tags`、`metadata` 这些 trace 级属性**必须在每条 span 上都存在**，否则无法按它们过滤/聚合。这是 v4 最容易被忽略、也最容易踩坑的一点，官方在 OTel 文档里专门用一节强调（见 §5）。
- 读接口全部收敛：`GET /traces`、`GET /observations`、`GET /sessions` → 统一走 `GET /api/public/v2/observations`（按 `traceId` / `sessionId` 过滤后客户端分组）；`GET /metrics` → `GET /api/public/v2/metrics`；`GET /scores` → `GET /api/public/v3/scores`（[Migration of deprecated APIs](https://langfuse.com/faq/all/deprecated-api-migration)）。
- **v4 中 trace 不再有独立的 input/output**：需要从 root observation（`parentObservationId == null`，或 v2 的 `isRootObservation = true` 维度）重建。

其余模型：

| 概念 | 说明与来源 |
|---|---|
| **Score** | 对 trace/observation/session 的评分，类型 numeric / categorical / boolean。写接口 `POST /api/public/scores` 与 SDK 的 `score-create` 事件在 v4 后**依然被接受**（唯一没被废弃的 ingestion 事件类型） |
| **Dataset / DatasetItem** | 评测数据集 |
| **Experiment / ExperimentItem** | 取代旧的 "dataset run"，`POST /dataset-run-items` 已废弃 |
| **Billable Unit** | `Units = Trace 数 + Observation 数 + Score 数`（[Billable Units](https://langfuse.com/docs/administration/billable-units)）。**注意：Langfuse 自身功能（LLM-as-a-Judge、标注队列、实验）产生的 observation 也算 units** |

## 1.3 自托管 docker compose 部署要求

### 1.3.1 官方 compose 里的服务与镜像

以官方仓库 `main` 分支的 [`docker-compose.yml`](https://github.com/langfuse/langfuse/blob/main/docker-compose.yml) 为准（已实际拉取核对）：

| 服务 | 镜像 | 作用 | 端口映射（官方 compose 原文） |
|---|---|---|---|
| `langfuse-web` | `docker.langfuse.com/langfuse/langfuse:4` | Web UI + Public API（含 `/api/public/otel`） | `3000:3000`（**监听所有网卡**） |
| `langfuse-worker` | `docker.langfuse.com/langfuse/langfuse-worker:4` | 后台任务（异步处理事件、发邮件） | `127.0.0.1:3030:3030` |
| `clickhouse` | `docker.io/clickhouse/clickhouse-server:25.12` | **所有 trace/observation/score 的存储**（列存）。官方 FAQ：「ClickHouse is currently a **required** component…There is no alternative OLAP database supported at this time.」 | `127.0.0.1:8123:8123`（HTTP）、`127.0.0.1:9000:9000`（native） |
| `minio` | `cgr.dev/chainguard/minio` | S3 兼容对象存储：event upload + media upload + batch export。**可选**——用云 S3/GCS/Azure 时不需要它 | `9090:9000`（**对外**）、`127.0.0.1:9091:9001`（控制台） |
| `redis` | `docker.io/redis:7` | 队列 + 缓存（API key 缓存、prompt 读穿缓存） | `127.0.0.1:6379:6379` |
| `postgres` | `docker.io/postgres:${POSTGRES_VERSION:-17}` | 事务/元数据（用户、组织、项目、加密 API key、prompt、dataset…） | `127.0.0.1:5432:5432` |

官方 compose 的关键结构性事实（**直接影响合并进现有 compose 的方式**）：

- 顶层只有 `services:` 和 `volumes:`，**没有 `networks:` 顶层键，也没有 `container_name`** → 各服务走该 compose project 的默认网络，靠 **service 名**（`postgres`/`redis`/`clickhouse`/`minio`）互相寻址。
- 头部注释建议：宿主机入站**只开放 3000（web）和 9090（minio）**，其余全部绑 `127.0.0.1`。
- Redis 启动参数：`--requirepass ${REDIS_AUTH:-myredissecret} --maxmemory-policy noeviction` ← **`noeviction` 是硬要求**，否则队列 job 被驱逐、worker 静默停摆。
- Postgres 环境变量含 `TZ: UTC` / `PGTZ: UTC` ← **Langfuse 要求基础设施默认 UTC**，覆盖 UTC 的 PG/ClickHouse 设置不被支持。
- MinIO 的 entrypoint 会先 `mkdir -p /data/langfuse` 再启动；`LANGFUSE_S3_EVENT_UPLOAD_BUCKET` 是 **Mandatory** 配置项。
- 命名卷：`langfuse_{postgres,clickhouse,redis,minio}_data` + `langfuse_clickhouse_logs`。

必备环境变量（compose 中标记 `# CHANGEME` 的敏感项）：`DATABASE_URL`、`SALT`、`ENCRYPTION_KEY`（`openssl rand -hex 32` 生成）、`NEXTAUTH_SECRET`、`CLICKHOUSE_PASSWORD`、`REDIS_AUTH`、`MINIO_ROOT_PASSWORD`、`LANGFUSE_S3_EVENT_UPLOAD_SECRET_ACCESS_KEY`、`LANGFUSE_S3_MEDIA_UPLOAD_SECRET_ACCESS_KEY`、`LANGFUSE_S3_BATCH_EXPORT_SECRET_ACCESS_KEY`。

文档侧的部署要求：git、docker & docker compose；启动后约 2–3 分钟 `langfuse-web-1` 打印 "Ready"，浏览器访问 `http://localhost:3000`（[Docker Compose 部署指南](https://langfuse.com/self-hosting/deployment/docker-compose)）。指南还明确：**compose 方式不支持横向扩展与高可用**，只适合起步/小规模，要 HA 就上 Kubernetes。

### 1.3.2 资源占用

官方一共给了三档口径，别混淆：

**(A) 官方最低基础设施要求**（逐字来自 [Scaling › Minimum Infrastructure Requirements](https://langfuse.com/self-hosting/configuration/scaling#minimum-infrastructure-requirements)）：

| 组件 | 最低要求 |
|---|---|
| Langfuse Web 容器 | 2 CPU / 4 GiB |
| Langfuse Worker 容器 | 2 CPU / 4 GiB |
| PostgreSQL | 2 CPU / 4 GiB |
| Redis / Valkey | 1 CPU / 1.5 GiB |
| **ClickHouse** | **2 CPU / 8 GiB** |
| Blob Storage | Serverless（S3 兼容）或 MinIO（2 CPU / 4 GiB） |

**(B) 官方容器通用建议**：所有容器 ≥ 2 CPU / 4 GB；生产**至少 2 个 web 实例**；单容器 CPU > 50% 时扩容（[Application Containers](https://langfuse.com/self-hosting/deployment/infrastructure/containers)）。
**Node.js 堆限制**：默认 `max-old-space-size` 只有 **1.7 GiB**，容器给了 4 GiB 也用不上，web 与 worker 都必须设 `NODE_OPTIONS=--max-old-space-size=${var.memory}`。

**(C) 单机 docker compose 的 VM 建议**：**≥ 4 cores / 16 GiB / ≥ 100 GiB 磁盘**（[Docker Compose 指南](https://langfuse.com/self-hosting/deployment/docker-compose)）。

**其它官方容量指引**：

| 项 | 官方说法 | 来源 |
|---|---|---|
| ClickHouse 大部署内存 | 「we recommend **at least 16 GiB of memory for larger deployments**」；ClickHouse 设计为垂直扩展 | [Scaling](https://langfuse.com/self-hosting/configuration/scaling) |
| ClickHouse 集群 | Helm 默认（也是 CH 官方推荐最小部署）**3 节点 × 2 核 8 GiB**；`cluster.replicas` 生产建议 ≥3；单 shard 可撑多 TB；**Langfuse 不支持多 shard** | [ClickHouse 部署](https://langfuse.com/self-hosting/deployment/infrastructure/clickhouse) |
| Redis 内存 | 「For every **~100000 events per minute** we recommend about **1 GB** of memory」 | [Cache](https://langfuse.com/self-hosting/deployment/infrastructure/cache#sizing-recommendations) |
| Redis CPU | 高负载用 ≥4 CPU 实例 | [Scaling](https://langfuse.com/self-hosting/configuration/scaling) |
| S3 并发写 | `LANGFUSE_S3_CONCURRENT_WRITES` 默认 **50**；日志出现 `socket usage at capacity=150` 时逐步提高 | 同上 |

> **社区报告（非官方承诺，仅作参考）**：
> - 默认 compose 部署中**系统日志表会失控**：某实例 `system.trace_log` **66.86 GiB**，而 Langfuse 业务表合计 `traces` 6.49 MiB + `observations` 44.82 MiB + `scores` 78.37 KiB < **52 MiB**（[issue #13123](https://github.com/langfuse/langfuse/issues/13123)）。
> - 生产环境 `observations` 表 inactive parts 累积到 **112 GiB**（[discussion #15024](https://github.com/orgs/langfuse/discussions/15024)，未答复）。
> - 维护者在 [discussion #5924](https://github.com/orgs/langfuse/discussions/5924) 建议 ClickHouse 3×2C/8GiB、Redis ~2 GiB 起步。

> **未验证**：ClickHouse 在 Langfuse 生产负载下的具体磁盘/内存基线换算公式。Langfuse 工程博客 [How Langfuse runs ClickHouse at agent scale](https://langfuse.com/resources/engineering/clickhouse-at-agent-scale) 描述了「agent 负载 = 高写入 + 深 trace + 高基数分析读」的压力模型（Cloud 侧是 PB 级），但**没有给出 self-host 的容量公式**。

### 1.3.3 数据保留（self-host 的坑）

- **self-host 默认永久保留，不会自动删除**（[Data Retention](https://langfuse.com/docs/administration/data-retention)）。
- Langfuse 内置的 **Data Retention 功能在 self-host 属于 Enterprise Edition**（Cloud 侧 Pro/Enterprise 才有；Hobby/Core 不可用）。OSS 用户需要**自己在 ClickHouse 层设置 TTL / 定期删除**。
- Cloud 的数据访问窗口：Hobby 30 天、Core 90 天、Pro/Enterprise 3 年。
- 官方 Data Retention 细节：按**项目**配置、**最小 3 天**、每晚执行；判定字段为 Traces `timestamp` / Observations `start_time` / Scores `timestamp` / Media Assets `created_at`；**删除不可恢复**；需要 Langfuse 的 IAM role 在所有 bucket 上有 `s3:DeleteObject`（版本化桶的 delete marker / 非当前版本需自己用 lifecycle 清理）。
- 需要长期归档可以配 Blob Storage Export（把 trace/observation/score 同步到 S3/GCS/Azure）。

**OSS 的替代路径：ClickHouse TTL**（[Scaling › ClickHouse disk usage](https://langfuse.com/self-hosting/configuration/scaling#clickhouse-disk-usage)）

官方给的「找出最大的表」SQL：

```sql
SELECT table, formatReadableSize(size) as size, rows FROM (
    SELECT table, database, sum(bytes) AS size, sum(rows) AS rows
    FROM system.parts WHERE active
    GROUP BY table, database ORDER BY size DESC
)
```

- TTL 适用于 `traces`、`observations`、`scores`、`event_log` 四张表。
- 删除超时可通过 `LANGFUSE_CLICKHOUSE_DELETION_TIMEOUT_MS`（默认 `600000` = 10 分钟）调整。
- ClickHouse 25.7+ 可把 `CLICKHOUSE_LIGHTWEIGHT_DELETE_MODE` 从默认 `alter_update` 改为 `lightweight_update` / `lightweight_update_force`，并设 `CLICKHOUSE_USE_LIGHTWEIGHT_UPDATE=true`（需先在 CH 侧 `CREATE SETTINGS PROFILE langfuse_lightweight_update SETTINGS enable_lightweight_update = 1 TO 'user';`）。

> ⚠️ **官方两处口径冲突**：[ClickHouse FAQ](https://langfuse.com/self-hosting/deployment/infrastructure/clickhouse#how-do-i-manage-clickhouse-storage-growth) 说「Langfuse 有内置 Data Retention…**You do not need to set ClickHouse TTLs manually**」，但 [Data Retention 文档](https://langfuse.com/docs/administration/data-retention) 明说自托管需要 **Enterprise Edition**。→ 实务结论：**OSS 无 EE 授权时，必须自己上 ClickHouse TTL 兜底**。

**真正的磁盘黑洞是 ClickHouse 系统日志表**（[Scaling › system log tables](https://langfuse.com/self-hosting/configuration/scaling#clickhouse-system-log-tables)）：

`trace_log`、`text_log`、`opentelemetry_span_log`、`asynchronous_metric_log`、`metric_log`、`latency_log` 默认**无 TTL**，且 query profiler 持续往 `system.trace_log` 写，而 Langfuse 根本不读这些表。两种治理方式：

- **方案 1（官方 Terraform 模块默认做法）——直接移除**：挂载到 `/etc/clickhouse-server/config.d/`：
  ```xml
  <clickhouse>
      <trace_log remove="1"/>
      <text_log remove="1"/>
      <opentelemetry_span_log remove="1"/>
      <asynchronous_metric_log remove="1"/>
      <metric_log remove="1"/>
      <latency_log remove="1"/>
  </clickhouse>
  ```
  保留 `query_log`、`part_log`、`error_log`（有用且体积小）。
- **方案 2——保留表但加 TTL + 关闭 query profiler**（`query_profiler_real_time_period_ns=0`、`query_profiler_cpu_time_period_ns=0`）。注意 **TTL 只在系统表首次创建时生效**，已有实例需 `TRUNCATE TABLE system.trace_log; ALTER TABLE system.trace_log MODIFY TTL event_date + INTERVAL 7 DAY;`。

**S3 / Blob 存储生命周期**：

- **events bucket** 建议设 lifecycle，多数客户用 **30 天**。
- **media bucket 禁止设 lifecycle**（会导致被引用的媒体失效 + 同 hash 再次上传失败，因为上传状态按 hash 记在 Postgres）。

**单字段大小上限（与代码生成场景强相关）**：`LANGFUSE_OBSERVATION_FIELD_SIZE_LIMIT_BYTES` 默认 **2 097 152（2 MiB）**；`LANGFUSE_OBSERVATION_FIELD_OVERFLOW_ENABLED` 默认 `false`（溢出处理默认关闭）。→ **生成大文件/长代码时，单个 input/output 字段不得超过 2 MiB**，否则会被截断或拒绝。我们的 `LangfuseObservationFilter` 必须做长度保护。

### 1.3.4 首次初始化、健康检查与升级

**无头初始化**（[Headless Initialization](https://langfuse.com/self-hosting/administration/headless-initialization)）：无需在 UI 点，配好以下环境变量后启动即自动创建资源。依赖顺序是 `Org → Project → API Keys` 与 `Org → User`，**没有 Org 就建不了 Project/User**。

| 变量 | 创建资源是否必需 |
|---|---|
| `LANGFUSE_INIT_ORG_ID` | **必需** |
| `LANGFUSE_INIT_PROJECT_ID` | **必需** |
| `LANGFUSE_INIT_PROJECT_PUBLIC_KEY` | **必需** |
| `LANGFUSE_INIT_PROJECT_SECRET_KEY` | **必需** |
| `LANGFUSE_INIT_USER_EMAIL` | **必需** |
| `LANGFUSE_INIT_USER_PASSWORD` | **必需** |
| `LANGFUSE_INIT_ORG_NAME` / `_PROJECT_NAME` / `_USER_NAME` / `_PROJECT_RETENTION` | 可选 |

> ⚠️ 官方排障：在 docker compose 里用 `LANGFUSE_INIT_*` 时**不要给值加双引号**（[issue #3398](https://github.com/langfuse/langfuse/issues/3398)）。

**健康 / 就绪端点**（[Health and Readiness](https://langfuse.com/self-hosting/configuration/health-readiness-endpoints)）：

| 容器 | 端点 | 说明 |
|---|---|---|
| langfuse-web | `/api/public/health` | 默认**不校验 DB**；加 `?failIfDatabaseUnavailable=true` 才校验。200 正常 / 503 异常 |
| langfuse-web | `/api/public/ready` | 500 = 已收到 SIGTERM/SIGINT，不应再接新流量 |
| langfuse-worker | `:3030/api/health` | 200 正常；可选 `?failIfEventPropagationStuck=true`（阈值 15 分钟）、`?failIfQueueConsumptionStuck=true`（阈值 60 分钟） |

**v4 升级的最低基础设施版本**（必须在升级 server 前完成，[升级指南](https://langfuse.com/self-hosting/upgrade/upgrade-guides/upgrade-v3-to-v4#step-1)）：

| 组件 | 要求 |
|---|---|
| ClickHouse | **最低 25.12，推荐 26.4** |
| PostgreSQL | 最低 15，推荐 16 |
| Redis | 最低 7.0，推荐 7.2 |

→ 官方 compose 默认的 `clickhouse:25.12` / `postgres:17` / `redis:7` **正好满足 v4**，所以**新部署直接用 `:4` 镜像即可，不要先上 v3**（省掉整个 v3→v4 迁移）。

> **升级地狱提示（如果将来真要 v3→v4）**：v4 迁移不是纯增量，会 DROP 被取代的表；升级前必须确认 `SELECT name FROM background_migrations WHERE finished_at IS NULL` **返回 0 行**，否则会**永久丢数据**；且**没有自动降级路径**，回滚要用 v4 镜像里的 `migrate` 二进制把 ClickHouse schema 退到 version 37。

### 1.3.5 ⚠️ ClickHouse 不可重建：备份是硬要求

一手证据（务必重视）：

- 事件是**先落 S3 再入库**：「All incoming tracing and evaluation events are persisted in S3/Blob Storage first. Only after successful processing, the events are written to the database.」（[self-hosting#architecture](https://langfuse.com/self-hosting#architecture)）
- **但没有内置的「从 S3 回灌 ClickHouse」机制**（[issue #10778](https://github.com/langfuse/langfuse/issues/10778)）。
- 维护者原话（[discussion #4763](https://github.com/orgs/langfuse/discussions/4763)）：「Right now, it's **technically possible** (and we've used it manually at times), but **not automated anywhere within Langfuse**.」——只能手动从 S3/日志提取事件再投递到 Redis ingestion queue 重放。

**备份优先级**：

| 数据存储 | 内容 | 丢失后果 | 备份方式 |
|---|---|---|---|
| **Postgres** | 用户/组织/项目/**API key**/prompt/dataset/配置 | **最严重，无法重建** | `pg_dump` 定时任务；官方强烈建议用托管库 |
| **ClickHouse** | 全部 trace/observation/score | 历史链路全丢 | **卷快照（推荐）** / Velero / `BACKUP DATABASE default TO S3(...)` |
| **MinIO / S3** | raw events、多模态媒体 | media 丢失 = trace 中媒体引用永久失效 | `mc replicate` / 卷快照 |
| Redis | 仅队列 + 缓存 | 在途队列 job 丢失 | **官方备份文档没有 Redis 章节** → 指引缺失 |

### 1.3.6 Cloud 免费额度与计费对比

来源：<https://langfuse.com/pricing>

| 计划 | 价格 | 含 units/月 | 数据可访问期 | 用户数 |
|---|---|---|---|---|
| **Hobby** | **免费**，无需信用卡 | **50k units** | 30 天 | 2 |
| Core | $29/月 | 100k，超出 $8/100k | 90 天 | 不限 |
| Pro | $199/月 | 100k，超出 $8/100k | 3 年 | 不限 |
| Enterprise | $2499/月 | 100k，超出 $8/100k | 3 年 | 不限 |
| Teams 附加包 | +$300/月 | — | — | SSO/RBAC |

折扣：早期创业公司首年 5 折；科研/学生最高全免；非营利 $199/月额度；开源项目首年 $300/月额度。

**自托管 vs Cloud 的取舍**：

| 维度 | self-host v4 | Cloud Hobby | Cloud Core |
|---|---|---|---|
| 软件成本 | $0（MIT） | $0 | $29/月 |
| 基础设施成本 | 1 台 4C16G VM（约 $60–120/月不等）+ 运维人力 | 0 | 0 |
| 数据出境 | 不出内网 ✅（本项目有用户代码，重要） | 出网（EU/US/JP 区域可选） | 出网 |
| 数据保留 | 自己管（默认无限增长） | 30 天 | 90 天 |
| 运维复杂度 | 高（6 个容器 + ClickHouse 调优 + 备份） | 无 | 无 |
| 多用户 | 不限 | 2 人 | 不限 |

**对本项目的建议**：AI 代码生成场景的 trace 里包含**用户提交的需求 + 生成的完整代码**，属于敏感业务数据。如果公司没有强合规约束，**先用 Cloud Hobby 做 PoC（50k units 够看效果），正式落地走 self-host**；如果合规要求数据不出内网，直接 self-host。

---

# 2. LangChain4j 的 Langfuse 集成现状（重点查证）

## 2.1 结论：没有官方 Langfuse 模块

**验证方法（可复现）**：拉取 Maven Central 上 `dev.langchain4j` 组目录列表（241+ 个 artifact），过滤 `langfuse`：

```bash
curl -sL https://repo1.maven.org/maven2/dev/langchain4j/ \
  | grep -oE 'href="[^"]+/"' | sed 's/href="//;s|/"||' | grep -i langfuse
# 输出为空
```

`dev.langchain4j` 下与可观测性相关的 artifact 只有：

| artifact | 说明 |
|---|---|
| `langchain4j-observation` | **官方 Micrometer Observation 集成模块**（本报告主角） |
| `langchain4j-spring-boot-tests` / `langchain4j-spring-boot4-tests` / `langchain4j-test` | 测试用，与观测无关 |

**没有 `langchain4j-langfuse`、没有 `langchain4j-otel`、没有 `langchain4j-tracing`。**

> 参考：LangChain4j 仓库 issue [#2328 "Langfuse: implement tracing"](https://github.com/langchain4j/langchain4j/issues/2328) 存在（由 web_search 返回），但 **issue 的当前状态未验证**（github.com 在本环境被 DNS 污染为假 IP，无法抓取 issue 页面正文）。

## 2.2 Langfuse 官方给 Java 的答案：OTel

Langfuse 官方 Java API 客户端 `com.langfuse:langfuse-java` **0.3.0**（Maven Central 已核对，最新版 `0.3.0`，2026-08-24 发布）在 README 里写得很直白：

> **OpenTelemetry is the recommended — and, going forward, the only supported — way to send tracing data to Langfuse from Java.**
> This client no longer exposes the legacy ingestion API; it is deprecated and is removed in Langfuse v4.
> Use this client for everything that is *not* tracing ingestion: prompts, datasets, scores, models, comments, annotation queues, and the reading APIs.

来源：<https://github.com/langfuse/langfuse-java>

```xml
<!-- 仅用于 API 读写（prompt / dataset / score / 查询），不是追踪 SDK -->
<dependency>
    <groupId>com.langfuse</groupId>
    <artifactId>langfuse-java</artifactId>
    <version>0.3.0</version>
</dependency>
```

```java
import com.langfuse.client.LangfuseClient;

LangfuseClient client = LangfuseClient.builder()
        .url("https://cloud.langfuse.com")     // 或 http://langfuse-web:3000
        .credentials("pk-lf-...", "sk-lf-...")
        .build();
```

> 报告里如果要做「把离线评估结果写回 Langfuse 当 score」这类需求，就用这个客户端；**追踪不要用它**。

## 2.3 最接近「官方集成」的东西：Quarkus LangChain4j 扩展（不能直接用）

Langfuse 官方有一页专门的 Quarkus LangChain4j 集成文档：<https://langfuse.com/integrations/frameworks/quarkus-langchain4j>。

它依赖 **Quarkus 自己的 LangChain4j OTel 埋点**：

```properties
# Quarkus 专属，Spring Boot 用不了
quarkus.otel.exporter.otlp.endpoint=https://cloud.langfuse.com/api/public/otel
quarkus.otel.exporter.otlp.headers=Authorization=Basic <base64 of public:key>,x-langfuse-ingestion-version=4
quarkus.otel.exporter.otlp.traces.protocol=http/protobuf
quarkus.langchain4j.tracing.include-prompt=true
quarkus.langchain4j.tracing.include-completion=true
```

文档说明这些 span 携带 `gen_ai.operation.name`、`gen_ai.system`、模型名、token 用量等属性。

**对本项目的价值**：不能直接复用（Quarkus/CDI 扩展），但它是**同一套 GenAI 语义约定的参考实现**——说明「LangChain4j 调用 → gen_ai.* span → Langfuse」这条链路是被 Langfuse 官方验证过的，只是 Quarkus 帮你做了埋点，Spring Boot 要自己做。

## 2.4 LangChain4j 自身的可观测性 API（1.18.1 已具备）

LangChain4j 1.x 有两条埋点通道（源码级已核对 `langchain4j-core-1.18.1-sources.jar`）：

### (a) `ChatModelListener`（本项目正在用）

```java
public interface ChatModelListener {
    default void onRequest(ChatModelRequestContext requestContext) {}
    default void onResponse(ChatModelResponseContext responseContext) {}
    default void onError(ChatModelErrorContext errorContext) {}
}
```

- `ChatModelRequestContext` 提供 `chatRequest()`、`modelProvider()`、**`attributes()`（`Map<Object,Object>`，跨回调传递数据）**。
- **流式模型同样触发**：`StreamingChatModel.chat(ChatRequest, ChatRequestOptions, StreamingChatResponseHandler)` 的默认实现里，先 `onRequest(...)`，再 `doChat(...)`，并把 `handler` 包一层，在 `onCompleteResponse` 前调用 `onResponse(...)`、在 `onError` 前调用 `onError(...)`（`StreamingChatModel.java` 源码已核对）。
- **`ChatRequestOptions.listenerAttributes`**（1.13.0 起）：调用方能往 `attributes` 里塞自定义数据 → 这是传递 `taskId` 等业务字段的官方通道。

### (b) AI Service Observability API（实验性）

`dev.langchain4j.observability.api` 包提供 `AiServiceStartedListener` / `AiServiceRequestIssuedListener` / `AiServiceResponseReceivedListener` / `AiServiceErrorListener` / `AiServiceCompletedListener` / `ToolExecutedEventListener` / guardrail 相关 listener，通过 `AiServices.builder(...).registerListener(...)` 注册，事件里带 `InvocationContext`（`invocationId`、`interfaceName`、`methodName`、`methodArguments`、`chatMemoryId`、`timestamp`）。

来源：<https://docs.langchain4j.dev/tutorials/observability>（文档明确标注 "AI Service observability is an experimental feature. Its API and behavior may change in future versions."）

**对本项目的价值**：`AiServiceRequestIssuedListener` / `AiServiceCompletedListener` 非常适合做**业务级 span**（一个 `AiService` 方法 = 一个 span），比 `ChatModelListener` 更贴近"业务链路"。但它是实验性 API，且不能用于裸 `ChatModel`/`StreamingChatModel`。

## 2.5 ⭐ `dev.langchain4j:langchain4j-observation`——被忽略的官方集成

这是本次调研**最有价值的发现**。

### 2.5.1 Maven 坐标与版本对齐

```
groupId:    dev.langchain4j
artifactId: langchain4j-observation
versions:   1.12.1-beta21 … 1.20.0-beta30（共 21 个版本）
```

**与本项目完全对齐的版本：`1.18.1-beta28`**

```
https://repo1.maven.org/maven2/dev/langchain4j/langchain4j-observation/1.18.1-beta28/
```

其 POM 依赖（已核对）：

| 依赖 | 版本 |
|---|---|
| `dev.langchain4j:langchain4j-core` | **1.18.1** ← 与本项目 `${langchain4j.version}` 完全一致 |
| `io.micrometer:micrometer-core` | 1.16.5 |
| `io.micrometer:micrometer-commons` | 1.16.5 |
| `io.micrometer:micrometer-observation` | 1.16.5 |

> 我同时下载了 `1.18.1-beta28` 与 `1.20.0-beta30` 的 sources jar 做 diff：`ObservationChatModelListener.java`、`DefaultChatModelConvention.java`、`ChatModelDocumentation.java` **完全一致（IDENTICAL）**，所以行为描述对两个版本都成立。

### 2.5.2 它做了什么（源码级）

`dev.langchain4j.observation.listener.ObservationChatModelListener implements ChatModelListener`：

```java
// 1.18.1-beta28 源码（节选，逐字来自 sources jar）
public class ObservationChatModelListener implements ChatModelListener {

    private static final String OBSERVATION_SCOPE_KEY = "micrometer.observation.scope";
    static final String TOKEN_USAGE = "gen_ai.client.token.usage";

    public ObservationChatModelListener(ObservationRegistry observationRegistry,
                                        MeterRegistry meterRegistry) {
        this.observationRegistry = observationRegistry;
        tokenDistribution = DistributionSummary.builder(TOKEN_USAGE)
                .description("Measures the quantity of used tokens")
                .baseUnit("tokens")
                .tag("gen_ai.operation.name", "chat")
                .withRegistry(meterRegistry);
        chatModelConvention = null;          // ← 注意：无法注入自定义 convention
    }

    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        Observation onRequest = ChatModelDocumentation.INSTANCE.start(
                this.chatModelConvention,
                new DefaultChatModelConvention(),
                () -> new ChatModelObservationContext(requestContext, null, null),
                observationRegistry);
        Observation.Scope scope = onRequest.openScope();
        requestContext.attributes().put(OBSERVATION_SCOPE_KEY, scope);
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        Observation.Scope currentScope =
                (Observation.Scope) responseContext.attributes().remove(OBSERVATION_SCOPE_KEY);
        if (currentScope == null) return;
        Observation observation = currentScope.getCurrentObservation();
        try {
            // ... 记录 token 的 DistributionSummary 指标 ...
        } finally {
            currentScope.close();
            observation.stop();     // ← span 在这里结束
        }
    }
}
```

产生的 span 属性（来自 `DefaultChatModelConvention` + `ChatModelDocumentation`）：

| 属性 | 值 | 基数 |
|---|---|---|
| `gen_ai.operation.name` | `chat` | low |
| `gen_ai.provider.name` | 如 `OPEN_AI`（`ModelProvider.name()`） | low |
| `gen_ai.request.model` | 请求模型名 | low |
| `gen_ai.response.model` | 响应模型名 | low |
| `outcome` | `SUCCESS` / `ERROR` | low |
| `input_tokens` / `output_tokens` | token 数（**注意：没有 `gen_ai.usage.` 前缀**） | high |

span 名称：`DefaultChatModelConvention.getName()` 返回 `gen_ai.client.operation.duration`，`getContextualName()` 返回 `"chat " + modelName`；最终 span 名由 `TracingObservationHandler.getSpanName()` 走 `SpanNameUtil.toLowerHyphen(contextualName)` → 形如 **`chat-gpt-4o`**。

另外它注册了一个 Micrometer 指标 `gen_ai.client.token.usage`（DistributionSummary），会进你现有的 Prometheus——**这正好能和现有 Grafana 面板互补**。

### 2.5.3 零改造接入（对本项目的精确结论）

`langchain4j-open-ai-spring-boot-starter:1.18.0-beta28` 的 `AutoConfig` 源码里：

```java
@Bean
@ConditionalOnProperty(PREFIX + ".chat-model.api-key")
OpenAiChatModel openAiChatModel(..., ObjectProvider<ChatModelListener> listeners) {
    return OpenAiChatModel.builder()
            ...
            .listeners(listeners.orderedStream().toList())
            .build();
}
// 流式模型同理：openAiStreamingChatModel(..., ObjectProvider<ChatModelListener> listeners)
```

**对本项目的精确影响（实际代码已核对）**：

| 模型 | 产生方式 | 是否需要改代码 |
|---|---|---|
| `openAiChatModel` | starter 的 `AutoConfig` 自动装配（`@Resource(name = "openAiChatModel")` 注入处） | ❌ **不用改**，新增的 `ObservationChatModelListener` bean 会被 `ObjectProvider<ChatModelListener>` 自动挂上 |
| `openAiStreamingChatModel` | 同上 | ❌ **不用改** |
| `routingChatModelPrototype()`（`config/RoutingAiModelConfig.java`） | **手写** `OpenAiChatModel.builder()`，**没有 `.listeners(...)`** | ⚠️ **需要手工加一行** `.listeners(observationChatModelListener)`（并把该 bean 注入这个 `@Configuration`） |

> 本项目里 `AiServices.builder(...)` 的 6 处（`CodeQualityCheckServiceFactory`、`ImageCollectionPlanServiceFactory`、`ImageCollectionServiceFactory`、`AiCodeGenTypeRoutingServiceFactory`、`AiCodeGeneratorServiceFactory` ×2）都只是**引用**注入的模型，不需要改动——listener 挂在模型上，不在 AiService 上。
>
> 另外注意 `routingChatModelPrototype` 是 `@Scope("prototype")`，注入配置类后每次取新实例都不会有问题，因为 `ObservationChatModelListener` 本身是无状态、线程安全的。

---

# 3. 通用方案：OpenTelemetry 接入（推荐）

## 3.1 Langfuse 的 OTLP 端点与鉴权

来源：<https://langfuse.com/integrations/native/opentelemetry>

```
# 基础端点（泛化）
OTEL_EXPORTER_OTLP_ENDPOINT="https://cloud.langfuse.com/api/public/otel"        # EU
#   US: https://us.cloud.langfuse.com/api/public/otel
#   JP: https://jp.cloud.langfuse.com/api/public/otel
#   HIPAA: https://hipaa.cloud.langfuse.com/api/public/otel
#   本地 self-host (>= v3.22.0): http://localhost:3000/api/public/otel

# 信号专用端点（traces）
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT="https://cloud.langfuse.com/api/public/otel/v1/traces"

# 鉴权 + v4 实时摄取
OTEL_EXPORTER_OTLP_HEADERS="Authorization=Basic ${AUTH_STRING},x-langfuse-ingestion-version=4"
# AUTH_STRING = base64("pk-lf-...:sk-lf-...")
#   echo -n "pk-lf-1234567890:sk-lf-1234567890" | base64      # macOS BSD base64
#   echo -n "pk-lf-1234567890:sk-lf-1234567890" | base64 -w0  # GNU
```

硬性约束：

1. **Langfuse 只支持 OTLP over HTTP（HTTP/protobuf 与 HTTP/JSON），不支持 gRPC** —— 原文："Please note that Langfuse currently supports OTLP over HTTP with both HTTP/JSON and HTTP/protobuf. gRPC is not supported yet."
2. **`x-langfuse-ingestion-version: 4` 建议必带**：不带的话，直接 OTLP 摄取的数据在 v4 数据模型上**最多延迟 10 分钟**才可见（不会丢，但排查体验极差）。

### ⚠️ 端点路径语义（极易踩坑，已源码验证）

OTel Java SDK 对"泛化端点"和"信号专用端点"的处理**不一样**：

```java
// opentelemetry-java v1.49.0
// exporters/otlp/all/src/main/java/io/opentelemetry/exporter/otlp/internal/OtlpConfigUtil.java
URL endpoint = validateEndpoint(config.getString("otel.exporter.otlp." + dataType + ".endpoint"), isHttpProtobuf);
if (endpoint != null) {
    if (endpoint.getPath().isEmpty()) endpoint = createUrl(endpoint, "/");     // 信号专用：原样用
} else {
    endpoint = validateEndpoint(config.getString("otel.exporter.otlp.endpoint"), isHttpProtobuf);
    if (endpoint != null && isHttpProtobuf) {
        String path = endpoint.getPath();
        if (!path.endsWith("/")) path += "/";
        path += signalPath(dataType);            // 泛化：自动补 "v1/traces"
    }
}
// signalPath("traces") -> "v1/traces"
```

而 Programmatic 的 `OtlpHttpSpanExporter.builder().setEndpoint(...)`（**Spring Boot 走的就是这条**）javadoc 明确说：

> Sets the OTLP endpoint to connect to. If unset, defaults to `http://localhost:4318/v1/traces`. The endpoint must start with either http:// or https://, **and include the full HTTP path.**

**结论表**：

| 配置方式 | 应该填什么 |
|---|---|
| `OTEL_EXPORTER_OTLP_ENDPOINT`（泛化，容器/agent 常用） | `http://langfuse-web:3000/api/public/otel` ← **不要**带 `/v1/traces` |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`（信号专用） | `http://langfuse-web:3000/api/public/otel/v1/traces` |
| **Spring Boot `management.otlp.tracing.endpoint`** | `http://langfuse-web:3000/api/public/otel/v1/traces` ← **必须**带完整路径 |

## 3.2 方案 A：OTel Java Agent（零代码，但价值有限）

```bash
java -javaagent:/path/opentelemetry-javaagent.jar \
     -Dotel.service.name=yu-ai-code-mother \
     -Dotel.exporter.otlp.endpoint=http://langfuse-web:3000/api/public/otel \
     -Dotel.exporter.otlp.protocol=http/protobuf \
     -Dotel.exporter.otlp.headers="Authorization=Basic ${AUTH},x-langfuse-ingestion-version=4" \
     -jar app.jar
```

- 最新 Java Agent：`io.opentelemetry.javaagent:opentelemetry-javaagent:2.31.1`（Maven Central 已核对）。
- **它不会给 LangChain4j 生成 gen_ai span**：`opentelemetry-java-instrumentation` 里没有 LangChain4j 的 instrumentation 模块，你得到的是 Spring MVC / JDBC / HTTP client 之类的通用 span。这些 span 会作为普通 `SPAN` 类型进入 Langfuse（因为不带 `gen_ai.operation.name`、也没有 model 属性 → 按 Langfuse 的 `ObservationTypeMapper` 落到默认 `SPAN`），**没有成本、没有 token、没有 prompt/completion**。
- 有一个可用的补充：`io.opentelemetry.instrumentation:opentelemetry-micrometer-1.5`（Maven Central 已核对，最新 `2.31.1-alpha`）是 Java Agent 的 Micrometer 桥接扩展，理论上能让 Agent 自动接管 Micrometer Observation。**未验证**：它是否与 Spring Boot 3.5 的 OTLP exporter 共存、是否会和 SDK 方案重复出 span。

**建议**：如果你只是想要「HTTP 入口 → LLM 调用」的粗略链路，Agent 可以；但既然要**成本 / token / prompt 级**洞察，Agent 不够，必须走下面这条路。

## 3.3 方案 B：Micrometer Observation → OTel → OTLP（**推荐的落地方案**）

### 3.3.1 依赖

```xml
<!-- pom.xml -->
<properties>
    <langchain4j-observation.version>1.18.1-beta28</langchain4j-observation.version>
</properties>

<dependencies>
    <!-- 1) LangChain4j 官方 Observation 集成：把 ChatModelListener 变成 Micrometer Observation -->
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-observation</artifactId>
        <version>${langchain4j-observation.version}</version>
    </dependency>

    <!-- 2) Spring Boot Actuator：提供 ObservationRegistry 自动装配 + tracing 自动装配 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>

    <!-- 3) Micrometer Observation → OpenTelemetry 桥（版本由 spring-boot-dependencies 管理：1.5.2） -->
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-tracing-bridge-otel</artifactId>
    </dependency>

    <!-- 4) OTLP exporter（版本由 opentelemetry-bom 管理：1.49.0） -->
    <dependency>
        <groupId>io.opentelemetry</groupId>
        <artifactId>opentelemetry-exporter-otlp</artifactId>
    </dependency>
</dependencies>
```

已在 `spring-boot-dependencies:3.5.4` 的 POM 中核对：

| 属性 | 值 | 说明 |
|---|---|---|
| `micrometer.version` | 1.15.2 | `micrometer-bom` import |
| `micrometer-tracing.version` | 1.5.2 | `micrometer-tracing-bom` import → 覆盖 `micrometer-tracing-bridge-otel` |
| `opentelemetry.version` | 1.49.0 | `opentelemetry-bom` import → 覆盖 `opentelemetry-exporter-otlp` |

> 注意：`opentelemetry-exporter-otlp-http-trace` 这类细分 artifact 在 Maven Central 上止步于 **1.14.0**；**从某个版本起 OTLP HTTP trace 的实现类被打包进 `io.opentelemetry:opentelemetry-exporter-otlp` 聚合 artifact**（已下载 `1.49.0` jar 确认里面含 `io/opentelemetry/exporter/otlp/http/trace/OtlpHttpSpanExporter.class`）。所以**不要**照抄网上的旧教程去引 `opentelemetry-exporter-otlp-http-trace`。

### 3.3.2 application.yml

```yaml
spring:
  application:
    name: yu-ai-code-mother        # 会作为 service.name 进 Langfuse 的 metadata.resourceAttributes

server:
  port: 8101

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,info
  tracing:
    enabled: true
    sampling:
      probability: 1.0             # 默认只采样 10%，按需调整
  otlp:
    tracing:
      # ⚠️ 必须带完整路径 /v1/traces（Spring Boot 用 setEndpoint()，不会自动补）
      endpoint: ${LANGFUSE_OTLP_ENDPOINT:http://langfuse-web:3000/api/public/otel/v1/traces}
      transport: http               # 默认 http；Langfuse 不支持 grpc
      compression: gzip
      timeout: 10s
      connect-timeout: 5s
      headers:
        Authorization: "Basic ${LANGFUSE_AUTH_BASE64}"
        x-langfuse-ingestion-version: "4"
  observations:
    enable:
      # 关掉 Micrometer 自带的 HTTP server/client observation，避免和 OTLP 里的 HTTP span 重复
      http.server.requests: false
      http.client.requests: false
```

`management.otlp.tracing.*` 与 `management.observations.enable.*` 属性名已从 Spring Boot 3.5 的 application properties 附录中逐一核对：`management.otlp.tracing.{endpoint,headers,transport,compression,timeout,connect-timeout,export.enabled}`、`management.observations.enable`。

`LANGFUSE_AUTH_BASE64` 生成：

```bash
export LANGFUSE_AUTH_BASE64=$(printf '%s' "pk-lf-xxx:sk-lf-yyy" | base64)
```

放在 docker compose 的 `environment:` 里即可（**不要**用 `OTEL_EXPORTER_OTLP_*` 环境变量去覆盖 Spring Boot 的 `management.otlp.tracing.endpoint`——两者都会生效且路径语义不同，容易互相打架；二选一）。

### 3.3.3 注册 ObservationChatModelListener（3 行）

```java
package com.yupi.yuaicodemother.monitor;

import dev.langchain4j.observation.listener.ObservationChatModelListener;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiObservabilityConfig {

    /**
     * 被 langchain4j-open-ai-spring-boot-starter 的 AutoConfig 自动注入到
     * ChatModel 和 StreamingChatModel 的 listeners 列表里（ObjectProvider<ChatModelListener>）。
     * 无需修改任何模型构造代码。
     */
    @Bean
    public ObservationChatModelListener observationChatModelListener(
            ObservationRegistry observationRegistry, MeterRegistry meterRegistry) {
        return new ObservationChatModelListener(observationRegistry, meterRegistry);
    }
}
```

### 3.3.4 为什么 span 会自动发到 Langfuse（机制说明）

1. `spring-boot-starter-actuator` 自动装配 `ObservationRegistry`；`MicrometerTracingAutoConfiguration` 注册 `DefaultTracingObservationHandler`（源码已核对 `spring-boot-actuator-autoconfigure:3.5.4`）。
2. `ObservationChatModelListener` 在 `onRequest` 里 `Observation.start()`，在 `onResponse` 里 `observation.stop()`。
3. `SimpleObservation.stop()` 依次执行：**先补 convention 的 key-value → 再跑 ObservationFilter → 最后通知 handlers**（`micrometer-observation:1.15.2` 的 `SimpleObservation.stop()` 源码已逐行核对）。
4. `DefaultTracingObservationHandler.onStop()` → `tagSpan(context, span)`：

```java
// micrometer-tracing:1.5.2  TracingObservationHandler
default void tagSpan(T context, Span span) {
    for (KeyValue keyValue : context.getAllKeyValues()) {   // ← low + high 基数全都要
        if (!keyValue.getKey().equalsIgnoreCase("ERROR")) span.tag(keyValue.getKey(), keyValue.getValue());
        else span.error(new RuntimeException(keyValue.getValue()));
    }
}
```

**这一步很关键**：Micrometer 的 **high-cardinality key value 也会写进 OTel span attribute**（不是只进指标），所以我们可以用 `ObservationFilter` 往 span 上塞 `langfuse.*` 属性。

5. span 由 OTel SDK 创建 → `otlp` exporter → `POST /api/public/otel/v1/traces` → Langfuse。

## 3.4 方案 C：直接调 Langfuse Ingestion API（**不推荐**，仅作兜底/存量兼容）

`POST /api/public/ingestion`，Basic Auth，请求体：

```json
{
  "batch": [
    {
      "id": "abcdef-1234-5678-90ab",
      "timestamp": "2026-01-01T00:00:00.000Z",
      "type": "trace-create",
      "body": {
        "id": "abcdef-1234-5678-90ab",
        "timestamp": "2026-01-01T00:00:00.000Z",
        "environment": "production",
        "name": "My Trace",
        "userId": "1234",
        "input": "My input",
        "output": "My output",
        "sessionId": "1234",
        "release": "1.0.0"
      }
    }
  ]
}
```

规格要点（逐字来自 [ingestion.yml](https://raw.githubusercontent.com/langfuse/langfuse/main/fern/apis/server/definition/ingestion.yml)）：

- 事件类型：`trace-create` / `span-create` / `span-update` / `generation-create` / `generation-update` / `event-create` / `score-create` / `sdk-log` 等；`batch` 内多条事件，按 `type` 判别。
- `event.id` 是**去重用的信封 ID**，必须唯一；`event.body.id` 才是实体 ID（同一 body.id + 不同 event.id = 更新）。
- **单批总量上限 3.5 MB**。
- **输入错误不返回 4xx**，而是返回 **HTTP 207**，响应体里带 error 列表 —— 客户端必须解析 207 body，否则会静默丢数据。
- `availability.status: deprecated`，官方建议："If you use custom auto-instrumentation, only then send data via the OpenTelemetry endpoint (`POST /api/public/otel/v1/traces`)"。

**deprecation 时间线**：Cloud 2026-11-16 起 v4-only，**trace/observation 事件被拒绝**；self-host 在 v4-only write 模式下同样拒绝；**`score-create` 与 `sdk-log` 事件长期保留**。

> 结论：**新项目一律走 OTel**。只有在「OTel 完全不可用 + 必须自研」的极端场景才考虑它，且要接受 2026-11-16 之后失效的风险。

---

# 4. 流式输出（streaming）场景的注意事项

## 4.1 LangChain4j 流式链路的事实（源码级）

1. **listener 一定会被触发**：`StreamingChatModel.chat(...)` 默认实现中，`onRequest` 在调用前触发，`onResponse` 在 `handler.onCompleteResponse(completeResponse)` **之前**触发，`onError` 同理。触发的 `ChatResponse` 是**完整聚合后的响应**（不是增量）。
2. **流式也能拿到 token 用量**：`OpenAiStreamingChatModel.doChat()` 里显式设置了 `streamOptions(StreamOptions.builder().includeUsage(true).build())`（1.18.1 源码），所以最终 `ChatResponse.metadata().tokenUsage()` 有值。`ObservationChatModelListener.onResponse` 也正是从这里取 input/output token。
3. **输出内容也能拿到**：`langchain4j-observation` 在 `onResponse` 才能拿到完整文本（流式期间没有回调给 listener）。所以 **`langfuse.observation.output` 只能在流结束时写一次**——Langfuse 的 observation 是"完成时写入"的模型，这完全没问题。

## 4.2 跨线程风险（本项目必须注意）

`ObservationChatModelListener` 在 `onRequest` 里 `openScope()`，在 `onResponse` 里 `scope.close()`。而 Micrometer 的"当前 observation/scope"是 **ThreadLocal**：

```java
// micrometer-observation:1.15.2  SimpleObservationRegistry
private static final ThreadLocal<Observation.Scope> localObservationScope = new ThreadLocal<>();
```

**流式场景下 `onRequest` 和 `onResponse` 很可能不在同一个线程**（你的 `AiModelMonitorListener` 注释里已经写了"请求和响应事件的触发不是同一个线程"，说明你们已经踩过）。`SimpleScope.close()` 不会抛异常，但它会执行 `this.registry.setCurrentObservationScope(previousObservationScope)`——把**线程 A 的父 scope 设置成线程 B 的当前 scope**。如果线程 B 是共享的 HTTP 回调线程池线程，理论上可能造成 scope 串扰。

**必须实测验证的项**：
- 流式下 LLM span 是否正确挂在业务 root span 之下（parent 是在 `onRequest` 那一刻决定的，`onRequest` 在调用线程 → **parent 通常是对的**）。
- 长连接/共享回调线程池下是否出现 span 归属错乱或 `Observation` 泄漏。

**缓解方案（按优先级）**：
1. 引入 Micrometer Context Propagation（`io.micrometer:context-propagation` + Spring Boot 的 `ContextPropagation`），用 `ContextSnapshot`/`ContextExecutorService` 显式传播上下文；
2. 自己写一个 StreamChat 专用 listener，**不 `openScope()`**，只 `Observation.createNotStarted(...).start()` / `.stop()`（span 的 parent 由 `start()` 时的当前 span 决定，不需要 openScope）；
3. 先上、观察日志，再决定。

> 另外，LangChain4j 的 `ChatRequestOptions.listenerAttributes` 会被**复制**进 `attributes` map（`new ConcurrentHashMap<>(effectiveOptions.listenerAttributes())`），但**值对象是共享引用**——所以传一个可变 holder（如 `AtomicLong`）是安全的，这是下面 TTFT 方案的基础。

## 4.3 TTFT / 首 token 延迟

Langfuse 的 observation 支持 `langfuse.observation.completion_start_time`（ISO 8601 字符串，见 [属性映射表](https://langfuse.com/integrations/native/opentelemetry#property-mapping)），Langfuse 会据此计算 **Time to First Token**（Metrics API v1 里就有 `timeToFirstToken` 指标）。

**问题**：`langchain4j-observation` 不测 TTFT，`ChatModelListener` 也没有"首个 token"回调。

**可行方案**：用 `ChatRequestOptions.listenerAttributes` 传一个可变 holder，在调用方自己的 `StreamingChatResponseHandler` 包装器里记录首 token 时间，再由 `ObservationFilter` 在 span 结束时写进属性。

```java
// 1) holder
public final class TtftHolder {
    private final AtomicLong firstTokenAtMillis = new AtomicLong(0);
    public void mark() { firstTokenAtMillis.compareAndSet(0, System.currentTimeMillis()); }
    public long get() { return firstTokenAtMillis.get(); }
}

// 2) 调用方：包一层 handler，首个 partial 时打点
TtftHolder ttft = new TtftHolder();
ChatRequestOptions options = ChatRequestOptions.builder()
        .addListenerAttribute("ttftHolder", ttft)
        .addListenerAttribute("taskId", taskId)          // 业务字段，见 §5
        .build();

streamingChatModel.chat(request, options, new StreamingChatResponseHandler() {
    @Override public void onPartialResponse(String partial) {
        ttft.mark();                                     // ← 首 token
        upstream.onPartialResponse(partial);             // 你自己的 SSE 下发
    }
    @Override public void onCompleteResponse(ChatResponse response) { upstream.onCompleteResponse(response); }
    @Override public void onError(Throwable error) { upstream.onError(error); }
});

// 3) ObservationFilter 在 span 结束时写入（见 §5.2 完整实现）
```

---

# 5. 业务字段关联与自定义埋点

## 5.1 Langfuse 认哪些属性（逐字核对）

来源：<https://langfuse.com/integrations/native/opentelemetry#property-mapping>（表格由页面原始 HTML 解析，非二手转述）

### Trace 级属性（可写在任意 span 上，会变成整条 trace 的属性）

| Langfuse 字段 | 映射自 OTel 属性 |
|---|---|
| `name` | `langfuse.trace.name` / root span 的 span name |
| `userId` | `langfuse.user.id` 或 `user.id` |
| `sessionId` | `langfuse.session.id` 或 `session.id` |
| `release` | `langfuse.release` |
| `public` | `langfuse.trace.public` |
| `tags` | `langfuse.trace.tags`（string[]） |
| `metadata` | `langfuse.trace.metadata.*` |
| `input` / `output` | **v4 已废弃**（改为用 root observation 的 input/output） |
| `version` / `environment` | 从 root span 的属性映射 |

### Observation 级属性

| Langfuse 字段 | 映射自 OTel 属性 |
|---|---|
| `type` | `langfuse.observation.type`（`span`/`generation`/`event`/`embedding`/`agent`/`tool`/`chain`/`retriever`/`guardrail`/`evaluator`）；未设置时按 `gen_ai.operation.name` 推断 |
| `level` / `statusMessage` | `langfuse.observation.level`（DEBUG/DEFAULT/WARNING/ERROR）、`langfuse.observation.status_message` |
| `metadata` | `langfuse.observation.metadata.*` |
| **`input`** | **`langfuse.observation.input`**（JSON 字符串）/ `gen_ai.prompt` |
| **`output`** | **`langfuse.observation.output`**（JSON 字符串）/ `gen_ai.completion` |
| `model` | `langfuse.observation.model.name` / `gen_ai.request.model` / `gen_ai.response.model` |
| `modelParameters` | `langfuse.observation.model.parameters`（JSON）/ `gen_ai.request.*` |
| **`usage`** | **`langfuse.observation.usage_details`**（JSON）/ **`gen_ai.usage.*`** |
| `cost` | `langfuse.observation.cost_details` / `gen_ai.usage.cost` |
| `prompt` | `langfuse.observation.prompt.name` / `.prompt.version` |
| **`completionStartTime`** | **`langfuse.observation.completion_start_time`**（ISO 8601） |

### 关于 metadata 的一个大坑

- `langfuse.trace.metadata.<key>` → 进入 trace `metadata` 的**第一层，可过滤/可聚合**。
- `langfuse.observation.metadata.<key>` → 进入 observation `metadata` 第一层。
- **其他任何没加前缀的 OTel 属性** → 被塞进 `metadata.attributes` 这个 catch-all 里，**不能在 UI 里过滤**（resource attributes 进 `metadata.resourceAttributes`）。

  → 所以业务字段一定要用前缀显式映射。

## 5.2 用 ObservationFilter 给 LLM span 补 input/output/usage（完整可运行代码）

```java
package com.yupi.yuaicodemother.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.observation.context.ChatModelObservationContext;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 LangChain4j 的 ChatModel 信息翻译成 Langfuse 认识的 OTel 属性。
 * 运行时机：SimpleObservation.stop() 内、convention 补属性之后、tracing handler 打 tag 之前（已源码核对）。
 */
@Component
public class LangfuseObservationFilter implements ObservationFilter {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Observation.Context map(Observation.Context context) {
        if (!(context instanceof ChatModelObservationContext cm)) {
            return context;
        }
        try {
            addInput(cm);
            addOutputAndUsage(cm);
            addTtft(cm);
            addBusinessAttributes(cm);
        } catch (Exception ignored) {
            // 监控绝不能影响主流程
        }
        return context;
    }

    private void addInput(ChatModelObservationContext cm) {
        ChatModelRequestContext req = cm.getRequestContext();
        if (req == null) return;
        List<Map<String, String>> messages = new ArrayList<>();
        for (ChatMessage m : req.chatRequest().messages()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("role", roleOf(m));          // user / system / assistant / tool
            item.put("content", clip(textOf(m))); // ← 必须截断，Langfuse 单字段上限 2 MiB
            messages.add(item);
        }
        cm.addHighCardinalityKeyValue(
                KeyValue.of("langfuse.observation.input", clip(toJson(messages))));
    }

    /** ChatMessageType 是枚举（SYSTEM/USER/AI/TOOL_EXECUTION_RESULT/CUSTOM），转成 Langfuse 惯用的角色名 */
    private String roleOf(ChatMessage m) {
        return switch (m.type()) {
            case SYSTEM -> "system";
            case USER -> "user";
            case AI -> "assistant";
            case TOOL_EXECUTION_RESULT -> "tool";
            case CUSTOM -> "custom";
        };
    }

    private String textOf(ChatMessage m) {
        return switch (m) {
            case SystemMessage s -> s.text();
            case UserMessage u -> u.singleText();
            case AiMessage a -> a.text();
            case ToolExecutionResultMessage t -> t.text();
            default -> String.valueOf(m);
        };
    }

    private void addOutputAndUsage(ChatModelObservationContext cm) {
        ChatModelResponseContext resp = cm.getResponseContext();
        if (resp == null || resp.chatResponse() == null) return;

        if (resp.chatResponse().aiMessage() != null) {
            cm.addHighCardinalityKeyValue(KeyValue.of(
                    "langfuse.observation.output",
                    clip(toJson(Map.of("role", "assistant",
                                       "content", String.valueOf(resp.chatResponse().aiMessage().text()))))));
        }

        TokenUsage usage = resp.chatResponse().tokenUsage();
        if (usage != null) {
            // ⚠️ 必须用 gen_ai.usage.* 前缀：langchain4j-observation 自己写的裸
            //    input_tokens/output_tokens 不在 Langfuse 的映射表里，等于白写
            if (usage.inputTokenCount() != null) {
                cm.addHighCardinalityKeyValue(KeyValue.of(
                        "gen_ai.usage.input_tokens", String.valueOf(usage.inputTokenCount())));
            }
            if (usage.outputTokenCount() != null) {
                cm.addHighCardinalityKeyValue(KeyValue.of(
                        "gen_ai.usage.output_tokens", String.valueOf(usage.outputTokenCount())));
            }
        }
    }

    /** 流式场景：从 ChatRequestOptions.listenerAttributes 传进来的 holder 取首 token 时间 */
    private void addTtft(ChatModelObservationContext cm) {
        ChatModelRequestContext req = cm.getRequestContext();
        if (req == null) return;
        Object holder = req.attributes().get("ttftHolder");
        if (holder instanceof TtftHolder t && t.get() > 0) {
            cm.addHighCardinalityKeyValue(KeyValue.of(
                    "langfuse.observation.completion_start_time",
                    Instant.ofEpochMilli(t.get()).toString()));   // ISO-8601
        }
    }

    /** 业务字段：userId / appId / taskId / 生成类型 */
    private void addBusinessAttributes(ChatModelObservationContext cm) {
        ChatModelRequestContext req = cm.getRequestContext();
        if (req == null) return;
        Map<Object, Object> attrs = req.attributes();

        // 方式一：调用方通过 ChatRequestOptions.addListenerAttribute 传进来（最可靠，流式/异步都安全）
        put(cm, "langfuse.trace.metadata.taskId",     attrs.get("taskId"));
        put(cm, "langfuse.trace.metadata.appId",      attrs.get("appId"));
        put(cm, "langfuse.trace.metadata.genType",    attrs.get("genType"));
        put(cm, "langfuse.trace.metadata.nodeName",   attrs.get("nodeName")); // LangGraph4j 节点名
        put(cm, "langfuse.user.id",                   attrs.get("userId"));
        put(cm, "langfuse.session.id",                attrs.get("sessionId"));
    }

    private void put(ChatModelObservationContext cm, String key, Object value) {
        if (value != null) {
            cm.addHighCardinalityKeyValue(KeyValue.of(key, String.valueOf(value)));
        }
    }

    private String toJson(Object o) {
        try { return mapper.writeValueAsString(o); } catch (Exception e) { return String.valueOf(o); }
    }

    /** Langfuse 单字段上限 2 MiB（LANGFUSE_OBSERVATION_FIELD_SIZE_LIMIT_BYTES），这里保守截到 256K 字符 */
    private static final int MAX_ATTR_CHARS = 256 * 1024;

    private String clip(String s) {
        if (s == null) return null;
        return s.length() <= MAX_ATTR_CHARS
                ? s
                : s.substring(0, MAX_ATTR_CHARS) + "\n...[truncated, total " + s.length() + " chars]";
    }
}
```

> **警告（必读）**：这些属性里包含**用户 prompt 与生成结果（代码）**。上线前必须评估：
> 1. 是否做脱敏/截断（`management.opentelemetry.tracing.limits.max-attribute-value-length` 可以限制单属性长度，Spring Boot 3.5 支持 `management.opentelemetry.tracing.limits.*`）；
> 2. self-host 的话数据不出内网，风险可控；用 Cloud 则数据出境；
> 3. Langfuse 侧可用 `langfuse.observation.metadata.*` 之外的数据掩码能力（self-host 有 Data Masking 配置项）。

## 5.3 在自定义 ChatModelListener 里写当前 span

如果你希望**保留 `AiModelMonitorListener` 并顺便往 Langfuse 写东西**（例如错误详情），可以在里面拿当前 OTel span：

```java
import io.opentelemetry.api.trace.Span;

@Override
public void onError(ChatModelErrorContext errorContext) {
    // ... 你原有的 Prometheus 指标逻辑 ...

    Span span = Span.current();
    if (span.getSpanContext().isValid()) {
        span.setAttribute("langfuse.observation.level", "ERROR");
        span.setAttribute("langfuse.observation.status_message",
                          String.valueOf(errorContext.error().getMessage()));
    }
}
```

**但要注意"当前 span 是谁"**：

- `io.opentelemetry.api.trace.Span.current()` 读的是 OTel Context 里的当前 span。
- Micrometer 桥接路线下，当前 span 由 `Observation` 的 **scope** 决定，而 scope 由 `ObservationChatModelListener` 在 `onRequest` 里 `openScope()` 打开。
- 因此：**同一次 LLM 调用中，如果你的 listener 排在 `ObservationChatModelListener` 之后执行，且该 observation 的 scope 已打开，`Span.current()` 就是那次 LLM 的 span**；否则拿到的是外层业务 span（甚至 INVALID）。
- Spring 的 `ObjectProvider.orderedStream()` 是**按 bean 定义顺序**注入的，顺序可控但不显式。

> 这属于**必须实测**的细节。更稳妥的做法是：**LLM span 由 `langchain4j-observation` 全权负责，你只用 `ObservationFilter` 做属性增强**（§5.2），`ChatModelListener` 保持只写 Prometheus。这样职责清晰、时序确定。

## 5.4 业务链路：一次生成任务 = 一条 trace

推荐结构（每个 LangGraph4j 节点一个 span，每个 LLM 调用一个 generation）：

```
trace: gen-task-{taskId}                       (root span, langfuse.trace.name = "gen-task")
├── langfuse.user.id / langfuse.session.id / langfuse.trace.metadata.{appId,taskId,genType}
├── span: langgraph.node.Router
├── span: langgraph.node.PromptEnhancer
│   └── generation: chat-gpt-4o               ← langchain4j-observation 自动产生
├── span: langgraph.node.CodeGenerator
│   ├── generation: chat-gpt-4o
│   └── generation: chat-gpt-4o  (流式，带 completionStartTime)
├── span: langgraph.node.CodeQualityCheck
├── span: langgraph.node.ImageCollector
└── span: langgraph.node.ProjectBuilder
```

### (a) 创建 root span + 设置 trace 级属性

```java
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Scope;

public class GenTaskTracer {
    private static final Tracer tracer = GlobalOpenTelemetry.getTracer("yu-ai-code-mother", "1.0.0");

    public static <T> T traceTask(String taskId, String userId, String appId, String genType,
                                  Supplier<T> body) {
        Span span = tracer.spanBuilder("gen-task")
                .setAttribute("langfuse.trace.name", "gen-task")
                .setAttribute("langfuse.user.id", userId)
                .setAttribute("langfuse.session.id", String.valueOf(appId)) // 用 appId 当会话更贴合业务
                .setAttribute("langfuse.trace.metadata.taskId", taskId)
                .setAttribute("langfuse.trace.metadata.appId", appId)
                .setAttribute("langfuse.trace.metadata.genType", genType)
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            return body.get();
        } catch (RuntimeException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }
}
```

### (b) LangGraph4j 节点 span

在节点的执行逻辑外层包一层（LangGraph4j 的 node action 里）：

```java
Span node = tracer.spanBuilder("langgraph.node." + nodeName)
        .setAttribute("langfuse.observation.type", "chain")
        .setAttribute("langfuse.observation.metadata.nodeName", nodeName)
        .startSpan();
try (Scope s = node.makeCurrent()) {
    return doNodeWork(state);
} finally {
    node.end();
}
```

### (c) 把 trace 级属性复制到**每一条** span（Langfuse v4 硬要求）

官方原文：

> These attributes include: `userId` (via `langfuse.user.id` or `user.id`), `sessionId` …, `metadata` …, `version`, `release`, `tags`, `trace_name`. **Langfuse filters and aggregations increasingly operate across individual observations rather than only at the trace level. If you want to reliably filter or aggregate by these attributes, they need to be present on each span in the trace, not only on the root span.**

**Java 侧实现**：自己写一个 `BaggageSpanProcessor`（Java 生态里没有现成的，Python/JS 有官方包）：

```java
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

/** 把 Baggage 里的 key/value 复制成每个 span 的属性（Langfuse 官方推荐做法） */
public class BaggageSpanProcessor implements SpanProcessor {
    @Override public void onStart(Context parentContext, ReadWriteSpan span) {
        Baggage.fromContext(parentContext)
               .forEach((key, value) -> span.setAttribute(key, value.getValue()));
    }
    @Override public boolean isStartRequired() { return true; }
    @Override public void onEnd(io.opentelemetry.sdk.trace.ReadableSpan span) { }
    @Override public boolean isEndRequired() { return false; }
}
```

注册到 Spring Boot 的 `SdkTracerProvider`（3.5.4 已核对该扩展点存在）：

```java
import org.springframework.boot.actuate.autoconfigure.tracing.SdkTracerProviderBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OtelConfig {
    @Bean
    public SdkTracerProviderBuilderCustomizer baggageSpanProcessorCustomizer() {
        return builder -> builder.addSpanProcessor(new BaggageSpanProcessor());
    }
}
```

写入 Baggage（在 trace 入口处）：

```java
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

Baggage baggage = Baggage.builder()
        .put("langfuse.user.id", userId)
        .put("langfuse.session.id", appId)
        .put("langfuse.trace.name", "gen-task")
        .put("langfuse.trace.metadata.taskId", taskId)
        .put("langfuse.trace.metadata.appId", appId)
        .put("langfuse.trace.metadata.genType", genType)
        .build();
try (Scope scope = baggage.storeInContext(Context.current()).makeCurrent()) {
    // 这里面的所有 span 都会带上这些属性
    workflow.invoke(initialState);
}
```

> ⚠️ 官方安全提示：Baggage 会跨服务边界传播，**不要放敏感信息**。
> ⚠️ 异步任务执行器：`@Async` / 自建线程池会**丢失 OTel Context**。必须用 OTel 的 `Context.taskWrapping(Runnable)` 或 Spring 的 `ContextPropagation`（`ContextSnapshot` / `ContextExecutorService`）包装 executor，否则 LangGraph4j 在子线程跑的节点 span 会变成孤儿 trace。

### (d) 用 `ChatRequestOptions` 传 per-call 业务字段（最直接）

```java
ChatRequestOptions options = ChatRequestOptions.builder()
        .addListenerAttribute("userId", userId)
        .addListenerAttribute("appId", appId)
        .addListenerAttribute("taskId", taskId)
        .addListenerAttribute("genType", "HTML")     // 生成类型
        .addListenerAttribute("nodeName", "CodeGenerator")
        .build();

chatModel.chat(request, options);                            // ChatModel
streamingChatModel.chat(request, options, handler);          // StreamingChatModel
```

配合 §5.2 的 `LangfuseObservationFilter` 即可把这些字段自动挂到每条 generation span 上。

---

# 6. 替代 / 竞品方案对比

本节所有事实均独立核对过来源（URL 见表格下方）。**核心判断标准是：一个不能再写 Python 的 Java/Spring Boot 团队，能否低成本拿到 LLM 语义的链路。**

## 6.1 总览对比表

| 方案 | 开源 / 许可 | 摄取协议 | Java / LangChain4j 友好度 | 自托管 | 入门成本 |
|---|---|---|---|---|---|
| **Langfuse** | MIT（核心 OSS；`ee/` 目录单独授权。版权方已是 ClickHouse, Inc.——Langfuse 2026 年初加入 ClickHouse） | **OTLP/HTTP 原生**（`/api/public/otel`，**不支持 gRPC**） | ★★★☆ 无官方 Java 埋点；但 `langchain4j-observation` + Micrometer/OTel 桥接后可开箱得到 GENERATION；官方 `langfuse-java` 是**API 客户端不是追踪 SDK**。**gen_ai 映射是各家最全的**（`gen_ai.prompt` / `gen_ai.completion` / `gen_ai.request.*` / `gen_ai.usage.*` / `gen_ai.usage.cost`） | ✅ docker compose（6 容器） | Cloud Hobby 免费 **50k units/月** |
| **Arize Phoenix** | **服务端 = Elastic License 2.0（ELv2，非 OSI）**；但 **Java instrumentation 库的 POM 声明是 Apache-2.0** | OTLP（HTTP 与 gRPC 都支持）+ OpenInference | ★★★★ **唯一有官方 Maven 发布的 LangChain4j 埋点库**：`com.arize:openinference-instrumentation-langchain4j:0.1.8`，Java 11+、要求 OTel Java 1.49.0+ | ✅ Docker Hub 镜像 `arizephoenix/phoenix`；UI+OTLP HTTP 在 **6006**，OTLP gRPC 在 **4317** | 免费（自托管） |
| **Comet Opik** | **Apache-2.0**（21.9k stars） | OTLP/**HTTP-only**，路径 `/api/v1/private/otel` | ★★★☆ 无 Java SDK，但文档有 **Java 专区**（Spring AI、Quarkus LangChain4j 两页），明确支持任意 OTel SDK。⚠️**未验证**：没有公开的 gen_ai 属性映射表，渲染深度未知 | ✅ `./opik.sh` → `:5173` | OSS $0；Free Cloud $0（25k spans/月）；Pro $19/月 |
| **LangSmith** | 闭源 | **支持 OTel**，端点 `https://api.smith.langchain.com/otel/v1/traces`；有 GenAI semconv / Traceloop / OpenInference / Logfire **完整属性映射表** | ★★☆ 无 Java SDK，但 OTLP 属性映射很全 → Java 可以"只发 OTel" | ❌ **自托管仅 Enterprise**（pricing 页原文把 "Self-hosted and hybrid deployment options" 只列在 Enterprise 下）——**硬性不匹配** | Developer $0（5k base traces/月）；Plus $39/seat/月 |
| **Helicone** | **Apache-2.0**（6.1k stars） | **HTTP 代理/gateway 模式**（"One line of code…"）。⚠️ 其文档索引里 `opentelemetry`、`gen_ai` **出现 0 次**，OTel 相关路径全 404 → **不是 OTLP 后端** | ★★☆ 与语言无关，但要改 `base_url` 走代理；拿不到 LLM 语义的 span 树 | ✅ 可自托管 | 免费 10k req/月 |
| **Datadog LLM Observability** | 闭源 | OTLP（GenAI semconv 1.37+）**+ OpenInference** | ★★★☆ **有官方 Java 支持**：`dd-trace-java ≥ 1.51.0` + `-Ddd.llmobs.enabled=true` | ❌ 不可自托管 | 商业计费 |
| **SigNoz** | MIT（+ `ee/`） | OTLP | ★★☆ 无 Java 埋点，但**显式渲染 `gen_ai.*`** | ✅ | 云版 $49/月 |
| **Grafana Tempo / OSS Grafana** | AGPL / Apache-2.0 | OTLP | ★ **整个 1.4 MB 文档索引里 `gen_ai` 出现 0 次 → 没有 LLM-aware UI**，只能当"能存能看 trace"的底座 | ✅（已有） | $0（已有） |
| **OpenLLMetry (Traceloop)** | Apache-2.0 | OTLP | ★ **Java 实际无支持**：`traceloop/openllmetry-java` 2024-02-15 创建、**当天即停更**、3 stars、README 404 → 空壳仓库 | ✅（Collector） | — |
| **OpenLIT** | Apache-2.0 | OTLP | ★ **无 Java**：`sdk/` 目录只有 `go/`、`python/`、`typescript/` | ✅ | — |
| **Langtrace** | AGPL | OTLP | ★ **无 Java**：官方 FAQ 原文「Langtrace currently supports Python and Typescript.」 | ✅ | — |
| **Uptrace** | AGPL | OTLP | ★ 无 gen_ai UI | ✅ | 免费 50 GB/月 |

> ⚠️ **Langfuse 官方文档里有一处不实陈述**：其 OTel 页面写 "Popular OpenTelemetry libraries include **OpenLLMetry and OpenLIT which extend Language support of Langfuse tracing to Java** and Go"。本次调研**独立证伪**：OpenLLMetry 的 Java 仓库是空壳，OpenLIT 没有 Java 实现（证据见 §6.4）。**不要据此认为 Langfuse 有现成的 Java→LLM 埋点方案**——Java 侧必须自己用 OTel 语义约定埋点。

## 6.2 关键发现：Arize 的 Java 埋点可以"混搭"发给 Langfuse

本次调研最重要的"隐藏路线"：

- Arize 官方在 Maven Central 发布了 OpenInference 的 **Java** instrumentation（目录已核对 <https://repo1.maven.org/maven2/com/arize/>）：

| artifact | 最新版本 |
|---|---|
| `com.arize:openinference-instrumentation-langchain4j` | **0.1.8** |
| `com.arize:openinference-instrumentation` | 0.1.9 |
| `com.arize:openinference-instrumentation-springAI` | 0.1.9 |
| `com.arize:openinference-instrumentation-annotation` | 0.1.2 |
| `com.arize:openinference-semantic-conventions` | 0.1.18 |

  其 POM 依赖（已核对）：`openinference-instrumentation:0.1.9`、`io.opentelemetry:opentelemetry-api:1.49.0`、`io.opentelemetry.instrumentation:opentelemetry-instrumentation-api:2.14.0`（+ `-incubator:2.14.0-alpha`）、`jackson-databind:2.17.0`。**注意它要求 OTel 1.49.0+，和我们 Spring Boot 3.5.4 管理的 1.49.0 正好对上。**

- 而 **Langfuse 的 `ObservationTypeMapper.ts` 明确支持 OpenInference 属性**（源码已核对）：

```typescript
// Priority 2: OpenInference span kind
new SimpleAttributeMapper("OpenInference", 2, "openinference.span.kind", {
  CHAIN: "CHAIN", RETRIEVER: "RETRIEVER", LLM: "GENERATION",
  EMBEDDING: "EMBEDDING", AGENT: "AGENT", TOOL: "TOOL",
  GUARDRAIL: "GUARDRAIL", EVALUATOR: "EVALUATOR",
}),
// Priority 3: OpenTelemetry GenAI operation
new SimpleAttributeMapper("OTel_GenAI_Operation", 3, "gen_ai.operation.name", {
  chat: "GENERATION", completion: "GENERATION", text_completion: "GENERATION",
  generate_content: "GENERATION", generate: "GENERATION",
  embeddings: "EMBEDDING", invoke_agent: "AGENT", create_agent: "AGENT", execute_tool: "TOOL",
}),
```

  且 observation 的 input/output 也支持 OpenInference 的 `input.value` / `output.value`（官方属性映射表原文：`langfuse.observation.input` / `gen_ai.prompt` / **`input.value` (OpenInference)** / `mlflow.spanInputs`）。

**→ 结论：完全可以用 Arize 的 `openinference-instrumentation-langchain4j` 产生 span（零手写埋点），再把 OTLP 端点指向 Langfuse。** 这是一条"官方 Java SDK + Langfuse 后端"的混搭路线。

> ⚠️ 两个必须自行验证的点：
> 1. 该库的 `opentelemetry-instrumentation-api:2.14.0` 与 Spring Boot 3.5.4 的依赖是否冲突（OTel SDK 1.49.0 vs instrumentation-api 2.14.0 属于不同 artifact 线，通常共存但要实测）。
> 2. Arize 官方文档页面是 JS 渲染的，本环境抓不到正文（`web_fetch` 因 DNS 假 IP 不可用，curl 只能拿到 CSS）。**其快速上手代码与 OTLP 端口（社区常说 6006 HTTP / 4317 gRPC）我未能逐字核实，标为「未验证」**，请以 <https://arize.com/docs/phoenix/integrations/java/langchain4j/langchain4j-tracing> 为准。

## 6.3 选型建议（针对本项目）

前提：Java 17 / Spring Boot 3.5.4 / LangChain4j 1.18.1 / 已有 Micrometer + Prometheus + Grafana / 已有 docker compose / **不能把埋点改写成 Python**。

**结论：主选 Langfuse，用 §3.3 的方案 B（`langchain4j-observation` + Micrometer + OTLP）。**

理由：

1. **它是唯一同时满足三个硬条件的**：OTLP 原生可 self-host、有完整的 LLM 语义（generation / 成本 / token / prompt 管理 / 评分 / 数据集）、且我们已经**源码级验证**了它的观测类型映射能正确识别 LangChain4j/Micrometer 产出的 `gen_ai.operation.name=chat`。
2. **适配成本最低**：`langchain4j-observation:1.18.1-beta28` 与本项目 langchain4j 版本完全对齐，且 starter 会自动把 listener 挂到主模型上（§2.5.3）。
3. **数据合规可控**：trace 里含用户需求与生成代码，self-host 可保证不出内网。

**备选排序**：

| 排序 | 场景 | 选择 |
|---|---|---|
| 1 | 想要**最少手写埋点**、能接受 ELv2 许可 | **Arize Phoenix 的 Java instrumentation 混搭发到 Langfuse**（§6.2），或直接上 Phoenix |
| 2 | 想要 Apache-2.0 + 有 Java 文档 + 可自托管 | **Comet Opik**（有 Spring AI / Quarkus LangChain4j 专页，OTLP 通用接入） |
| 3 | 团队已重度使用 LangChain 生态、且愿意买 Enterprise | LangSmith（OTLP 属性映射很全，但自托管要 Enterprise） |
| 4 | 只想要"网关式"的成本/用量统计，不追求 trace 树 | Helicone（但要改 `base_url` 走代理，改造面大） |
| ❌ | — | **OpenLLMetry 直接排除**（Java 是空壳仓库）；**OpenLIT / Langtrace 不推荐**（无 Java）。 |

### 「如果只能选一个」的决策点

把三条硬约束（**可自托管** + **Java 原生埋点** + **开箱内容完整**）同时满足的只有两个方案，**唯一的取舍点是许可**：

| | 方案 A：Langfuse + `langchain4j-observation` | 方案 B：Phoenix + `com.arize` instrumentor |
|---|---|---|
| 许可 | **MIT，可自托管且不限量、无功能门** ✅ 评审/合规最省事 | **服务端 ELv2（非 OSI）+ 专利声明** ⚠️ 内部使用没问题，**做对外 SaaS 前必须过法务** |
| Java 埋点工作量 | 需自己写 ~50–100 行（补 `gen_ai.prompt`/`gen_ai.completion`/`gen_ai.usage.*`） | **开箱即含 messages/token/工具调用**，工程量最小 |
| 部署成本 | 6 个容器（含 ClickHouse） | **单容器** + 可选 Postgres/SQLite |
| 协议 | OTLP **HTTP only**（gRPC 不支持） | HTTP(6006) + gRPC(4317) 都行 |
| gen_ai 渲染 | **各家最全** | 只发标准 gen_ai 时"UI 功能会 reduced"，必须用它自家的 instrumentor |
| 未来换后端 | OpenInference 属性也能被 Langfuse 识别 → 埋点可复用 | — |

**判断规则**：

- **不能接受非 OSI 许可 → 选 A（Langfuse）**，代价是自己写属性补全代码（本报告 §5.2 已经给了）。
- **能接受 ELv2、且要最快见效 → 选 B（Phoenix + `com.arize`）**，甚至可以把这个 OpenInference 埋点的 OTLP 同时扇出给 Langfuse（§6.2）。

> 对大多数国内业务系统，"内部可观测平台"用途下 ELv2 通常没有障碍，但**如果这个平台未来要作为对外服务的一部分（SaaS 化），A 更安全**。

**不建议**：为了 Langfuse 去引入 OTel Java Agent 走"零代码"路线（§3.2）——它给不了 generation/token/prompt，等于白接。

## 6.4 ⭐ 零成本第一步：`langchain4j-micrometer-metrics`（本周就能做）

这是竞品调研中另一个**高价值发现**：LangChain4j 还发布了 **`dev.langchain4j:langchain4j-micrometer-metrics`**，同样是 **`1.18.1-beta28`** 版本线（已核对 Maven Central，且 1.18.0/1.18.1/1.19.x 齐全），POM 声明 **Apache-2.0**，依赖 `langchain4j-core:1.18.1` + `micrometer-core:1.16.5`。

它提供一个 `MicrometerMetricsChatModelListener`（`@Experimental`，源码已逐行核对），在 `onResponse` 里往**现有 `MeterRegistry`** 写一个 `DistributionSummary`：

```java
// 逐字来自 langchain4j-micrometer-metrics:1.18.1-beta28 sources jar
DistributionSummary.builder("gen_ai.client.token.usage")     // OTelGenAiMetricName.TOKEN_USAGE
        .baseUnit("tokens")
        .tag("gen_ai.operation.name", "chat")
        .tag("gen_ai.provider.name", getProviderName(responseContext))
        .tag("gen_ai.request.model",  getRequestModelName(responseContext))
        .tag("gen_ai.response.model", getResponseModelName(responseContext))
        .tag("gen_ai.token.type",     tokenType.value())      // "input" / "output"
        .description("Measures token usage")
        .register(meterRegistry)
        .record(tokenCount);
```

**为什么这是"零成本第一步"**：

| 维度 | 说明 |
|---|---|
| 新增组件 | **0 个**（只加一个 Maven 依赖 + 一个 `@Bean`） |
| 复用现有设施 | ✅ 直接进你已有的 **Micrometer + Prometheus + Grafana** |
| 得到什么 | 按 `model` / `provider` / `token.type` 维度的 **token 用量曲线**，可做成本估算与告警 |
| 拿不到什么 | **没有 trace 树、没有 prompt/completion 内容、没有单次链路排查能力** |
| 版本风险 | 与 §7.3 的 micrometer 1.15.2 vs 1.16.5 问题**完全相同**（同一个 1.18.1-beta28 版本线、同一个 micrometer 1.16.5 编译依赖） |

> 注意：`langchain4j-observation` 的 `ObservationChatModelListener` **内部也注册了同名指标** `gen_ai.client.token.usage`（源码里 `TOKEN_USAGE` 常量）。**两个 listener 同时挂会产生重复计数**——二选一，或只保留其中一个。建议：
> - 只想先要 token 指标 → 用 `langchain4j-micrometer-metrics`；
> - 要 trace → 用 `langchain4j-observation`（它顺带把指标也给了）。
> 从"只要指标"升级到"要 trace"时，**把这个 listener 换成 `ObservationChatModelListener` 即可**，Prometheus 指标名不变。

## 6.5 推荐推进路径：Collector 扇出，一次埋点两处验证

由于 Langfuse 与 Phoenix（以及 LangSmith / Opik / Datadog）**都吃 OTLP**，最稳的做法是**让应用只发一次 OTLP 到本地 OTel Collector，由 Collector 扇出（fanout）到多个后端**：

```
Spring Boot (+ langchain4j-observation)
        │  OTLP/HTTP  http://otel-collector:4318
        ▼
  OpenTelemetry Collector
        ├── otlphttp/langfuse   → http://lf-web:3000/api/public/otel   (头: Authorization + x-langfuse-ingestion-version:4)
        └── otlphttp/phoenix    → http://phoenix:6006                  (OTLP HTTP)
```

Collector 配置骨架（端点与 header 以各自官方文档为准）：

```yaml
receivers:
  otlp:
    protocols:
      http:
        endpoint: 0.0.0.0:4318
      grpc:
        endpoint: 0.0.0.0:4317

processors:
  batch: {}
  # 可在这里做脱敏 / 属性裁剪，避免把用户代码原文发到不允许的外网后端
  attributes:
    actions:
      - key: langfuse.trace.metadata.taskId
        action: upsert

exporters:
  otlphttp/langfuse:
    endpoint: http://lf-web:3000/api/public/otel
    headers:
      Authorization: "Basic ${env:LANGFUSE_AUTH_BASE64}"
      x-langfuse-ingestion-version: "4"
  otlphttp/phoenix:
    endpoint: http://phoenix:6006

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [batch, attributes]
      exporters: [otlphttp/langfuse, otlphttp/phoenix]
```

**这条路线的好处**：后端可替换、可 A/B 对比、可只改 Collector 配置而不动 Java 代码，且未来要加 Datadog/SigNoz 也只是加一个 exporter。

> 注意：走 Collector 时，**应用侧 `management.otlp.tracing.endpoint` 指向 Collector**（仍是完整路径 `http://otel-collector:4318/v1/traces`），Basic Auth 头与 `x-langfuse-ingestion-version` 挪到 Collector 的 exporter 上。

## 6.6 本节的来源

| 事实 | URL |
|---|---|
| Phoenix 服务端许可为 ELv2 | https://raw.githubusercontent.com/Arize-ai/phoenix/main/LICENSE |
| Phoenix 容器镜像 / 自托管 | https://raw.githubusercontent.com/Arize-ai/phoenix/main/README.md 、https://hub.docker.com/r/arizephoenix/phoenix |
| Phoenix 的 Java/LangChain4j 埋点指南（6006/4317、OTel 1.49.0+） | https://arize.com/docs/phoenix/integrations/java/langchain4j/langchain4j-tracing |
| Arize OpenInference Java artifacts 列表 | https://repo1.maven.org/maven2/com/arize/ |
| `openinference-instrumentation-langchain4j:0.1.8` POM 依赖 | https://repo1.maven.org/maven2/com/arize/openinference-instrumentation-langchain4j/0.1.8/openinference-instrumentation-langchain4j-0.1.8.pom |
| LangChain4j 自身 gen_ai 约定（`ChatModelDocumentation`） | https://github.com/langchain4j/langchain4j/blob/main/langchain4j-observation/src/main/java/dev/langchain4j/observation/convention/ChatModelDocumentation.java |
| `langchain4j-micrometer-metrics` 版本列表（含 1.18.1-beta28） | https://repo1.maven.org/maven2/dev/langchain4j/langchain4j-micrometer-metrics/maven-metadata.xml |
| `langchain4j-micrometer-metrics:1.18.1-beta28` 源码 | https://repo1.maven.org/maven2/dev/langchain4j/langchain4j-micrometer-metrics/1.18.1-beta28/ |
| LangChain4j Spring Boot 集成（Java 17、SB 3.5+/4.0+） | https://docs.langchain4j.dev/tutorials/spring-boot-integration |
| OTel Java instrumentation 模块列表（**无 langchain4j 模块**） | https://repo1.maven.org/maven2/io/opentelemetry/instrumentation/ |
| Langfuse 观测类型映射源码（含 OpenInference / gen_ai） | https://raw.githubusercontent.com/langfuse/langfuse/main/packages/shared/src/server/otel/ObservationTypeMapper.ts |
| Opik 许可 Apache-2.0 | https://api.github.com/repos/comet-ml/opik |
| Opik Java 集成页（Spring AI / Quarkus LangChain4j） | https://www.comet.com/docs/opik/integrations/opentelemetry |
| LangSmith 支持 OTel + 属性映射表 | https://docs.langchain.com/langsmith/trace-with-opentelemetry |
| LangSmith 定价（自托管仅 Enterprise） | https://www.langchain.com/pricing |
| Helicone 许可 Apache-2.0 | https://api.github.com/repos/Helicone/helicone |
| OpenLLMetry Java 空壳仓库 | https://api.github.com/repos/traceloop/openllmetry-java |
| OpenLIT `sdk/` 目录只有 go/python/typescript | https://api.github.com/repos/openlit/openlit/contents/sdk |
| Langtrace 仅支持 Python/TypeScript | https://docs.langtrace.ai/faqs.md |

---

# 7. 落地成本与风险

## 7.1 改造量评估（对本项目，基于实际代码核对）

| 项 | 工作量 | 说明 |
|---|---|---|
| pom.xml | **4 个依赖** | `langchain4j-observation:1.18.1-beta28`、`spring-boot-starter-actuator`、`micrometer-tracing-bridge-otel`、`opentelemetry-exporter-otlp` |
| application.yml | **~20 行** | `management.otlp.tracing.*`（注意 endpoint 带 `/v1/traces`）、`management.tracing.sampling.probability`、`management.observations.enable` 去重 |
| LLM 调用埋点 | **1 个 `@Bean`（~5 行）+ 1 行** | `ObservationChatModelListener` 声明为 bean；`openAiChatModel`/`openAiStreamingChatModel` **自动挂载**；仅在 `RoutingAiModelConfig.routingChatModelPrototype()` 手工加的 `.listeners(...)` 是 1 行 |
| 属性增强 | **~120 行** | `LangfuseObservationFilter`（input/output/usage/业务字段/截断保护），见 §5.2 |
| 业务 trace | **~80 行** | root span + LangGraph4j 节点 span + Baggage + `BaggageSpanProcessor` |
| 异步 executor 改造 | **~30 行** | TaskDecorator / `Context.taskWrapping`，否则子线程 span 断链 |
| docker-compose.yml | **~80 行** | 新增 6 个 Langfuse 服务 + 端口/卷/网络调整（或独立 project + 反代） |
| **现有业务代码改动** | **1 行** | `AiModelMonitorListener`、`AiModelMetricsCollector`、6 处 `AiServices.builder(...)`、LangGraph4j 工作流**均不用改** |

**总投入估计**：3–5 人日（不含 ClickHouse 容量调优与压力测试）。

## 7.2 自托管运维复杂度

| 风险 | 说明 | 缓解 |
|---|---|---|
| **组件多** | 6 个容器：web/worker/clickhouse/postgres/redis/minio | 用官方 compose 直接 merge，不自己拼 |
| **服务名撞车** | 官方 compose 的 service 键就叫 `redis` 和 `postgres`，与本项目现有服务**同名**；YAML 键重复直接冲突 | 改名（如 `lf-redis`/`lf-postgres`），改名后**必须同步改** `REDIS_HOST`、`DATABASE_URL`、`DIRECT_URL` |
| **端口冲突（即使绑 127.0.0.1 也占端口）** | 官方映射：`127.0.0.1:6379:6379`（redis）、`127.0.0.1:5432:5432`（postgres）、`127.0.0.1:8123/9000`（CH）、`3000:3000`（web，**全网卡**）、`3030`（worker）、`9090`（minio 对外）、`9091`（minio 控制台） | **只改宿主机侧映射**（`"5433:5432"`、`"6380:6379"`），**容器内部端口与 `DATABASE_URL` 保持不变**（社区踩坑 [#10212](https://github.com/orgs/langfuse/discussions/10212)：改容器内端口后仍去连 localhost:5432）；或直接删掉不需要的 `ports:` 段 |
| **网络不互通** | 两个 compose project 默认网络不同，Langfuse 用不了 service 名解析你现有的 `mysql`/`backend` | 用 `networks: {shared: {external: true}}` 显式共享，或**干脆把 Langfuse 保持为独立 compose project**，只反代 `langfuse-web:3000` |
| **不能复用现有 Redis** | Langfuse 要求 Redis ≥7 且 `maxmemory-policy=noeviction`，并期望「access to all keys and commands within the given database」（ACL 形如 `on ~* +@all`），与 BullMQ 队列强耦合 | **不要和业务共用 Redis 库**；`noeviction` 不满足会导致队列 job 被驱逐、worker 静默停摆 |
| **MySQL 不能替代 Postgres** | Langfuse 只官方支持 PostgreSQL / Aurora PostgreSQL | 现有 mysql 只能并存 |
| **Postgres 必须 UTC** | 「Langfuse expects that its infrastructure components default to UTC」 | 保持官方 compose 的 `TZ=UTC`/`PGTZ=UTC` |
| **数据无限增长** | self-host 默认**永不删除**；Data Retention 是 EE 功能 | 在 ClickHouse 对 `traces`/`observations`/`scores`/`event_log` 建 TTL；**并且必须治理 ClickHouse 系统日志表**（否则磁盘被 `system.trace_log` 吃掉——社区实测 66.86 GiB vs 业务数据 <52 MiB） |
| **备份** | Postgres 存元数据+API key（**不可重建**）；ClickHouse 存全部 trace 且**无内置从 S3 回灌机制**（维护者原话：「not automated anywhere within Langfuse」） | **Postgres 与 ClickHouse 都必须定期快照**；不要把 S3 events 当 ClickHouse 的备份 |
| **Base path 部署成本高** | 想在 `https://domain/langfuse` 下运行，**web 镜像必须从源码构建**（`NEXT_PUBLIC_BASE_PATH` 是 build arg），worker 可用预构建镜像 | **推荐改用子域名反代**（`langfuse.yourdomain.com` → `langfuse-web:3000`），此时预构建镜像即可 |
| **媒体上传** | 官方 compose 里 `LANGFUSE_S3_MEDIA_UPLOAD_ENDPOINT` 被设为 `http://localhost:9090`，Docker 网络外不可达 → 多模态上传失败 | 生产必须改成 SDK/浏览器可达地址；容器内读用 `LANGFUSE_S3_MEDIA_UPLOAD_INTERNAL_ENDPOINT` |
| **无 HA** | compose 方式无高可用，单点 | 对"监控挂了不影响业务"的定位可接受；OTel SDK 默认异步批量导出，**绝不能让上报拖慢主流程**（`management.tracing.opentelemetry.export.*` 可调 batch/queue） |
| **升级** | v3→v4 有破坏性变更（API、ingestion、读端点全部 404）；**无自动降级** | 新部署直接用 `:4` 镜像；基础设施满足 CH≥25.12 / PG≥15 / Redis≥7.0（官方 compose 默认值正好满足） |
| **首次初始化** | 需要建 organization/project 并生成 pk/sk | 用 `LANGFUSE_INIT_*` 无头初始化（**值不要加双引号**，[issue #3398](https://github.com/langfuse/langfuse/issues/3398)）|

### 数据量估算

**Langfuse 的计费单位**（[Billable Units](https://langfuse.com/docs/administration/billable-units)）：

```
Units = Trace 数 + Observation 数 + Score 数
```

> 官方示例锚点：`20,070 Traces + 119,500 Observations + 561 Scores` → **observations ≈ traces × 6**。这个比例（1 trace ≈ 6 observations）比我们下面的估算更保守，做容量规划时建议按 **1:6** 而不是 1:11。

**本项目的单次生成任务估算**（假设 LangGraph4j 6 个节点，其中 3 个节点调用 LLM）：

| 项 | 数量 |
|---|---|
| Trace | 1 |
| Observation（generation） | 3（LLM 调用） |
| Observation（span） | 6（节点）+ 1（root）= 7 |
| Observation（guessed 其他） | 0 |
| **合计 units / 任务** | **≈ 11** |

**月度量级推演**（假设）：

| 假设 | 值 |
|---|---|
| 日活生成任务 | 500 |
| 每次任务 units | 11 |
| 月 units | 500 × 11 × 30 = **165,000 units/月** |
| vs Cloud Hobby（50k） | **超 3.3 倍** → Hobby 不够 |
| vs Cloud Core（含 100k，超出 $8/100k） | 超出 65k → 约 **+$5.2/月 + $29 = $34/月** |
| vs Pro | $199/月 |

**存储侧估算**（**以下均为估算，非官方数字**）：

- 单条 generation observation 的 input+output 文本：代码生成场景下 prompt ~2–5 KB、生成代码 ~5–20 KB → 平均按 **15 KB/observation** 计（含 metadata/attributes 开销另加 ~30%）。
- 每任务 11 个 observation ≈ 165 KB 原始文本；ClickHouse 列存 + 压缩（ZSTD）后通常可按 **1/5 ~ 1/10** 估。
- 165,000 units/月 ≈ 500×30 = 15,000 任务/月 ≈ **2.5 GB/月原始 / 约 0.3–0.5 GB/月压缩后**。
- 一年 ≈ **30 GB 原始 / 4–6 GB 压缩后**。
- → **磁盘不是瓶颈，ClickHouse 内存与查询并发才是**。这也是官方给「4C16G」建议的原因。

**换一个更通用的口径**（**估算**，公式与假设如下）：

```
CH_bytes ≈ Σ_t (行数_t × 平均单行未压缩字节_t ÷ 压缩比) × (1 + 索引与 part 开销率) + 系统表开销
```
假设：压缩比 7.5×、索引/part 开销 +15%、1M traces = 6M observations（按官方 1:6 锚点）。

| 场景 | 平均单行 payload（估算） | ClickHouse 压缩后（估算） | S3 events（估算） |
|---|---|---|---|
| 轻量（短 prompt/completion） | 2 KB | **~1.8 GB / 1M traces** | ~12 GB |
| 中等（含上下文、tool 输出、metadata） | 20 KB | **~18 GB / 1M traces** | ~120 GB |
| **重（代码生成、长上下文、大 JSON）** | 200 KB | **~184 GB / 1M traces** | ~1.2 TB |

**本项目按"重"档估算**：15,000 任务/月 = 0.015M traces/月 → ClickHouse 约 **2.8 GB/月**、S3 约 **18 GB/月**。

**必须叠加的非业务开销**（官方事实）：
1. **ClickHouse 系统日志表可能远超业务数据**（社区实测 66.86 GiB vs <52 MiB）——不治理的话上面的估算完全失效。
2. v4 historic backfill 需要 **约 3× 现有数据量**的磁盘余量；`dual` 写入模式**存两份**（官方原文：「`dual` doubles the write load and storage costs」）。

> **强烈建议**：上线前先用 self-host + 真实流量跑一周，用 Langfuse 内置的 **Langfuse Usage Management** 看板（Dashboards → Langfuse Usage Management）读真实 units，再决定 Cloud/self-host 与保留策略。

> **未验证**：官方**从未发布**「每 1M traces = X GB」的数字（相关 issue 里维护者只回答「built for scale」，没给数值）。上面所有存储数字都是基于官方约束（2 MiB 单字段上限、1:6 比例、backfill 3×）的自建估算，**必须以自身实测校准**。

## 7.3 版本兼容性坑（逐条）

| # | 风险 | 事实（已核对） | 建议 |
|---|---|---|---|
| 1 | **micrometer 版本错位** | `langchain4j-observation:1.18.1-beta28` 编译期依赖 **micrometer 1.16.5**；Spring Boot 3.5.4 的 `micrometer-bom` 把 micrometer 固定为 **1.15.2**。Maven 的 dependencyManagement 会**强制降到 1.15.2** | 先按默认（1.15.2）跑，写一个最小单测验证 `ObservationChatModelListener` 能正常产生 span；若报 `NoSuchMethodError`，在 pom 里显式覆盖 `micrometer.version`（有风险，可能影响 actuator/prometheus）或升级 Spring Boot |
| 2 | **OTel 版本落后** | Spring Boot 3.5.4 通过 `opentelemetry-bom` 管理 **1.49.0**；OTel Java 当前最新已是 **1.65.0**；Java Agent 最新 **2.31.1** | 跟随 Spring Boot BOM，不要手动升 OTel（会与 `micrometer-tracing-bridge-otel` 1.5.2 不一致） |
| 3 | **LangChain4j 版本必须精确对齐** | `langchain4j-observation` 的版本号必须与 `${langchain4j.version}` 处于同一 minor 线：项目是 `1.18.1` → 必须用 `1.18.1-beta28`（同一个 `1.18.0-beta28`/`1.18.1-beta28` 线） | 在 pom 里把 `langchain4j-observation.version` 与 `langchain4j.version` 绑在一起维护；**不要**用 `1.20.0-beta30`（依赖 core 1.20.0） |
| 4 | **Java Agent 与 SDK 混用** | Agent 会自己注册 GlobalOpenTelemetry；与 `opentelemetry-exporter-otlp` SDK 同时存在时可能重复导出 | 本方案**不需要 Agent**，明确不要加 `-javaagent` |
| 5 | **不要引旧 artifact 名** | `opentelemetry-exporter-otlp-http-trace` 在 Maven Central 上**止步 1.14.0**；1.49.0 的实现类在 `opentelemetry-exporter-otlp` 里 | 引 `opentelemetry-exporter-otlp` |
| 6 | **Langfuse 不支持 gRPC** | 官方原文 | Spring Boot 侧保持 `management.otlp.tracing.transport: http`（也是默认值）|
| 7 | **`x-langfuse-ingestion-version: 4`** | 不带则数据可能延迟最多 10 分钟可见 | 在 `management.otlp.tracing.headers` 里带上 |
| 8 | **endpoint 路径** | Spring Boot 用 `setEndpoint()`，**要求完整路径** | `.../api/public/otel/v1/traces` |
| 9 | **`ChatModelListener` 执行顺序** | Spring 按 `ObjectProvider.orderedStream()` 注入；`ObservationChatModelListener` 与你现有的 `AiModelMonitorListener` 共用同一个 `attributes` map（key 不冲突） | 不要依赖顺序；用 `@Order` 显式控制（若确实需要） |
| 10 | **`langchain4j-observation` 标了 `@Experimental`** | 源码里有 `@Experimental` 注解 | 版本升级时回归验证；把它隔离在一个 config 类里，便于快速摘除 |

## 7.4 其他风险

- **Langfuse 发布（release）与版本**：建议把所有 span 带上 `langfuse.release` = 你的 git commit / 构建号，方便对比"某次上线后质量变化"。
- **采样**：`management.tracing.sampling.probability` 默认 0.1（Spring Boot 默认只采 10%）。要么设 1.0，要么接受部分 tracing（Prometheus 指标仍是全量的，两者互补）。
- **不要让它拖垮业务**：OTel 导出是异步批量的；但 export 队列满时会丢 span（`management.tracing.opentelemetry.export.max-queue-size`）。监控挂掉时应用**必须能正常跑**——发消息给 AI 生成的链路不能因为 Langfuse 不可用而超时。
- **超长 attribute（代码生成场景的头号风险）**：`LANGFUSE_OBSERVATION_FIELD_SIZE_LIMIT_BYTES` 默认 **2 MiB（2 097 152 字节）**，且 `LANGFUSE_OBSERVATION_FIELD_OVERFLOW_ENABLED` 默认 `false`（溢出处理默认关闭）。**单条 observation 的 input 或 output 超过 2 MiB 会被截断/拒绝。** 生成大文件时必须做长度保护：
  ```java
  private static final int MAX_ATTR_CHARS = 256 * 1024;   // 保守取 256 KB，远低于 2 MiB 上限
  private String clip(String s) {
      if (s == null) return null;
      return s.length() <= MAX_ATTR_CHARS ? s
              : s.substring(0, MAX_ATTR_CHARS) + "\n...[truncated, total " + s.length() + " chars]";
  }
  ```
  同时可用 `management.opentelemetry.tracing.limits.max-attribute-value-length`（Spring Boot 3.5 支持 `management.opentelemetry.tracing.limits.*`）在 SDK 层再兜一道。
- **Prometheus 与 Langfuse 的分工**：Prometheus/Grafana 继续做**聚合 SLO**（QPS、P95 延迟、错误率、token 总量），Langfuse 做**单次链路的明细排查**（哪次生成、哪个节点、哪次 LLM 调用慢/贵/错）。两者不冲突。

---

# 8. 可执行的接入步骤

## 阶段 -1：零风险预热（0.5 天，可立刻做，不碰 Langfuse）

> 这一步**完全不需要 Langfuse**，只加一个依赖就能在现有 Grafana 上看到 token 用量曲线，用来先摸清数据量与成本基线。

1. 加依赖（与当前 langchain4j 同版本线，已核对存在）：
   ```xml
   <dependency>
     <groupId>dev.langchain4j</groupId>
     <artifactId>langchain4j-micrometer-metrics</artifactId>
     <version>1.18.1-beta28</version>
   </dependency>
   ```
2. 声明 bean（会被 starter 自动挂到 `openAiChatModel`/`openAiStreamingChatModel`；`routingChatModelPrototype` 仍需手工 `.listeners(...)`）：
   ```java
   @Bean
   public MicrometerMetricsChatModelListener metricsListener(MeterRegistry registry) {
       return new MicrometerMetricsChatModelListener(registry);
   }
   ```
3. 在 Grafana 上画 `gen_ai_client_token_usage_tokens`（按 `gen_ai_token_type` / `gen_ai_request_model` 分组），观察一周真实 token 曲线。
4. ⚠️ 进入阶段 0 时**把上面这个 bean 删掉**，换成 `ObservationChatModelListener`（否则 `gen_ai.client.token.usage` 会重复计数，见 §6.4）。

## 阶段 0：PoC（0.5 天，不动生产）

1. 注册 Langfuse Cloud Hobby，建 project，拿 `pk-lf-...`/`sk-lf-...`。
2. 本地起 Spring Boot，设置环境变量：

```bash
export LANGFUSE_OTLP_ENDPOINT=https://cloud.langfuse.com/api/public/otel/v1/traces
export LANGFUSE_AUTH_BASE64=$(printf '%s' "pk-lf-xxx:sk-lf-yyy" | base64)
```

3. 按 §3.3.1 加依赖（**用 `langchain4j-observation` 替换掉阶段 -1 的 micrometer-metrics**）、§3.3.2 加配置、§3.3.3 加 `@Bean`。
4. 打一次 `/api/app/chat` 之类的接口，去 Langfuse → Traces 看：
   - 是否出现 `chat-gpt-4o` 的 **Generation** 类型 observation；
   - 是否有 model 名；
   - 是否 **没有** input/output 与 token（这是预期的，说明需要 §5.2 的 Filter）。
5. （可选，强烈建议）同时挂一个本地 OTel Collector 扇出到 Phoenix，用**同一份埋点**对比两边"有没有 input/output、token 对不对、内容丰不丰富"，再决定长期后端（§6.5）。

## 阶段 1：属性补全（1 天）

5. 加 `LangfuseObservationFilter`（§5.2），验证 input/output/token 出现。
6. 加 `ChatRequestOptions` 传 `userId/appId/taskId/genType`，验证 `metadata` 里能看到且**可过滤**。

## 阶段 2：业务链路（1–2 天）

7. 在生成任务入口建 root span + Baggage（§5.4a/c）。
8. LangGraph4j 每个节点加 span（§5.4b）。
9. 异步 executor 加 Context 传播 TaskDecorator，验证**子线程 span 不是孤儿**。
10. 流式场景验证：`onRequest`/`onResponse` 跨线程时 span 是否正确挂载；TTFT 是否上报。

## 阶段 3：自托管（1 天）

11. 把 Langfuse 的 6 个服务 merge 进现有 `docker-compose.yml`：
    - 单独 network，**不对外暴露** 6379/5432/8123/9000/9090；
    - 只暴露 `3000`（或经 nginx 反代到子路径）；
    - 所有 `# CHANGEME` 密钥改成强随机值并放 `.env`；
    - backend 通过服务名 `http://langfuse-web:3000/api/public/otel/v1/traces` 上报。
12. 首次登录建 org/project，生成新的 pk/sk，更新 backend 环境变量。
13. 配 ClickHouse TTL / 定期清理；把 Postgres 纳入备份计划。

## 阶段 4：沉淀（持续）

14. Grafana 保留原面板；Langfuse 用于明细排查。
15. 用 Langfuse 的 Scores / Datasets 做代码生成质量评估（把 `gen_task` 的产物与人工/自动评分写回 Langfuse）。
16. 每次发版带 `langfuse.release`。

---

# 9. 待验证清单（上线前必须实测）

| # | 待验证项 | 验证方法 |
|---|---|---|
| 1 | micrometer 1.15.2 与 `langchain4j-observation`（编译于 1.16.5）二进制兼容 | 写一个 `@SpringBootTest`，调用一次 chat，断言 `Observation` 被 started/stopped 且无 `NoSuchMethodError` |
| 2 | 流式下 `onRequest`/`onResponse` 跨线程是否导致 scope 串扰 | 压测 + 观察是否有 span 挂错 parent、`SimpleScope` 相关 WARN 日志 |
| 3 | `Span.current()` 在 `AiModelMonitorListener` 里拿到的是哪个 span | 在 listener 里打印 `Span.current().getSpanContext().getSpanId()`，与 Langfuse UI 对比 |
| 4 | `ObservationFilter` 注入的高基数 key value 是否真的进了 span | Langfuse UI 打开一条 generation，看 `metadata.attributes` 里有没有 `langfuse.observation.input` |
| 5 | Langfuse OTLP 端点单 span / 单请求大小上限 | 故意发一个 5 MB 的 output，看是否被拒；**未验证** |
| 6 | Self-host v4 的 ClickHouse 表名与 TTL 语法 | 进 clickhouse 容器 `SHOW TABLES` 后确认 |
| 7 | `langchain4j-observation` 的 `@Experimental` API 是否会随版本变化 | 锁死 `1.18.1-beta28`，升级 langchain4j 时同步升级并回归 |
| 8 | docker compose 合并后的端口/网络冲突 | `docker compose config` + 全量起一遍 |
| 9 | Langfuse 是否会把 Micrometer 产生的其他 observation（如 `http.server.requests`）也导入 | 检查 Langfuse 是否出现大量非 LLM span；若有，用 `management.observations.enable` 关闭或加 Collector filter |
| 10 | LangChain4j issue #2328 当前状态（是否会出官方 Langfuse 模块） | 抓取 GitHub issue（本环境 DNS 异常，需在正常网络下确认） |
| 11 | **Comet Opik 对标准 `gen_ai.*` 的渲染程度** | 其 OTel 文档**没有 gen_ai 属性映射表**，无法在选型阶段判断 Java 只发 OTel 能得到多好的呈现 → 必须跑 PoC |
| 12 | **Phoenix 服务端与 Spring Boot 3.5 的 OTel BOM 是否对齐** | `com.arize` 要求 OTel Java **1.49.0+**（Spring Boot 3.5.4 正好管理 1.49.0）；但 `opentelemetry-instrumentation-api:2.14.0` 与之是否共存需实测 |
| 13 | **裸 `input_tokens`/`output_tokens` 是否被各平台识别** | gen_ai 规范名是 `gen_ai.usage.*`；建议无论如何都补一份（§5.2 已含） |
| 14 | **LangSmith 是否支持 OTLP gRPC** | 官方只给了 HTTP 路径（`https://api.smith.langchain.com/otel/v1/traces`） |
| 15 | **Grafana Tempo / Grafana OSS 是否有 gen_ai 专属面板插件** | 官方文档 1.4 MB 索引中 `gen_ai` 出现 **0 次** → 默认没有 LLM 语义 UI，仅确认文档层面 |
| 16 | **Datadog 通用 OTLP 主机的确切 host/端口** | managed-platform 形式已确认；`otlp.datadoghq.com` 未从官方页确认 |
| 17 | **Langtrace / Traceloop / Uptrace / Honeycomb 是否渲染第三方标准 `gen_ai` span** | 均未找到明确文档 |

---

# 附录 A：一次性可复制的配置清单

**pom.xml 增量**
```xml
<!-- 方案 1（零风险预热，只有指标）：与现有 Grafana 直接配合 -->
<dependency>
  <groupId>dev.langchain4j</groupId><artifactId>langchain4j-micrometer-metrics</artifactId>
  <version>1.18.1-beta28</version>
</dependency>

<!-- 方案 2（正式接入，指标 + trace）：与方案 1 二选一，不要同时挂 -->
<dependency>
  <groupId>dev.langchain4j</groupId><artifactId>langchain4j-observation</artifactId>
  <version>1.18.1-beta28</version>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
  <groupId>io.micrometer</groupId><artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
  <groupId>io.opentelemetry</groupId><artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

**application.yml 增量**
```yaml
management:
  tracing:
    sampling: { probability: 1.0 }
  otlp:
    tracing:
      endpoint: ${LANGFUSE_OTLP_ENDPOINT:http://langfuse-web:3000/api/public/otel/v1/traces}
      transport: http
      compression: gzip
      headers:
        Authorization: "Basic ${LANGFUSE_AUTH_BASE64}"
        x-langfuse-ingestion-version: "4"
  observations:
    enable:
      http.server.requests: false
      http.client.requests: false
```

**docker-compose 增量（要点，非完整文件）**

> 下面用的是**官方 compose 的真实变量名**（已从原始文件逐项核对），但服务名做了 `lf-` 前缀以避免与现有 `redis`/`postgres` 撞名 —— **改名后必须同步改 `REDIS_HOST` / `DATABASE_URL` / `CLICKHOUSE_*` / `LANGFUSE_S3_*_ENDPOINT`**。
> 更省事的做法：把官方 compose **原样保留为独立 project**，只把 `3000` 反代出去。

```yaml
  lf-web:
    image: docker.langfuse.com/langfuse/langfuse:4
    restart: always
    ports: ["3000:3000"]                     # 只暴露 web；其余不映射到宿主机
    environment:
      # ---- 认证/加密（必填，全部用 .env 注入）----
      NEXTAUTH_URL: ${LANGFUSE_URL:-http://localhost:3000}
      NEXTAUTH_SECRET: ${LF_NEXTAUTH_SECRET}          # openssl rand -base64 32
      SALT: ${LF_SALT}                                # openssl rand -base64 32
      ENCRYPTION_KEY: ${LF_ENCRYPTION_KEY}            # openssl rand -hex 32（必须 64 hex）
      # ---- 事务库 ----
      DATABASE_URL: postgresql://postgres:${LF_PG_PASSWORD}@lf-postgres:5432/postgres
      # ---- ClickHouse ----
      CLICKHOUSE_URL: http://lf-clickhouse:8123
      CLICKHOUSE_MIGRATION_URL: clickhouse://lf-clickhouse:9000
      CLICKHOUSE_USER: clickhouse
      CLICKHOUSE_PASSWORD: ${LF_CH_PASSWORD}
      CLICKHOUSE_CLUSTER_ENABLED: "false"
      # ---- Redis（必须 noeviction，见官方 command）----
      REDIS_HOST: lf-redis
      REDIS_PORT: "6379"
      REDIS_AUTH: ${LF_REDIS_PASSWORD}
      # ---- S3 / Blob（event upload bucket 是 Mandatory）----
      LANGFUSE_S3_EVENT_UPLOAD_BUCKET: langfuse
      LANGFUSE_S3_EVENT_UPLOAD_REGION: auto
      LANGFUSE_S3_EVENT_UPLOAD_ACCESS_KEY_ID: minio
      LANGFUSE_S3_EVENT_UPLOAD_SECRET_ACCESS_KEY: ${LF_MINIO_PASSWORD}
      LANGFUSE_S3_EVENT_UPLOAD_ENDPOINT: http://lf-minio:9000
      LANGFUSE_S3_EVENT_UPLOAD_FORCE_PATH_STYLE: "true"
      LANGFUSE_S3_EVENT_UPLOAD_PREFIX: events/
      LANGFUSE_S3_MEDIA_UPLOAD_BUCKET: langfuse
      LANGFUSE_S3_MEDIA_UPLOAD_ACCESS_KEY_ID: minio
      LANGFUSE_S3_MEDIA_UPLOAD_SECRET_ACCESS_KEY: ${LF_MINIO_PASSWORD}
      LANGFUSE_S3_MEDIA_UPLOAD_FORCE_PATH_STYLE: "true"
      LANGFUSE_S3_MEDIA_UPLOAD_PREFIX: media/
      # 生产必须改成 SDK/浏览器可达地址，否则多模态上传失败
      LANGFUSE_S3_MEDIA_UPLOAD_ENDPOINT: ${LANGFUSE_MEDIA_ENDPOINT:-http://localhost:9090}
      LANGFUSE_S3_MEDIA_UPLOAD_INTERNAL_ENDPOINT: http://lf-minio:9000
      # ---- 无头初始化（值不要加双引号！issue #3398）----
      LANGFUSE_INIT_ORG_ID: ${LF_ORG_ID}
      LANGFUSE_INIT_PROJECT_ID: ${LF_PROJECT_ID}
      LANGFUSE_INIT_PROJECT_PUBLIC_KEY: ${LF_PK}
      LANGFUSE_INIT_PROJECT_SECRET_KEY: ${LF_SK}
      LANGFUSE_INIT_USER_EMAIL: ${LF_ADMIN_EMAIL}
      LANGFUSE_INIT_USER_PASSWORD: ${LF_ADMIN_PASSWORD}
      TELEMETRY_ENABLED: "true"
    networks: [app-net, langfuse-net]

  lf-redis:
    image: docker.io/redis:7
    command: >-
      --requirepass ${LF_REDIS_PASSWORD}
      --maxmemory-policy noeviction          # ← 硬要求
    networks: [langfuse-net]

  lf-postgres:
    image: docker.io/postgres:17
    environment:
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: ${LF_PG_PASSWORD}
      POSTGRES_DB: postgres
      TZ: UTC                                 # ← Langfuse 要求 UTC
      PGTZ: UTC
    volumes: [lf_pg_data:/var/lib/postgresql/data]
    networks: [langfuse-net]

  lf-clickhouse:
    image: docker.io/clickhouse/clickhouse-server:25.12
    user: "101:101"
    environment:
      CLICKHOUSE_DB: default
      CLICKHOUSE_USER: clickhouse
      CLICKHOUSE_PASSWORD: ${LF_CH_PASSWORD}
    volumes:
      - lf_ch_data:/var/lib/clickhouse
      - lf_ch_logs:/var/log/clickhouse-server
      # 建议挂上系统日志表治理配置，见 §1.3.3
      # - ./clickhouse/config.d/no-system-logs.xml:/etc/clickhouse-server/config.d/no-system-logs.xml:ro
    networks: [langfuse-net]

  lf-minio:
    image: cgr.dev/chainguard/minio
    entrypoint: sh
    command: -c 'mkdir -p /data/langfuse && minio server --address ":9000" --console-address ":9001" /data'
    environment:
      MINIO_ROOT_USER: minio
      MINIO_ROOT_PASSWORD: ${LF_MINIO_PASSWORD}
    volumes: [lf_minio_data:/data]
    networks: [langfuse-net]
```

`langfuse-worker`（镜像 `docker.langfuse.com/langfuse/langfuse-worker:4`）需要与 web **完全相同**的那批环境变量（官方用 YAML anchor `&langfuse-worker-env` 共享），端口 `127.0.0.1:3030:3030`，**不要漏掉**——worker 才是真正消费队列、写 ClickHouse 的组件，只起 web 会看不到数据。

**backend 侧需要新增的环境变量**：
```bash
LANGFUSE_OTLP_ENDPOINT=http://lf-web:3000/api/public/otel/v1/traces   # 注意带 /v1/traces
LANGFUSE_AUTH_BASE64=$(printf '%s' "${LF_PK}:${LF_SK}" | base64)
```
并把 `backend` 加入 `app-net` + `langfuse-net`（或至少能解析到 `lf-web`）。

---

# 附录 B：关键信息来源索引

| 主题 | URL |
|---|---|
| Langfuse v4 changelog | https://langfuse.com/changelog/2026-08-17-langfuse-v4 |
| 数据模型 | https://langfuse.com/docs/observability/data-model |
| OTel 接入（属性映射全表） | https://langfuse.com/integrations/native/opentelemetry |
| 废弃 API 迁移 | https://langfuse.com/faq/all/deprecated-api-migration |
| Ingestion 规格（legacy） | https://raw.githubusercontent.com/langfuse/langfuse/main/fern/apis/server/definition/ingestion.yml |
| 观测类型映射源码 | https://raw.githubusercontent.com/langfuse/langfuse/main/packages/shared/src/server/otel/ObservationTypeMapper.ts |
| Langfuse 属性常量源码 | https://raw.githubusercontent.com/langfuse/langfuse/main/packages/shared/src/server/otel/attributes.ts |
| 自托管 docker compose | https://langfuse.com/self-hosting/deployment/docker-compose |
| 官方 compose 文件（224 行，逐项核对） | https://raw.githubusercontent.com/langfuse/langfuse/main/docker-compose.yml |
| 应用容器与规格 | https://langfuse.com/self-hosting/deployment/infrastructure/containers |
| 最低基础设施要求 / 扩容 / ClickHouse TTL / 系统日志表 | https://langfuse.com/self-hosting/configuration/scaling |
| ClickHouse 组件说明 | https://langfuse.com/self-hosting/deployment/infrastructure/clickhouse |
| Redis/Valkey 组件（noeviction、1GB/10万事件每分钟） | https://langfuse.com/self-hosting/deployment/infrastructure/cache |
| S3 兼容对象存储 | https://langfuse.com/self-hosting/deployment/infrastructure/blobstorage |
| 环境变量总表 | https://langfuse.com/self-hosting/configuration |
| 无头初始化 | https://langfuse.com/self-hosting/administration/headless-initialization |
| 健康/就绪端点 | https://langfuse.com/self-hosting/configuration/health-readiness-endpoints |
| 备份策略 | https://langfuse.com/self-hosting/configuration/backups |
| 自定义 base path | https://langfuse.com/self-hosting/configuration/custom-base-path |
| v3→v4 自托管升级 | https://langfuse.com/self-hosting/upgrade/upgrade-guides/upgrade-v3-to-v4 |
| 版本兼容矩阵 | https://langfuse.com/docs/compatibility |
| ClickHouse 工程博客 | https://langfuse.com/resources/engineering/clickhouse-at-agent-scale |
| ClickHouse 系统表膨胀（社区） | https://github.com/langfuse/langfuse/issues/13123 |
| ClickHouse 无法从 S3 回灌（官方回复） | https://github.com/orgs/langfuse/discussions/4763 |
| 数据保留 | https://langfuse.com/docs/administration/data-retention |
| 计费单位 | https://langfuse.com/docs/administration/billable-units |
| 定价 | https://langfuse.com/pricing |
| Spring AI 集成（Java 配置范例） | https://langfuse.com/integrations/frameworks/spring-ai |
| Quarkus LangChain4j 集成 | https://langfuse.com/integrations/frameworks/quarkus-langchain4j |
| langfuse-java 客户端 | https://github.com/langfuse/langfuse-java |
| langchain4j-observation（Maven） | https://repo1.maven.org/maven2/dev/langchain4j/langchain4j-observation/1.18.1-beta28/ |
| langchain4j-observation（源码） | https://github.com/langchain4j/langchain4j/tree/main/langchain4j-observation |
| LangChain4j 可观测性文档 | https://docs.langchain4j.dev/tutorials/observability |
| LangChain4j Langfuse issue | https://github.com/langchain4j/langchain4j/issues/2328 |
| Spring Boot 3.5 Tracing | https://docs.spring.io/spring-boot/3.5/reference/actuator/tracing.html |
| Arize OpenInference Java | https://repo1.maven.org/maven2/com/arize/ |
