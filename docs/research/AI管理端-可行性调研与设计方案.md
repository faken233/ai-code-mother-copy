# AI 管理端可行性调研与设计方案

> ⚠️ **本文档部分内容已作废（2026-09-11）**
> 需求方已确认：**第 2 节「全局模型管控」不再需要**，继续走环境变量硬编码模型名（DeepSeek）。
> 监管需求也已重新聚焦为「任务级轨迹 + token + 成本」并重写。
> **请以 `docs/research/任务监管端-可行性与设计方案.md` 为准。**
> 本文档保留 §1（现状盘点）、§3（Langfuse 接入）、§4（闭环设计）作为背景参考。

> 目标：评估在「AI 零代码应用生成平台」上新增一个**平台管理端**的可行性，覆盖两个诉求：
> 1. **全局控制平台执行任务时可以使用的模型**
> 2. **监管每个任务的 AI 执行流程（Langfuse）**
>
> 调研时间：2026-09-11
> 代码基线：`main` 分支，Spring Boot 3.5.4 / Java 21 / LangChain4j 1.18.1 / LangGraph4j 1.6.0-rc2
> 配套深度报告：`docs/research/langfuse-接入调研报告.md`（Langfuse 侧的源码级查证、maven 坐标实测、40+ 来源 URL）

---

## 0. 结论速览

**两个需求都可做，而且平台已经具备大部分地基，属于"中等改造"而非"重建"。**

| 需求 | 可行性 | 核心改造 | 预估工作量 |
|---|---|---|---|
| ① 全局模型管控 | ✅ 高 | 新增模型注册表 + 1 个动态工厂 + 改造 5 处模型注入点 | 后端 3–4 人日，前端 1.5 人日 |
| ② Langfuse 流程监管 | ✅ 高 | 加 3 个 maven 依赖 + 1 个 ObservationFilter + 业务埋点 | 4 人日（含自托管部署） |
| ③ 管理端 UI 外壳 | ✅ 高 | 复用现有 admin 页面/路由/鉴权/`openapi2ts` 代码生成 | 0.5 人日 |

**四个关键判断：**

1. **模型装配目前是"启动期静态固化"的**——全部来自 `.env` → `application.yml` → Spring Bean，运行期没有任何干预点。要做"管理端控制"，必须引入**运行时可重建的模型注册表**，这是需求①的全部难点所在。

2. **Langfuse 不要自己写信道**。调研查证到 LangChain4j 官方有一个被忽略的模块 `dev.langchain4j:langchain4j-observation:1.18.1-beta28`，其依赖的 `langchain4j-core:1.18.1` **与本项目版本完全一致**，它能把每次模型调用自动变成 Micrometer Observation → OTel span → OTLP → Langfuse。**Langfuse 服务端会把它识别为 `GENERATION` 类型**（已核对 Langfuse 源码 `ObservationTypeMapper.ts`）。现有 `AiModelMonitorListener` 一行都不用改。

3. **两个需求天然应该打通的点**：模型注册表选中的"模型配置"必须写进监控上下文（`MonitorContext`），这样在 Langfuse 里能按"管理端配的哪个模型"筛选任务，在管理端能从任务一键跳转到 Langfuse trace。**这是把"管控"和"监管"闭环的关键设计**，见 §4。

4. **最大风险不在代码，在运维**：Langfuse 自托管需要 6 个容器（ClickHouse/Postgres/Redis/MinIO/web/worker），官方建议 ≥4C/16G/100G，且 **self-host 默认永不删数据、内置 Data Retention 属企业版功能**，必须自己上 ClickHouse TTL。若不想背这个包袱，可先用 Langfuse Cloud（Hobby 免费 50k units/月），代码侧接法完全不变。

5. **需求②可以分两步走，第一步零风险**：LangChain4j 还有一个版本完全对齐的官方构件 `dev.langchain4j:langchain4j-micrometer-metrics:1.18.1-beta28`，**单独加它就能在现有 Grafana 上看到按模型分组的 token 曲线，零新增组件、不碰 Langfuse、不改业务代码**（§3.2）。建议先花半天摸清成本基线，再决定是否上 Langfuse。

---

## 1. 现状盘点（代码级证据）

### 1.1 模型装配链路：4 条链路，全部静态

| # | 用途 | 产生方式 | 配置来源 | 注入点 |
|---|---|---|---|---|
| 1 | 主对话 / 工具调用 `ChatModel` | `langchain4j-open-ai-spring-boot-starter` 的 `AutoConfig` 自动装配 | yml `langchain4j.open-ai.chat-model` | `AiCodeGeneratorServiceFactory:31`<br>`CodeQualityCheckServiceFactory:17`<br>`ImageCollectionPlanServiceFactory:15`<br>`ImageCollectionServiceFactory:21` |
| 2 | 流式生成 `StreamingChatModel` | 同上 | yml `langchain4j.open-ai.streaming-chat-model` | `AiCodeGeneratorServiceFactory:39` |
| 3 | 路由分类模型 | **手写** `RoutingAiModelConfig.routingChatModelPrototype()`（`@Scope("prototype")`） | yml `langchain4j.open-ai.routing-chat-model` | `AiCodeGenTypeRoutingServiceFactory:24`（`SpringContextUtil.getBean("routingChatModelPrototype")`） |
| 4 | 图片生成 | DashScope SDK | `dashscope.image-model` | `ImageCollectionService` |

> **结论**：模型名/baseUrl/apiKey/温度/超时 **100% 来自环境变量**，改任何一个都必须重启服务。运行期不存在任何"哪些模型可用"的概念——这就是需求①要解决的问题。

### 1.2 任务执行链路：已经有清晰的生命周期

- `gen_task` 表（`sql/gen_task_table.sql`）承载任务全生命周期：`pending/running/completed/failed/cancelled` + `queuePosition` + `generatedContent` + `errorMessage` + 起止时间。
- `GenTaskExecutor`（445 行）用 Reactor `Sinks.Many` + `flatMap` 控制并发（`gen-task.max-concurrent: 3`），并在 `:218-225` 处**已经设置了监控上下文**、`:271` 处清理。
- 两套执行路径：
  - **直接生成**：`AiCodeGeneratorFacade` → `AiCodeGeneratorServiceFactory` → LangChain4j `AiServices`
  - **工作流生成**：LangGraph4j `CodeGenWorkflow`，6 个节点 `image_collector → prompt_enhancer → router → code_generator → code_quality_check → project_builder`

> **结论**：需求②要的"每个任务的 AI 执行流程"，**任务边界已经现成**，缺的只是"把流程可视化"。

### 1.3 监控现状：有指标，没有链路

| 已有能力 | 位置 | 说明 |
|---|---|---|
| `ChatModelListener` 埋点 | `monitor/AiModelMonitorListener.java` | 重写 `onRequest/onResponse/onError`，采集请求数/错误数/Token/响应时长 |
| 指标收集器 | `monitor/AiModelMetricsCollector.java` | Micrometer `Counter`/`Timer`，注册为 `ai_model_requests_total` 等 |
| 业务上下文 | `monitor/MonitorContext` + `MonitorContextHolder` | **只有 `userId` 和 `appId`**，ThreadLocal |
| 指标后端 | `pom.xml` + `prometheus.yml` + `grafana/ai_model_grafana_config.json` | Prometheus 抓 `/api/actuator/prometheus`，Grafana 面板 |
| Langfuse / OTel / tracing | **无** | 全仓库 grep 无任何相关依赖或代码 |

> **结论**：现在是**聚合指标**（"今天调了 1000 次模型"），不是**执行链路**（"这个任务为什么失败、卡在哪一步、prompt 是什么"）。需求②补的正是后者。
>
> **而且**：`AiModelMonitorListener` 是实现 `ChatModelListener` 的 Spring Bean，而 starter 的 `AutoConfig` 用 `ObjectProvider<ChatModelListener>` 自动挂载所有 listener——**这是一个已经铺好的扩展点**，接 Langfuse 时天然复用。

### 1.4 LangChain4j 1.18.1 自带的可观测性 API（重要，往往是盲区）

反编译 `langchain4j-core-1.18.1.jar` 确认，包 `dev.langchain4j.observability` 已经提供完整的事件体系：

| 事件 | 触发时机 | 可用于 |
|---|---|---|
| `AiServiceStartedEvent` | AI Service 方法调用开始 | 创建 root span |
| `AiServiceRequestIssuedEvent` | 每次发往模型前 | generation span 开始 |
| `AiServiceResponseReceivedEvent` | 收到模型响应 | generation span 结束 |
| `AiServiceCompletedEvent` | AI Service 调用结束 | 结束 root span |
| `AiServiceErrorEvent` | 调用异常 | 错误标记 |
| `ToolExecutedEvent` | 每次工具执行后 | **工具调用 span（写文件/读文件/构建项目）** |
| `GuardrailExecutedEvent` / `InputGuardrailExecutedEvent` / `OutputGuardrailExecutedEvent` | 护轨执行 | 安全检查 span |

并且 `InvocationContext`（`dev.langchain4j.invocation`）提供：

```java
UUID    invocationId();     // ← 单次 AI Service 调用的唯一 ID，天然的 trace id
String  interfaceName();
String  methodName();
Object  chatMemoryId();     // ← 本项目里 = appId（MessageWindowChatMemory.id(appId)）
```

注册方式（`AiServices` 实例方法，`langchain4j-1.18.1`）：

```java
AiServices.builder(MyService.class).registerListener(myListener).build();
AiServices.builder(MyService.class).registerListeners(l1, l2).build();
```

> **结论**：即使不接 Langfuse，这套 API 本身也值得用——它能让"每个任务的 AI 执行流程"在**不引入任何外部系统**的前提下先跑通（LangGraph4j 节点 span + 工具调用 span），再决定往哪个后端发。设计上应把"埋点"和"上报"解耦。

### 1.5 管理端地基：已有 80%

| 能力 | 位置 | 复用方式 |
|---|---|---|
| 管理员鉴权注解 | `annotation/AuthCheck.java` + `aop/AuthInterceptor.java` | 新接口直接标 `@AuthCheck(mustRole = UserConstant.ADMIN_ROLE)` |
| 角色常量 | `constant/UserConstant.ADMIN_ROLE` | `user.userRole = 'admin'` |
| 后端管理接口范式 | `UserController` / `AppController` / `ChatHistoryController` 的 `/admin/*` | 照抄分页 + VO 模式 |
| 前端管理页面 | `pages/admin/{UserManagePage,AppManagePage,ChatManagePage}.vue` | 照抄 Ant Design Vue 表格 + 搜索表单 |
| 前端路由 | `router/index.ts` 已有 `/admin/userManage` 等 | 加 `/admin/modelManage`、`/admin/traceManage` |
| 前端 API 自动生成 | `openapi2ts.config.ts`（读 `/api/v3/api-docs`） | 后端写完 Controller 后 `npm run openapi2ts` 自动生成 TS client |
| 分布式能力 | Redisson（`ratelimter/config/RedissonConfig.java`） | 用 `RTopic` 做多实例配置失效广播 |
| 配置缓存 | `RedisCacheManagerConfig` | 模型配置可加 `@Cacheable` |

> ⚠️ **一个现存的安全缺口**：前端 `router/index.ts` 里的 `/admin/*` 路由**没有任何前端访问守卫**，仅靠后端 `@AuthCheck` 拦截。新增管理端页面时应一并补上路由守卫（`meta.requiresAdmin` + `beforeEach`），否则普通用户能打开页面看到空数据/报错，体验与安全都不好。

---

## 2. 需求①：全局模型管控

### 2.1 目标拆解

| 层级 | 目标 | 优先级 |
|---|---|---|
| L1 | 平台维护一个**可用模型池**，管理员可增删改查、启用/禁用 | P0 |
| L2 | **场景绑定**：指定"代码生成 / 路由判断 / 代码质检 / 图片规划 / 图片生成"分别用哪个模型 | P0 |
| L3 | **运行期热生效**：改完立即对下一个任务生效，无需重启 | P0 |
| L4 | **多实例一致**：改一次，所有后端实例同步 | P1 |
| L5 | **连通性自检**：保存前能一键测试模型是否可用、是否支持工具调用 | P1 |
| L6 | **降级兜底**：管理端配错不能让整个平台挂掉 | P0（硬要求） |
| L7 | 用户/应用级模型覆盖、成本核算、A/B 实验 | P2 |

### 2.2 方案对比

| 维度 | A. 自建模型注册表 | B. 外部 AI 网关 | C. A + B 组合 |
|---|---|---|---|
| 代表实现 | 本项目自研 | one-api / new-api / LiteLLM / Higress AI 网关 | 注册表管策略，网关管上游 |
| 实现方式 | DB 存模型配置 + 动态工厂重建 Bean | 平台 `baseUrl` 指向网关，模型切换在网关做 | 平台只存"逻辑模型名"，网关做多渠道/负载均衡 |
| 改造量 | 中（改 5 处注入点） | **极小**（只改 `.env` 的 `LLM_BASE_URL`） | 中 |
| 管控粒度 | 场景级、用户级、应用级 | 仅网关侧的 key/渠道级 | 最细 |
| 与业务埋点结合 | ✅ 可把 `modelConfigId` 写进 trace | ❌ 平台不知道最终用了哪个上游 | ✅ |
| 多实例一致性 | 需自己做（Redis 广播） | 网关天然统一 | 混合 |
| 平台可用性风险 | 配错可能影响全站（需兜底） | 网关挂了全站挂 | 中 |
| 运维成本 | 低 | 中（多一个网关要运维） | 中高 |
| 密钥管理 | 平台自己加密存库 | 集中在网关（更安全） | 网关集中 |

**推荐：先做 A（P0），把 B 作为可选的演进方向。**

理由：
- 平台当前只有 3 个模型、4 条链路，**网关带来的收益（多渠道故障转移、计费）暂时用不上**，但会引入一个额外的运维组件和一次网络跳转。
- 需求明确说的是"**全局控制平台执行任务时可以使用的模型**"——这是**平台侧策略**，天然应该在平台自己的管理端里；网关做不到"按任务/用户/应用维度选模型"这种业务级策略。
- 一旦未来上游 key 池化需求出现（例如多个 DeepSeek key 轮询、按量计费），可以平滑升级为 C：注册表里存"逻辑模型名"，`baseUrl` 指向网关。

### 2.3 推荐设计（方案 A）

#### 2.3.1 数据模型

两张表：**模型池**与**场景绑定**解耦。一个模型可服务多个场景，一个场景可绑多个模型（按 `priority` 组成降级链）。

```sql
-- 模型池
create table if not exists ai_model_config
(
    id                bigint auto_increment comment 'id' primary key,
    name              varchar(128)  not null comment '展示名，如 DeepSeek-V3 / Qwen-Turbo',
    provider          varchar(64)   not null default 'openai-compatible' comment '协议类型：openai-compatible/dashscope',
    base_url          varchar(512)  not null comment 'API Base URL',
    api_key_cipher    varchar(1024) not null comment 'API Key（AES-GCM 加密，主密钥走环境变量）',
    api_key_masked    varchar(128)  not null comment '脱敏展示用，如 sk-****abcd',
    model_name        varchar(128)  not null comment '模型标识（传给上游的 model 字段）',
    supports_streaming tinyint      default 1 not null comment '是否支持流式',
    supports_tools    tinyint       default 1 not null comment '是否支持工具调用（VUE/FULLSTACK 生成必需）',
    supports_thinking tinyint       default 0 not null comment '是否支持思考链',
    max_tokens        int           default 8192  not null,
    temperature       decimal(3, 2) default 0.70  not null,
    top_p             decimal(3, 2) default 1.00  not null,
    reasoning_effort  varchar(16)   default 'low' not null comment 'low/medium/high',
    timeout_seconds   int           default 300   not null,
    status            tinyint       default 1     not null comment '1=启用 0=禁用（全局熔断开关）',
    remark            varchar(512)  null,
    creator_id        bigint        not null comment '创建管理员 id',
    create_time       datetime      default CURRENT_TIMESTAMP not null,
    update_time       datetime      default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    is_delete         tinyint       default 0     not null,
    UNIQUE KEY uk_name (name, is_delete),
    INDEX idx_status (status)
) comment 'AI 模型配置' collate = utf8mb4_unicode_ci;

-- 场景绑定（决定"哪个场景用哪个模型"，priority 越大越优先，构成降级链）
create table if not exists ai_scene_model_binding
(
    id          bigint auto_increment comment 'id' primary key,
    scene       varchar(64) not null comment '场景枚举：CODE_GEN/ROUTING/QUALITY_CHECK/IMAGE_PLAN/IMAGE_GEN',
    model_id    bigint      not null comment 'ai_model_config.id',
    priority    int         default 0 not null comment '降级链优先级，大者优先',
    enabled     tinyint     default 1 not null,
    create_time datetime    default CURRENT_TIMESTAMP not null,
    update_time datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    UNIQUE KEY uk_scene_model (scene, model_id),
    INDEX idx_scene_priority (scene, priority)
) comment 'AI 场景-模型绑定' collate = utf8mb4_unicode_ci;

-- 变更审计（模型开关是高风险操作，必须留痕）
create table if not exists ai_model_config_log
(
    id            bigint auto_increment comment 'id' primary key,
    model_id      bigint       null comment '被操作模型 id（新建时为 null）',
    action        varchar(32)  not null comment 'CREATE/UPDATE/DELETE/TOGGLE/BIND/UNBIND',
    before_value  text         null comment '变更前快照（JSON，apiKey 脱敏）',
    after_value   text         null comment '变更后快照（JSON，apiKey 脱敏）',
    operator_id   bigint       not null comment '操作管理员 id',
    operator_ip   varchar(64)  null,
    create_time   datetime     default CURRENT_TIMESTAMP not null,
    INDEX idx_model_id (model_id),
    INDEX idx_create_time (create_time)
) comment 'AI 模型配置变更审计' collate = utf8mb4_unicode_ci;
```

**场景枚举**（`ModelSceneEnum`）与现有链路一一对应：

| 枚举 | 对应链路 | 现状来源 |
|---|---|---|
| `CODE_GEN` | `AiCodeGeneratorServiceFactory` 的 chat + streaming | `LLM_CHAT_MODEL` / `LLM_STREAMING_MODEL` |
| `ROUTING` | `AiCodeGenTypeRoutingServiceFactory` | `LLM_ROUTING_MODEL` |
| `QUALITY_CHECK` | `CodeQualityCheckServiceFactory` | `LLM_CHAT_MODEL` |
| `IMAGE_PLAN` | `ImageCollectionPlanServiceFactory` | `LLM_CHAT_MODEL` |
| `IMAGE_GEN` | `ImageCollectionService`（DashScope） | `DASHSCOPE_IMAGE_MODEL` |

#### 2.3.2 核心组件

```
com.yupi.yuaicodemother.modelconfig/
├── enums/ModelSceneEnum.java          场景枚举
├── entity/AiModelConfig.java          MyBatis-Flex 实体（与现有 model/entity 一致）
├── AiModelRegistry.java         ★核心：运行期模型注册表
├── AiModelFactory.java               从配置构建 ChatModel/StreamingChatModel
├── ModelSecretCodec.java             API Key AES-GCM 加解密
└── event/ModelConfigChangedEvent.java 配置变更事件
```

**`AiModelRegistry` 的关键设计：**

```java
@Component
@Slf4j
public class AiModelRegistry {

    @Resource private AiModelConfigService configService;
    @Resource private AiModelFactory modelFactory;

    /** 运行期构建的模型实例缓存：modelConfigId -> 实例 */
    private final ConcurrentMap<Long, ModelHolder> cache = new ConcurrentHashMap<>();

    /** 全局版本号：任何配置变更都 +1，用于让上层缓存失效 */
    private final AtomicLong revision = new AtomicLong(0);

    /** 兜底模型：registry 不可用/未配置时回退到 starter 装配的 yml 模型 */
    @Resource(name = "openAiChatModel")          private ChatModel fallbackChatModel;
    @Resource(name = "openAiStreamingChatModel") private StreamingChatModel fallbackStreamingModel;

    /** 按场景取 ChatModel（含降级链：按 priority 依次尝试构建） */
    public ChatModel getChatModel(ModelSceneEnum scene) {
        return resolve(scene).chatModel();
    }

    public StreamingChatModel getStreamingChatModel(ModelSceneEnum scene) {
        return resolve(scene).streamingModel();
    }

    private ModelHolder resolve(ModelSceneEnum scene) {
        try {
            List<AiModelConfig> candidates = configService.listEnabledByScene(scene); // 已按 priority 倒序
            for (AiModelConfig config : candidates) {
                try {
                    return cache.computeIfAbsent(config.getId(), id -> modelFactory.build(config));
                } catch (Exception e) {
                    log.error("模型 {} 构建失败，尝试降级链下一项", config.getName(), e);
                }
            }
        } catch (Exception e) {
            log.error("读取模型配置失败，回退到 yml 兜底模型", e);
        }
        return ModelHolder.fallback(fallbackChatModel, fallbackStreamingModel); // ★ 永不返回 null
    }

    /** 配置变更后由管理端调用（本机），并广播到其它实例 */
    public void invalidateAll() {
        revision.incrementAndGet();
        cache.clear();
        log.info("模型注册表已刷新，revision={}", revision.get());
    }

    public long getRevision() { return revision.get(); }
}
```

**`AiModelFactory` 必须显式挂上 listener**（因为不再走 starter 的自动装配）：

```java
@Component
public class AiModelFactory {

    /** 复刻 starter AutoConfig 的行为：收集容器里所有 ChatModelListener */
    @Resource private ObjectProvider<ChatModelListener> listeners;
    @Resource private ModelSecretCodec codec;

    public ModelHolder build(AiModelConfig c) {
        String apiKey = codec.decrypt(c.getApiKeyCipher());
        List<ChatModelListener> ls = listeners.orderedStream().toList();

        ChatModel chat = OpenAiChatModel.builder()
                .baseUrl(c.getBaseUrl()).apiKey(apiKey).modelName(c.getModelName())
                .maxTokens(c.getMaxTokens()).temperature(c.getTemperature().doubleValue())
                .topP(c.getTopP().doubleValue())
                .timeout(Duration.ofSeconds(c.getTimeoutSeconds()))
                .reasoningEffort(c.getReasoningEffort())
                .listeners(ls)                     // ★ 关键：否则监控/Tracing 全部丢失
                .build();

        StreamingChatModel streaming = c.getSupportsStreaming() == 1
                ? OpenAiStreamingChatModel.builder()/* 同上 */.listeners(ls).build()
                : null;

        return new ModelHolder(chat, streaming, c.getId(), c.getName());
    }
}
```

#### 2.3.3 需要改造的注入点（精确清单）

| 文件 | 现状 | 改为 |
|---|---|---|
| `ai/AiCodeGeneratorServiceFactory.java:31,39` | `@Resource(name="openAiChatModel"/"openAiStreamingChatModel")` | `registry.getChatModel(CODE_GEN)` / `getStreamingChatModel(CODE_GEN)` |
| `langgraph4j/ai/CodeQualityCheckServiceFactory.java:17` | `@Resource(name="openAiChatModel")` | `registry.getChatModel(QUALITY_CHECK)` |
| `langgraph4j/ai/ImageCollectionPlanServiceFactory.java:15` | `@Resource(name="openAiChatModel")` | `registry.getChatModel(IMAGE_PLAN)` |
| `langgraph4j/ai/ImageCollectionServiceFactory.java:21` | `@Resource(name="openAiChatModel")` | `registry.getChatModel(IMAGE_PLAN)` |
| `ai/AiCodeGenTypeRoutingServiceFactory.java:24` | `SpringContextUtil.getBean("routingChatModelPrototype")` | `registry.getChatModel(ROUTING)`（`RoutingAiModelConfig` 保留为 yml 兜底） |
| `config/RoutingAiModelConfig.java:57` | 手写 builder，**没有 `.listeners(...)`** | 补 `.listeners(observationChatModelListener)`（顺带修复监控缺失） |
| `monitor/MonitorContext.java` | 只有 `userId`/`appId` | 增加 `taskId`/`codeGenType`/`modelConfigId`/`modelName`/`traceId` |
| `task/GenTaskExecutor.java:219` | 设置 `MonitorContext` | 补上 `taskId`/`codeGenType`/选中模型信息（见 §4） |

#### 2.3.4 ⚠️ 三个必须处理的"热生效"陷阱

这是本方案最容易做错的地方，单独列出：

**陷阱 1：`AiCodeGeneratorServiceFactory` 的 Caffeine 缓存会持有旧模型引用**

`AiCodeGeneratorServiceFactory:58-63` 用 `serviceCache`（最大 1000、写后 30 分钟过期）缓存 `AiCodeGeneratorService` 实例，而每个实例内部**持有构建时的 model 引用**。管理端换了模型后，缓存内的旧实例会继续用旧模型最多 30 分钟 —— **"热生效"直接失效**。

修复（二选一）：
```java
// 方案 1（推荐）：缓存 key 带上 registry 版本号，revision 变了自然 miss
private String buildCacheKey(long appId, CodeGenTypeEnum type) {
    return appId + "_" + type.getValue() + "_" + registry.getRevision();
}

// 方案 2：监听配置变更事件，主动清缓存
@EventListener
public void onModelConfigChanged(ModelConfigChangedEvent e) {
    serviceCache.invalidateAll();
}
```
> 建议两个都做：方案 1 保证正确性，方案 2 及时释放内存。

**陷阱 2：多实例不一致**

`GenTaskExecutor` 是每个实例各自轮询的（`gen-task.poll-interval: 2000`），任务可能落在任意实例上。A 实例改了配置，B 实例的缓存不会自动失效。

修复：项目已引入 Redisson，用 `RTopic` 广播：
```java
// 变更方
redissonClient.getTopic("ai:model:config:changed").publish(revision);
// 订阅方（启动时注册）
redissonClient.getTopic("ai:model:config:changed")
    .addListener(Long.class, (ch, rev) -> registry.invalidateAll());
```
单实例部署时可跳过，但**代码结构上应预留**，避免后续扩容时踩坑。

**陷阱 3：降级兜底不能省**

管理端是"全站模型的总开关"，一旦管理员配错（baseUrl 写错 / key 失效 / 模型名不存在），**所有用户的任务都会失败**。因此：

- `resolve()` 内部逐级降级，最差情况回退到 starter 装配的 yml 模型（见上面代码的 `ModelHolder.fallback`）；
- 管理端**保存前强制自检**（`POST /admin/model/test`）：发一条 `"ping"` 消息，验证 ①能收到响应 ②返回耗时 ③对 CODE_GEN 场景额外验证**是否支持工具调用**（不支持就不能用于 VUE/FULLSTACK 生成）；
- `status=0`（禁用）是**全局熔断开关**，应对上游故障时的紧急止损。

#### 2.3.5 API Key 安全

- 存储：AES-256-GCM 加密，主密钥来自环境变量 `MODEL_SECRET_KEY`（**不落库、不进 git**），库里只存密文 + 脱敏串。
- 传输：接口返回一律用 `api_key_masked`，**永不回显明文**；更新时若该字段为空则保留原值。
- 权限：所有 `/admin/model/*` 接口标 `@AuthCheck(mustRole = ADMIN_ROLE)`。
- 审计：所有写操作落 `ai_model_config_log`。

#### 2.3.6 管理端接口设计

统一挂 `/api/admin/model`，全部 `@AuthCheck(mustRole = ADMIN_ROLE)`：

| 方法 | 路径 | 说明 | 优先级 |
|---|---|---|---|
| POST | `/list/page` | 分页查询模型池 | P0 |
| POST | `/create` | 新建模型 | P0 |
| POST | `/update` | 更新模型 | P0 |
| POST | `/delete` | 逻辑删除 | P0 |
| POST | `/toggle` | 启用/禁用（全局熔断） | P0 |
| POST | `/test` | **连通性 + 能力自检** | P0 |
| GET | `/scene/list` | 查询场景绑定关系 | P0 |
| POST | `/scene/bind` | 绑定场景（含 priority） | P0 |
| POST | `/scene/unbind` | 解绑 | P0 |
| GET | `/revision` | 查询当前配置版本（前端轮询用） | P1 |
| POST | `/usage/stat` | 模型用量统计（读 Prometheus 或聚合表） | P1 |

#### 2.3.7 前端页面

`pages/admin/ModelManagePage.vue`，路由 `/admin/modelManage`，Ant Design Vue：

- **模型池表格**：名称 / 供应商 / 模型标识 / 场景标签 / 状态开关 / 操作（编辑、测试、删除）
- **新建/编辑抽屉**：表单字段对应表结构；`apiKey` 输入框 placeholder 显示脱敏值
- **场景绑定区**：5 个场景各一个下拉 + 优先级拖拽排序（体现降级链）
- **"测试"按钮**：点击后展示 ✅/❌ + 延迟 + 是否支持工具调用
- **顶部告警**：当某场景没有任何可用模型时高亮提示（防止"静默降级到 yml 兜底"而管理员不知情）

写完 Controller 后执行 `npm run openapi2ts` 自动生成 `api/aiModelConfigController.ts`。

#### 2.3.8 进阶能力（P2，按需）

- **用户/应用级覆盖**：`app_model_binding(appId, scene, modelId)`，实现"某应用的生成走更强模型"
- **成本核算**：模型表加 `input_price`/`output_price`，`gen_task` 加 `prompt_tokens`/`completion_tokens`/`cost_amount`，管理端出成本报表
- **模型级限流**：复用现有 `ratelimter` 包（Redisson `RRateLimiter`），按模型配 QPS
- **A/B 实验**：按 `userId` hash 分流到不同模型，用 Langfuse 对比成功率与成本
- **自动降级链**：已在 §2.3.2 的 `resolve()` 里预留（priority 排序 + 逐个 try）

---

## 3. 需求②：Langfuse 任务 AI 流程监管

> 本节是结论摘要，**源码级查证、maven 坐标实测、完整代码片段、40+ 来源 URL、自托管部署逐服务解析**全部在配套文档 `docs/research/langfuse-接入调研报告.md`（1696 行）。

### 3.1 选型结论

**主选 Langfuse，走「`langchain4j-observation` + Micrometer Observation + OTLP」路线。**

理由：
1. 它是唯一同时满足「OTLP 原生可自托管」「有完整 LLM 语义（generation / token / 成本 / prompt / 评分 / 数据集）」「已源码验证能正确识别本项目的埋点产物」三个条件的方案。
2. 适配成本最低：`dev.langchain4j:langchain4j-observation:1.18.1-beta28` 依赖的 `langchain4j-core` 正是 **1.18.1**，与本项目 `${langchain4j.version}` 完全一致。
3. trace 里含用户需求与生成代码，自托管可保证数据不出内网。

### 3.2 ⭐ 第一步：零风险的 token 指标（可选，本周可做）

LangChain4j 还发布了**第二个**与本项目版本线对齐的官方可观测性构件：

```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-micrometer-metrics</artifactId>
    <version>1.18.1-beta28</version>
</dependency>
```

> 已核对 Maven Central：版本 `1.12.1-beta21 … 1.20.0-beta30` 齐全，`1.18.1-beta28` 的 POM 依赖 `langchain4j-core:1.18.1` + `micrometer-core:1.16.5`，**与本项目完全对齐**。

它提供 `MicrometerMetricsChatModelListener`，往**现有的 `MeterRegistry`** 写 `gen_ai.client.token.usage` DistributionSummary，标签为 `gen_ai.operation.name` / `provider.name` / `request.model` / `response.model` / `token.type`(input|output)。

**价值：零新增组件、不碰 Langfuse、不改业务代码**，就能在现有 Grafana 上立刻拿到按模型/输入输出分组的 token 曲线。适合作为**第 0 步**——先摸清 token 成本和数据量基线，再决定要不要背 Langfuse 的运维包袱。

> ⚠️ **一个必须知道的坑**：`ObservationChatModelListener`（§3.4 要用的）**内部也注册了同名指标** `gen_ai.client.token.usage`（源码里就是 `TOKEN_USAGE` 常量）。**两个 listener 同时挂会重复计数**。从"只要指标"升级到"要 trace"时，应当用后者**替换**前者（指标名不变，Grafana 面板无需改）。

### 3.3 三种接入方式对比

| 方案 | 做法 | 改造量 | 结论 |
|---|---|---|---|
| **A. OTel Java Agent** | 挂 `-javaagent` | 0 代码 | ❌ **不建议**：Java Agent 没有 LangChain4j 的 instrumentation，只能产出 HTTP/JDBC/Spring 基础设施 span，**没有 generation、没有 token、没有 prompt**，等于白接 |
| **B. Micrometer → OTel → OTLP** | 加 3 个依赖 + 1 个 Filter | **小** | ✅ **推荐** |
| **C. 直调 Ingestion API** | 用 `ChatModelListener` 手写 HTTP 上报 | 大 | ❌ **明确禁止**：官方已标 deprecated，Langfuse Cloud 2026-11-16 起 v4-only **拒绝 trace/observation 事件** |

### 3.4 方案 B 的依赖与配置

```xml
<!-- 1) LangChain4j 官方 observation 模块：把 ChatModel 调用变成 Micrometer Observation -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-observation</artifactId>
    <version>1.18.1-beta28</version>
</dependency>
<!-- 2) Micrometer Observation → OTel span -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<!-- 3) OTLP 导出器 -->
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

```yaml
management:
  tracing:
    sampling:
      probability: 1.0          # 代码生成任务量不大，建议全采
  otlp:
    tracing:
      # ⚠️ Spring Boot 的 setEndpoint() 要求「完整路径」，SDK 不会自动补 /v1/traces
      endpoint: ${LANGFUSE_OTLP_ENDPOINT:http://localhost:3000/api/public/otel/v1/traces}
      headers:
        Authorization: ${LANGFUSE_AUTH_HEADER}                  # Basic base64(pk:sk)
        x-langfuse-ingestion-version: "4"                        # 不加则数据最多延迟 10 分钟可见
```

**接入后几乎是零改造**：starter 的 `AutoConfig` 用 `ObjectProvider<ChatModelListener>` 挂载所有 listener，所以新增 `ObservationChatModelListener` Bean 后，`openAiChatModel` / `openAiStreamingChatModel` **自动带上**，现有 `AiModelMonitorListener` 和 6 处 `AiServices.builder(...)` **一行都不用改**。唯一要手工补的是 `RoutingAiModelConfig`（手写的 builder 没调 `.listeners(...)`）。

### 3.5 必须自己补的 3 个字段

`langchain4j-observation` 的默认 convention 产出的是裸指标属性，与 Langfuse 的字段约定有 3 处缺口，需要在 `ObservationFilter` 里补齐：

| 缺口 | 默认产出 | Langfuse 期望 | 补法 |
|---|---|---|---|
| 输入/输出内容 | 无 | `langfuse.observation.input` / `.output` | Filter 里写入（注意 **2 MiB 字段上限**，代码生成场景容易超） |
| Token 用量 | `input_tokens` / `output_tokens`（**裸名**） | `gen_ai.usage.input_tokens` / `gen_ai.usage.output_tokens` | Filter 里补写 `gen_ai.usage.*` |
| TTFT 首 token 延迟 | 无 | `langfuse.observation.completion_start_time` | 包装 `StreamingChatResponseHandler`，用 `ChatRequestOptions.listenerAttributes` 传可变 holder |

调研报告 §5.2 给出了完整可编译的 `LangfuseObservationFilter` 实现（含超长字段截断保护）。

### 3.6 业务链路映射：一次生成任务 = 一条 trace

这是需求②的核心设计，也是与管理端（需求①）打通的接口：

```
trace: gen-task-{taskId}                    ← langfuse.trace.name = "gen-task"
├─ langfuse.user.id      = userId
├─ langfuse.session.id   = appId            ← 同一应用的多次对话聚合成一个 session
├─ langfuse.trace.metadata.taskId / appId / genType
├─ langfuse.trace.metadata.modelConfigId / modelName   ← ★ 来自需求①的模型注册表
│
├── span: langgraph.node.image_collector
├── span: langgraph.node.prompt_enhancer
│     └── generation: chat-<model>          ← langchain4j-observation 自动产生
├── span: langgraph.node.router
│     └── generation: chat-<model>
├── span: langgraph.node.code_generator
│     ├── generation: chat-<model>
│     ├── span: tool.file_write             ← ToolExecutedEvent
│     ├── span: tool.file_read
│     └── span: tool.build_project
├── span: langgraph.node.code_quality_check
├── span: langgraph.node.image_collector
└── span: langgraph.node.project_builder
```

**落地方式**（三层，按优先级）：

1. **任务根 span**：在 `GenTaskExecutor.executeTask()` 里包一层。该项目 `:218` 已经在设置 `MonitorContext`，正好是同一个位置——把 `taskId`（`executeTask` 的入参）一并放进去，并开 root span。
2. **LangGraph4j 节点 span**：在 6 个 `*Node.create()` 的 action 外层包 span，节点名天然是 span 名。这是"AI 执行流程监管"最直观的部分——**一眼看出任务卡在哪个节点**。
3. **工具调用 span**：直接消费 LangChain4j 的 `ToolExecutedEvent`（`AiServiceListener`），不用改工具类本身。

### 3.7 ⚠️ 流式场景的跨线程风险（必须实测）

`GenTaskExecutor` 用 Reactor `flatMap` + `Schedulers.boundedElastic()`，而 `ObservationChatModelListener` 在 `onRequest` 里 `openScope()`、在 `onResponse` 里 `close()`——**请求和响应很可能不在同一线程**，而 Micrometer 的 current scope 是 ThreadLocal 的（项目 `AiModelMonitorListener` 的注释已经明确写了"请求和响应事件的触发不是同一个线程"，现有实现是靠 `requestContext.attributes()` 传递来绕开的）。

后果：span 可能挂错父节点，或产生孤立 trace。

三种缓解方案（按推荐度）：
1. 先按方案 B 上线，**用 `GenTaskExecutor` 的真实并发场景验证 span 父子关系**，有问题再处理；
2. 开启 Reactor 的自动上下文传播（`Hooks.enableAutomaticContextPropagation()`）——但需评估对现有 Reactor 流的影响；
3. 自研一个不自管 scope 的 `ChatModelListener`，直接拿 `MonitorContext` 里的 trace/span 上下文手动建 span（最可控，但要自己写埋点）。

### 3.8 自托管部署要点

| 项 | 结论 |
|---|---|
| 容器数 | 6 个：`langfuse-web`、`langfuse-worker`、`clickhouse:25.12`、`postgres:17`、`redis:7`、`minio` |
| 官方建议规格 | web/worker/PG 各 2C4G，Redis 1C1.5G，**ClickHouse 2C8G**；单机 VM **≥4C/16G/100G** |
| 与现有 compose 的冲突 | ① 官方 service 名就叫 `redis`/`postgres`，与现有同名需改名；② 端口 6379/5432 冲突；③ 跨 compose project 网络不通，需 shared network 或独立 project + 反代 |
| **不能复用现有 Redis** | Langfuse 要求 Redis ≥7 + `maxmemory-policy=noeviction`；MySQL 也不能替代 Postgres |
| **最大运维坑** | ClickHouse 系统日志表会把磁盘吃光（社区实测 `system.trace_log` 66.86 GiB vs 业务数据 <52 MiB），且 **ClickHouse 数据丢失后没有内置的 S3 回灌机制** → **必须做卷快照** |
| 数据保留 | self-host **默认永不删除**，内置 Data Retention 是**企业版功能** → 必须自己写 ClickHouse TTL |
| 轻量替代 | **Langfuse Cloud Hobby 免费 50k units/月**（30 天数据、2 用户），代码侧接法完全不变 |

**建议路径**：先在 Cloud 上用免费额度跑通 PoC（0.5 天），验证字段完整性与跨线程问题后，再决定自托管。

### 3.9 后端不锁定：用 OTel Collector 扇出

Langfuse、Arize Phoenix、LangSmith、Opik、Datadog **都吃 OTLP**。所以不要一上来就把应用焊死在 Langfuse 上——让应用只发一次 OTLP 给本地 Collector，由 Collector 扇出到多个后端：

```
Spring Boot ──OTLP/HTTP──▶ OpenTelemetry Collector ──┬──▶ Langfuse
                          (本地, 4317/4318)          └──▶ Phoenix / 其它
```

好处：
- **同一份埋点跑 A/B**，直接对比"input/output 是不是空的、token 对不对、内容丰不丰富"，再定后端；
- 换后端只改 Collector 配置，**Java 代码不动**；
- 将来加 Datadog/SigNoz 只是加一个 exporter。

> 走 Collector 时，应用侧 `management.otlp.tracing.endpoint` 指向 Collector（**仍是完整路径** `http://otel-collector:4318/v1/traces`），Basic Auth 头与 `x-langfuse-ingestion-version` 挪到 Collector 的 exporter 上。配置骨架见调研报告 §6.5。

**为什么值得这么做——两处已核实的一手信息：**

1. **Arize Phoenix 是 Maven Central 上唯一官方发布的 LangChain4j 埋点库**（`com.arize:openinference-instrumentation-langchain4j:0.1.8`），开箱即含 messages/token，单容器部署（UI+OTLP HTTP 在 6006）。**但 Phoenix 服务端许可是 ELv2（非 OSI 开源）**，对外提供 SaaS 前必须过法务。而 Langfuse 的 `ObservationTypeMapper.ts` **明确支持 OpenInference 属性**（`openinference.span.kind: LLM → GENERATION`，`input.value`/`output.value`）→ 于是可以**用 Arize 的 Java instrumentor 产生 span，把 OTLP 发给 Langfuse**，这是"最少手写埋点"的混搭路线。

2. **不要相信 Langfuse 官方文档里"OpenLLMetry / OpenLIT 把 Langfuse tracing 扩展到 Java"这句话**——已实测证伪：`traceloop/openllmetry-java` 是 2024-02-15 创建当天即停更、3 stars、README 404 的空壳仓库；OpenLIT 的 `sdk/` 目录只有 go/python/typescript。**据此选型会踩空。**

于是选型被收敛到**唯一变量 = 许可**：

| 你的约束 | 选择 | 代价 |
|---|---|---|
| 不能接受非 OSI 许可 | **Langfuse** + §3.4 方案 B | 自己写约 50–100 行补 input/output/token 前缀/TTFT |
| 能接受 ELv2、要最快见效 | **Phoenix** + `com.arize` 埋点 | 许可风险；或把它的 OTLP 扇出给 Langfuse |

---

## 4. 两个需求的闭环设计（本方案的重点）

单独做"模型管控"和"Langfuse 监管"，会得到两个互不相干的系统。**打通它们才是真正的管理端**：

```
管理端配模型                      任务执行                        监管端看流程
─────────────                    ─────────                       ─────────────
ai_model_config          ┌─→  AiModelRegistry  ─→ 选中的模型
  ↓ 场景绑定             │        ↓
ai_scene_model_binding ──┘   写入 MonitorContext
                                 ↓
                          GenTaskExecutor 开 root span
                                 ↓
                          Langfuse trace: gen-task-{taskId}
                          ├─ metadata.modelConfigId = 7   ★
                          ├─ metadata.modelName = "DeepSeek-V3"
                          └─ generation: chat-deepseek-chat

反向：管理端任务列表 → 点"查看轨迹" → 深链到 Langfuse（按 traceId 过滤）
正向：Langfuse 里按 metadata.modelConfigId 筛选 → 对比不同模型的成功率/成本/耗时
```

**具体落地：**

1. `MonitorContext` 扩展字段（向后兼容，全部可空）：
```java
private String userId;
private String appId;
private String taskId;          // ★ 新增：gen_task.id
private String codeGenType;     // ★ 新增
private Long   modelConfigId;   // ★ 新增：来自 AiModelRegistry
private String modelName;       // ★ 新增
private String traceId;         // ★ 新增：OTel trace id，用于回链
```

2. `AiModelRegistry.resolve()` 把命中的 `modelConfigId` / `modelName` 塞进 `MonitorContext`（若已有 context 则补字段）。

3. Langfuse 侧写 `langfuse.trace.metadata.modelConfigId`；管理端侧在 `gen_task` 表加 `modelConfigId` + `traceId` 两列，任务列表直接展示"用了哪个模型"并附带"查看轨迹"按钮。

4. **管理端的价值闭环**：用 Langfuse 的数据反过来指导模型配置——例如某模型在某场景成功率只有 60%，管理端就能看到并调整绑定。这才是"全局控制模型"的完整闭环。

---

## 5. 统一管理端落地清单

### 5.1 页面规划

| 路由 | 页面 | 内容 | 优先级 |
|---|---|---|---|
| `/admin/modelManage` | 模型管理 | 模型池 CRUD + 启用禁用 + 连通性测试 | P0 |
| `/admin/sceneBinding` | 场景绑定 | 5 个场景的模型绑定 + 降级链排序 | P0 |
| `/admin/taskManage` | 任务监管 | gen_task 列表（含模型、状态、耗时、Token）+ "查看轨迹"深链 | P1 |
| `/admin/traceManage` | 轨迹总览 | Langfuse iframe 嵌入 / 指标概览卡片 | P1 |
| `/admin/modelUsage` | 用量与成本 | 按模型/场景/用户的 Token 与成本报表 | P2 |

### 5.2 还需要补的基础设施

| 项 | 说明 |
|---|---|
| **前端路由守卫** | `router/index.ts` 的 `/admin/*` 目前没有访问控制，需加 `meta.requiresAdmin` + `beforeEach` 拦截 |
| **管理端菜单** | `layouts/BasicLayout.vue` 加"模型管理/任务监管"入口，仅 admin 可见 |
| **`gen_task` 表扩展** | 加 `modelConfigId`、`traceId`、`promptTokens`、`completionTokens`（便于列表展示与成本统计） |
| **审计日志** | `ai_model_config_log` 表 + 管理端查询页 |

---

## 6. 风险清单

| # | 风险 | 等级 | 缓解 |
|---|---|---|---|
| 1 | 管理端配错模型导致全站任务失败 | 🔴 高 | 逐级降级 + 回退 yml 兜底模型；保存前强制连通性自检；`status` 全局熔断开关 |
| 2 | Caffeine 缓存持有旧模型 → 热切换失效 | 🔴 高 | 缓存 key 带 registry revision + 变更事件清缓存（§2.3.4 陷阱 1） |
| 3 | 多实例配置不一致 | 🟡 中 | Redisson `RTopic` 广播失效事件 |
| 4 | `langchain4j-observation` 编译于 micrometer 1.16.5，Spring Boot 3.5.4 BOM 固定 1.15.2 | 🟡 中 | 大概率兼容，**必须写单测实测**；可在 `pom.xml` 显式 import micrometer-bom 抬版本 |
| 5 | 流式跨线程导致 span 父子错乱 | 🟡 中 | 先上线观察，必要时上 ContextPropagation 或自研 listener |
| 6 | Langfuse 自托管磁盘被 ClickHouse 系统日志吃满 | 🟡 中 | 上 ClickHouse TTL + 卷快照 + 磁盘告警 |
| 7 | trace 中含用户 prompt 与生成代码，有隐私/合规风险 | 🟡 中 | 自托管不出内网；或对 input/output 做脱敏后再上报 |
| 8 | 2 MiB 字段上限，代码生成场景易超 | 🟢 低 | Filter 里做截断（调研报告 §5.2 已有实现） |
| 9 | API Key 加密主密钥管理 | 🟢 低 | 走环境变量，不进 git，与现有 `.env` 策略一致 |
| 10 | `langchain4j-micrometer-metrics` 与 `langchain4j-observation` **注册同名指标** `gen_ai.client.token.usage`，同挂会重复计数 | 🟢 低 | 升级时用后者**替换**前者（指标名不变，Grafana 面板无需改） |
| 11 | 误信 Langfuse 官方文档"OpenLLMetry/OpenLIT 已支持 Java"而据此选型 | 🟢 低 | 该说法已实测证伪（空壳仓库）；Java 侧只有 §3.4 方案 B 或 Arize 埋点两条真实路径 |

---

## 7. 实施路线图

| 阶段 | 内容 | 产出 | 工作量 |
|---|---|---|---|
| **P-1**（可选，零风险） | 加 `langchain4j-micrometer-metrics:1.18.1-beta28`，复用现有 Prometheus + Grafana | 按模型分组的 token 成本基线 | **0.5 人日** |
| **P0-1** | 建表 + 实体 + Service + 管理端 CRUD 接口 | 模型池可维护 | 1.5 人日 |
| **P0-2** | `AiModelRegistry` + `AiModelFactory` + 改造 5 处注入点 + 降级兜底 | 模型可热切换 | 1.5 人日 |
| **P0-3** | 前端页面 + 路由守卫 + 菜单 | 管理端可用 | 1.5 人日 |
| **P1-1** | Langfuse PoC（Cloud 免费额度，跑通 OTLP；**用 `langchain4j-observation` 替换 P-1 的 micrometer-metrics**） | 能看到 generation | 0.5 人日 |
| **P1-2** | `LangfuseObservationFilter` 补 input/output/token/TTFT | trace 字段完整 | 1 人日 |
| **P1-3** | 任务 root span + LangGraph4j 节点 span + MonitorContext 扩展 | 流程可见 | 1.5 人日 |
| **P1-4** | 任务监管页 + 轨迹深链 | 闭环打通 | 1 人日 |
| **P2** | 自托管 Langfuse / OTel Collector 扇出 / 成本核算 / 用户级覆盖 / A/B | 生产级 | 视需求 |

**P-1 仅 0.5 人日且无任何新组件，建议先做**——它能在投入 Langfuse 之前给出"值不值得"的数据答案。
**P0 合计约 4.5 人日，P1 合计约 4 人日。**

---

## 8. 参考

- Langfuse 接入深度调研（本仓库）：`docs/research/langfuse-接入调研报告.md`
- Langfuse OTel 集成：<https://langfuse.com/integrations/native/opentelemetry>
- Langfuse 数据保留（self-host 属 EE）：<https://langfuse.com/docs/administration/data-retention>
- Langfuse 官方 compose：<https://github.com/langfuse/langfuse/blob/main/docker-compose.yml>
- Langfuse 观测类型映射源码：<https://github.com/langfuse/langfuse/blob/main/packages/shared/src/server/otel/ObservationTypeMapper.ts>
- LangChain4j 官方 Langfuse 立场：<https://github.com/langfuse/langfuse-java>
- `langchain4j-observation` Maven 目录：<https://repo1.maven.org/maven2/dev/langchain4j/langchain4j-observation/1.18.1-beta28/>
- `langchain4j-micrometer-metrics` Maven 目录：<https://repo1.maven.org/maven2/dev/langchain4j/langchain4j-micrometer-metrics/1.18.1-beta28/>
- Arize OpenInference LangChain4j 埋点库：<https://repo1.maven.org/maven2/com/arize/openinference-instrumentation-langchain4j/0.1.8/>
- Langfuse 观测类型映射源码（含 OpenInference 支持）：<https://github.com/langfuse/langfuse/blob/main/packages/shared/src/server/otel/ObservationTypeMapper.ts>
- 备选方案 Comet Opik（Apache-2.0）：<https://www.comet.com/docs/opik/integrations/opentelemetry>
