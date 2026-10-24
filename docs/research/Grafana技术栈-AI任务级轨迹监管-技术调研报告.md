# Grafana 技术栈能否满足「AI 任务级轨迹监管」——技术调研报告

> **调研对象**：现网已有的 `Prometheus + Grafana`（+ MySQL + Redis + docker compose）栈
> **目标需求**：监管端按**单个用户任务**查看 ①工具调用轨迹（多轮对话 + 工具名/参数/返回值）②token 消耗 ③单任务成本
> **调研方式**：以 Grafana / Prometheus / Loki / Tempo / OpenTelemetry / Micrometer / Spring Boot 官方文档为主，辅以官方 GitHub 仓库源码与 changelog
> **结论一句话**：**仅用现有 Prometheus + Grafana 无法实现；补 Loki 或 Tempo 可以「看到轨迹」但要付出对象存储与运维代价；本需求的最优解是「MySQL 存轨迹明细 + 前端展示」为主干，Langfuse 为增强，Grafana 继续只做聚合大盘。**
>
> 证据标记约定：**【官方】**＝官方文档/规范/源码明确说明；**【推断】**＝本报告基于官方事实的工程推理，未经官方背书；**【待核实】**＝本次未能取得权威证据。

---

## 0. 结论速览

| 问题 | 结论 | 关键依据 |
|---|---|---|
| **A3. 仅用现有 Prometheus + Grafana，能否展示单任务的对话与工具调用轨迹？** | ❌ **不能，且不是"配置问题"而是"数据模型不匹配"** | Prometheus 是数值时序库，无字符串/文本载荷字段；标签是索引维度而非内容载体 **【官方】** |
| 把 `task_id` 加成 Prometheus 标签可行吗？ | ❌ **不可行**，会单调增长的基数爆炸；官方建议单指标基数 <10，>100 就该换系统 **【官方】** | [Prometheus Instrumentation](https://prometheus.io/docs/practices/instrumentation/) |
| exemplars 能当"挂 trace 链接"的桥梁吗？ | ✅ 能，但**只能挂"最新一次"的引用**，不能枚举全部任务，且默认需开启特性开关 **【官方】** | [OpenMetrics Spec](https://github.com/prometheus/OpenMetrics/blob/v1.0.0/specification/OpenMetrics.md#exemplars) / [Prometheus Feature flags](https://prometheus.io/docs/prometheus/latest/feature_flags/) |
| **B4. 补 Loki 能否做出"按 taskId 过滤 + 按时间排序的轨迹视图"？** | ✅ **能**，且是 Grafana 栈里**改造成本最低**的方案。`task_id` 必须走 **structured metadata，不能做 label** **【官方】** | [Loki Label best practices](https://grafana.com/docs/loki/latest/get-started/labels/bp-labels/) / [Structured metadata](https://grafana.com/docs/loki/latest/get-started/labels/structured-metadata/) |
| **B5. 补 Tempo 能否展示 trace 树？** | ✅ **能，且语义最贴切**（树形天然映射"任务→多轮 LLM→工具调用"）。OTel 已为 GenAI 定义 `gen_ai.*` 语义约定，含工具调用 span **【官方】** | [GenAI semconv](https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/gen-ai-spans.md) |
| Grafana 有没有官方 LLM 可观测方案？ | ✅ 有，但**是 Grafana Cloud 专属**（OpenLIT Observability，public preview），OSS 自建栈拿不到这套看板 **【官方】** | [Grafana Cloud AI Observability](https://grafana.com/docs/grafana-cloud/monitor-applications/ai-observability/) |
| **C8. 推荐哪个？** | **MySQL 自建管理端为主**（需求 ①②③ 全覆盖、零新增中间件）+ **Prometheus/Grafana 保持聚合大盘**；有进阶评测/标注需求时**再叠加 Langfuse**（不建议用 Loki/Tempo 承载轨迹正文） | 见 §6 |

---

## 1. 需求 → 数据形态映射（先把问题说清楚）

监管端要的三样东西，在数据形态上完全不同，必须分开选型：

| 需求 | 数据形态 | 基数特征 | 是否适合 metrics |
|---|---|---|---|
| ①工具调用轨迹（对话正文、工具名、**参数 JSON**、**返回值**） | **事件流 / 文本 / 半结构化** | 每个任务都有唯一 ID，正文长度 KB~MB | ❌ 完全不合适 |
| ②token 消耗 | 数值计数 | 低基数（可按 user/app/model 聚合） | ✅ 合适 |
| ③单任务成本 | 数值，但需要**按 taskId 精确归集** | 任务级 = 高基数 | ⚠️ 明细不合适，**聚合**才合适 |

> **关键洞察**：需求 ② 你现在**已经满足**了；真正缺的是 ①，而 ③ 是"①的副产品"（明细表里加一个 `cost_usd` 列即可）。
> 从代码现状看（`monitor/MonitorContext` 只有 `userId` + `appId`），项目当前是「**聚合指标**」而非「**执行链路**」——这个判断与 `docs/research/AI管理端-可行性调研与设计方案.md` §1.3 一致，本报告从数据模型角度给出独立佐证。

---

## 2. A. Grafana / Prometheus 的能力边界

### 2.1 为什么文本型、事件型数据不适合放 Prometheus

**【官方】Prometheus 的数据模型约束**：

1. **只存 float64 数值 + 标签集**。一个样本 = `(metric_name, label_set, timestamp, float64)`。没有字符串值字段、没有 JSON 字段、没有可变长文本载荷。
2. **标签是"索引维度"，不是"内容载体"**。标签组合（labelset）唯一确定一条时间序列，样本按时间追加。因此标签的设计前提是「**值域有界且长期复用**」。
3. **无法表达"一次事件的正文"**。工具调用的 `arguments` / `result` 是每次调用都不同的 JSON，如果塞进标签，就等于给每次调用造一条**永不复用**的序列。

**【官方】Prometheus 官方对标签使用的明确告诫**（[Instrumentation | Prometheus](https://prometheus.io/docs/practices/instrumentation/)，小节 *Do not overuse labels*）：

> "Each labelset is an additional time series that has RAM, CPU, disk, and network costs."
> "As a general guideline, try to keep the cardinality of your metrics **below 10**, and for metrics that exceed that, aim to limit them to a handful across your whole system. **The vast majority of your metrics should have no labels.**"
> "If you have a metric that has a cardinality **over 100** or the potential to grow that large, **investigate alternate solutions such as reducing the number of dimensions or moving the analysis away from monitoring and to a general-purpose processing system**。"

最后一句就是官方给出的、针对本需求的标准答案：**这类分析应该"移出监控系统，交给通用处理系统"**。

### 2.2 基数上限 / 性能断崖：官方量化说明

**【官方】两个必须同时看的表述（它们之间存在张力，社区对此有争议）：**

| 来源 | 表述 | 性质 |
|---|---|---|
| [Instrumentation 文档](https://prometheus.io/docs/practices/instrumentation/) | 单指标基数 **<10** 为一般准则；**>100** 应考虑迁移到通用处理系统 | **实践建议**（软性） |
| [Instrumentation 文档](https://prometheus.io/docs/practices/instrumentation/) 的 node_exporter 举例 | 10,000 节点 × 每节点数十条 `node_filesystem_avail` 序列 ≈ **100,000 条**，"is fine for Prometheus to handle"；但"再加上 per-user quota，10,000 用户 × 10,000 节点 会迅速达到**两位数百万级**……**This is too much for the current implementation of Prometheus**" | **能力边界举例** |
| [FAQ | Prometheus](https://prometheus.io/docs/introduction/faq/) | "You can run Prometheus reliably with **tens of millions of active series**." | **能力声明**（面向大型单机） |
| [Storage | Prometheus](https://prometheus.io/docs/prometheus/latest/storage/) | "Prometheus stores an average of only **1-2 bytes per sample**"，容量公式 `needed_disk_space = retention_time_seconds × ingested_samples_per_second × bytes_per_sample` | **磁盘侧量化** |
| [prometheus/docs#2291](https://github.com/prometheus/docs/issues/2291) | 社区 issue 标题即「Prometheus Docs Recommend Bizarrely Low Cardinality」，反映官方文档建议值与实际能力之间的落差 | **社区佐证** |

**【推断】如何理解这个"断崖"**：官方**没有**给出"单指标 N 条序列必崩"的硬阈值。真实的瓶颈有两个：
- **内存**：Head block 的活跃序列常驻内存，内存随活跃序列数近似线性增长。**官方未公布"每序列 KB 数"的权威值**，社区经验值通常在每活跃系列数 KB 量级 —— 此数字**【待核实】**，请勿当作官方结论引用。
- **查询/抓取成本**：`topk` / `sum by` 的代价随匹配序列数增长；且**绝大多数序列是"写一次就再也不被查询"的垃圾序列**，纯属净损耗。

**结论（对本项目）**：断崖不是某个具体数字，而是**增长模式**——`user_id` / `app_id` 的序列数由"用户数/应用数"决定（**有界**），而 `task_id` 的序列数由"历史任务总数"决定（**只增不减**）。这是本质区别。

### 2.3 exemplars 机制：能否用来挂 trace 链接？

**【官方】可以，但有严格限制。**

规范层面（[OpenMetrics v1.0.0 Specification §Exemplars](https://github.com/prometheus/OpenMetrics/blob/v1.0.0/specification/OpenMetrics.md#exemplars)）：

> "Exemplars are **references to data outside of the MetricSet**. A common use case are **IDs of program traces**."
> "The combined length of the label names and values of an Exemplar's LabelSet **MUST NOT exceed 128 UTF-8 characters**."
> "**Ingestors MAY discard exemplars.**"
> 直方图 bucket 约束："Each bucket ... the value of the exemplar MUST be within this range. ... **A bucket MUST NOT have more than one exemplar.**"

实现层面（[Prometheus Feature flags §Exemplars storage](https://prometheus.io/docs/prometheus/latest/feature_flags/)）：

> 特性开关 `--enable-feature=exemplar-storage`
> "Exemplar storage is implemented as a **fixed size circular buffer** that stores exemplars **in memory** for all series."
> "An exemplar with just a `trace_id=<jaeger-trace-id>` uses roughly **100 bytes** of memory via the in-memory exemplar storage."
> "If the exemplar storage is enabled, we will also append the exemplars to WAL for local persistence (**for WAL duration**)."

Java/Micrometer 侧（[Micrometer – Prometheus](https://docs.micrometer.io/micrometer/reference/implementations/prometheus.html)）：

> "Exemplars are **not tags/dimensions** (labels in Prometheus terminology), **they will not increase cardinality** since they belong to the values of the time series."
> 需要提供 `io.prometheus.metrics.tracer.common.SpanContext`（新 client）或 `SpanContextSupplier`（legacy client）。

**限制清单（【官方】+【推断】）**：

| 限制 | 说明 | 等级 |
|---|---|---|
| 必须显式开启 | `--enable-feature=exemplar-storage`，当前不是默认开启 | 【官方】 |
| 固定大小环形缓冲 | 旧 exemplar 会被**静默淘汰**，`Ingestors MAY discard exemplars` | 【官方】 |
| **不是可查询维度** | exemplar **不能**作为 PromQL 的过滤/分组条件；你**无法** `sum by (task_id)` 或"列出所有 task_id" | 【官方】（Micrometer 明说 not tags/dimensions）+【推断】 |
| 每 bucket 最多 1 个 | 直方图场景下，一个时间窗内只能挂一个 trace 引用 | 【官方】 |
| 128 字符上限 | 只能放短引用（trace_id / 短 ID），**放不下参数或正文** | 【官方】 |
| 需接入 SpanContext | 要先把 Java 侧 tracing 打通，才能在 scrape 时写入 exemplar | 【官方】 |

> **判定**：exemplars 的正确定位是「**从大盘曲线点一下，跳到这一分钟里某一条 trace**」的**反向导航**，是很好的 UX 粘合剂；但它**不能**承担"按 taskId 检索轨迹"的主查询路径。**它解决的是"从指标到 trace"，不解决"从 taskId 到轨迹"。**

### 2.4 Grafana 本身支持哪些数据源

Grafana 本体是**可视化/查询层**，能力边界由数据源决定，不由 Grafana 决定。**【官方】**[Data sources | Grafana](https://grafana.com/docs/grafana/latest/datasources/) 中与「LLM 轨迹展示」相关的内置/官方数据源：

| 数据源 | 数据形态 | 对本需求的适用性 | 判断 |
|---|---|---|---|
| **Prometheus** | 数值时序 | 只能做 token/成本/错误率的**聚合大盘**；无法承载轨迹 | ⚠️ 仅聚合 |
| **Loki** | 日志（文本行 + 标签 + structured metadata） | ✅ **最贴合"轨迹"**：每轮 LLM/每次工具调用写一条结构化 JSON 行，按 `task_id` 过滤 + 按时间排序 = 会话时间线。Grafana 有 Logs panel / Explore 日志详情（字段展开）/ Logs Drilldown | ✅ 推荐候选 |
| **Tempo** | trace（span 树 + 属性 + 事件） | ✅ **语义最贴切**：任务=root span，每轮 LLM、每次工具调用=子 span，天然形成"轨迹树"。Grafana Trace View 支持 span attributes / resource attributes / events / links；Service Graph 需 metrics-generator | ✅ 推荐候选 |
| **MySQL / PostgreSQL** | 关系表 | ✅ Grafana 官方支持作为数据源做 table / timeseries 查询。可以用来直接查 `gen_task` / 轨迹明细表。但**长文本与嵌套 JSON 的展示体验差**（表格截断、需手写 SQL、无展开折叠） | ⚠️ 能用但体验一般 |
| **ClickHouse**（`grafana-clickhouse-datasource` 官方插件） | 列式 OLAP | ✅ 适合海量明细 + 聚合分析；但引入新组件。**（Langfuse 自身就用 ClickHouse 存 traces）** | ⚠️ 重 |
| Elasticsearch / OpenSearch | 日志/文档 | ✅ 全文检索轨迹正文能力强 | ⚠️ 重 |
| Jaeger / Zipkin | trace | ✅ 同类，功能弱于 Tempo | ⚠️ 备选 |

### 2.5 ⭐ 关键判断：仅用现有 Prometheus + Grafana 能否展示单任务的对话与工具调用轨迹？

## ❌ 不能。三个层面都不通，且没有任何"配置技巧"可以绕过。

**理由 1｜存储层：数据模型不匹配（硬性）**
Prometheus 没有承载文本/JSON 的字段。对话正文、工具 `arguments`、工具 `result` **物理上无法存进时间序列**。唯一能想到的"把 JSON 塞进 label"的做法，等于给每次调用创建永不复用的一条序列，直接触发下面第 2 条。**【官方】**（数据模型）+ **【推断】**（JSON→label 的后果）

**理由 2｜基数层：taskId 是单调增长的维度**
见 §5.1 的量化。把 `task_id` 加到现有 4 个指标上，仅以"1000 任务/天、每任务 5 次 LLM 调用、默认 15 天保留"估算，就会带来**数万到十几万条一次性序列**，且随历史任务数持续增长，永远不会被复用。这正好命中官方那句"If you have a metric that has a cardinality over 100 ... moving the analysis away from monitoring and to a general-purpose processing system"。**【官方】**

**理由 3｜展示层：Grafana 面板无法渲染"会话"**
即使数据进去了，Grafana 的时间序列面板语义是「值随时间变化」，不是「事件按序排列」。它没有"多轮对话气泡"、"工具调用参数折叠展开"、"工具返回结果查看"之类的可视化原语。Trace View 有树但没有对话渲染；Logs panel 有行但没有会话聚合。**【推断】**（基于对 [Traces in Explore](https://grafana.com/docs/grafana/latest/explore/trace-integration/) 与 [View logs](https://grafana.com/docs/grafana/latest/visualizations/simplified-exploration/logs/view-logs/) 文档所述可视化能力的判断）

> **一句话**：现有栈能做到的极限是——**"这个用户今天花了多少 token、错了几次"**；做不到——**"这个任务第 3 轮调用了什么工具、传了什么参数、返回了什么"**。二者之间不是工程量差距，是**数据模型的鸿沟**。

### 2.6 ⚠️ 顺带发现的现网隐患（代码级证据）

本次调研在核对指标标签时发现一处**已存在的基数风险**，建议独立于本需求尽快修复：

`src/main/java/com/yupi/yuaicodemother/monitor/AiModelMonitorListener.java:70`

```java
String errorMessage = errorContext.error().getMessage();   // ← 原始异常消息
```

该字符串在 `AiModelMetricsCollector.recordError()`（`AiModelMetricsCollector.java:58`）被**原样用作 Prometheus 标签值**：

```java
.tag("error_message", errorMessage)
```

**风险**：
- 异常消息通常包含**变量内容**（URL、请求 ID、上游返回的业务文案、超时数值、模型名回显等），值域**无界**；
- 现有 Grafana 看板中已有一个面板做 `sum by (error_message) (increase(ai_model_errors_total[$__range]))`，一旦上游开始返回个性化错误文案，该面板会拉取大量序列；
- 同时 `errorCountersCache`（以 `user_app_model_errorMessage` 拼串为 key 的 Map）在**应用堆内也会无界增长**。

**建议**（【推断】）：改为**低基数错误分类标签**，例如 `error_type`（`timeout` / `auth_failed` / `rate_limited` / `upstream_5xx` / `unknown`），原始消息落日志或明细表，不进 label。

---

## 3. B. 补充 Grafana 生态是否可行

### 3.1 Grafana Loki：把轨迹写成结构化 JSON 日志

**可行吗？** ✅ **可行，而且是 Grafana 栈内改造成本最低的轨迹方案。**

**为什么可行**：Loki 存的就是「带标签的文本行 + 时间戳」，天然是**事件存储**；Grafana 能按标签筛选、按时间排序、逐行展开字段。

**⚠️ 但必须遵守 Loki 的基数纪律**（这是本方案成败的关键）：

**【官方】**[Label best practices | Loki](https://grafana.com/docs/loki/latest/get-started/labels/bp-labels/)：

> "Ensure the labels have **low cardinality, ideally limited to tens of values**."
> "**Do not extract ephemeral values like a trace ID or an order ID into a label**; the values should be static, not dynamic."
> "As a general rule, you should try to keep any single tenant in Loki to **less than 100,000 active streams**, and less than a million streams in a 24-hour period."

**【官方】**[Cardinality | Loki](https://grafana.com/docs/loki/latest/get-started/labels/cardinality/)：

> "In Loki, the fewer labels you use, the better. This is why Loki has a **default limit of 15 index labels**."
> "High cardinality causes Loki to create many streams ... This causes Loki to build a **huge index**, and to flush **thousands of tiny chunks** to the object store."
> "**Loki was not designed or built to support high cardinality label values.** In fact, it was built for exactly the opposite."

👉 **所以：`task_id` 绝不能做 label。** `user_id` / `app_id` 也应谨慎（它们是有界的，但值域可能达万级，建议评估后再定）。

**正确做法：`task_id` 走 structured metadata。**

**【官方】**[Structured metadata | Loki](https://grafana.com/docs/loki/latest/get-started/labels/structured-metadata/)：

> "Structured metadata is a way to **attach metadata to logs without indexing them** or including them in the log line content itself."
> "Examples of useful metadata include **container_IDs or user IDs**."
> 适用场景："If you have **high cardinality metadata** that should not be used as a label and does not exist in the log line."
> 查询方式：结构化元数据会被自动附加到返回行的 labels 上，可用**标签过滤表达式**过滤，例如 `{app="x"} | task_id="T123"`。
> ⚠️ 注意事项："since structured metadata is extracted automatically to the results labels, **some metric queries might return an error like `maximum of series (50000) reached for a single query`**"（可用 `keep` / `drop` 缓解）。
> 前置条件：structured metadata **需要 chunk format V4 / schema version ≥ 13，且必须使用 `tsdb` 索引**；**不支持已废弃的 `boltdb-shipper`**。

**推荐的自定义标签设计（【推断】，符合官方最佳实践）**：

| 字段 | 放哪 | 理由 |
|---|---|---|
| `service` / `env` / `job` | **label** | 静态、值域极小（官方推荐） |
| `task_id` / `trace_id` / `user_id` | **structured metadata** | 官方明说 trace ID / order ID **不要**做 label；官方把 user ID 列为 structured metadata 的典型用例 |
| `event_type`（`llm_request` / `llm_response` / `tool_request` / `tool_result`） | **structured metadata** | 值域小但组合起来会增流，元数据更稳 |
| 正文 / 参数 / 返回值（JSON） | **日志行 body** | 本来就是文本，不索引 |
| `model_name` / `status` / `token_type` | **structured metadata**（或 label，视值域而定） | 值域小，可放 label；但为省 index 也可走元数据 |

**能否做出"按 taskId 过滤、按时间排序的轨迹视图"？** ✅ **能**：
- Explore / Logs panel 中用 `{service="ai-codegen"} | task_id="T123"` 过滤 → 结果天然按时间升序 → 即为该任务的**线性轨迹**；
- 每条日志是一段结构化 JSON（含 `event_type` / `seq` / `model` / `tool_name` / `arguments` / `result` / `tokens` / `cost`），Grafana 日志详情面板可**展开字段**；
- 可在 [Logs Drilldown](https://grafana.com/docs/grafana/latest/visualizations/simplified-exploration/logs/view-logs/) 中做进一步筛选。

**有没有现成的 Grafana 面板/插件适合"单次会话轨迹"？**
**【待核实】/【推断】**：本次调研在 Grafana OSS 官方文档与插件目录中**未找到**专门为「LLM 多轮会话/工具调用轨迹」设计的官方面板插件。现有的能力是**通用日志/追踪渲染**（Logs panel、Explore log details、Trace View），可以承载轨迹的**线性列表**，但**没有"对话气泡 + 工具调用卡片"这种会话化 UI**。若要好体验，需要自研前端或上专用平台。

**优点**：一套组件（Loki 单体）+ Grafana 已有 → 集成简单；检索能力（过滤表达式）强；成本远低于 Tempo。
**缺点**：只有**线性时间线**，没有**调用层级/父子关系**（多轮里哪次工具调用属于哪次 LLM 轮次，日志中需自己用 `seq`/`span_id` 表达）；无内建 token/成本聚合语义（要自己算好写进 JSON 或用 LogQL 度量查询）。

---

### 3.2 Grafana Tempo（或 OTLP 链路）：trace 树 + GenAI 语义

**能否展示 trace 树（任务 → 各节点 → 各 LLM 调用 → 各工具调用）？** ✅ **能，且这是最贴合"轨迹"心智模型的方案。**

**【官方】**[Traces in Explore | Grafana](https://grafana.com/docs/grafana/latest/explore/trace-integration/)：
- Trace View 提供 **span 树 / 火焰图 / 节点图**，"Span details include span attributes, resource attributes, **events**, and **links**"；
- Node graph 可展开，"displays spans of the trace as nodes in the graph"；
- Service graph 可视化 RED 指标与服务关系（需 metrics-generator 配置）。

**对"单条 trace 的多轮 LLM 调用"展示效果如何？** **【推断】**：
- ✅ **树形结构天然正确**：一次生成任务 = 1 条 trace；LangGraph4j 的 6 个节点（`image_collector → prompt_enhancer → router → code_generator → code_quality_check → project_builder`）= 6 个 span；每轮 LLM 调用、每次工具调用分别为子 span。**层级关系一目了然**；
- ✅ **属性面板能看到 `arguments` / `result`**（前提是采集了，见下）；
- ⚠️ **但仍是"通用 trace 查看器"**：没有为"对话"优化的渲染（比如把 N 轮 LLM 调用呈现为对话流）；正文长文本在属性面板中是**原始字符串**，阅读体验一般。

**有没有针对 GenAI 语义（`gen_ai.*`）的专门支持？** ✅ **OpenTelemetry 有完整规范**（Grafana 侧是通用渲染）。

**【官方】** [Semantic conventions for generative client AI spans](https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/gen-ai-spans.md)（注意：该规范**已从 opentelemetry.io 迁移到独立仓库** `open-telemetry/semantic-conventions-genai`，原页面标注 "has moved and is no longer maintained"）：

| 语义 | 内容 | 需求级别 |
|---|---|---|
| Inference span | `gen_ai.operation.name`（如 `chat`）、`gen_ai.request.model`；span 名建议 `{gen_ai.operation.name} {gen_ai.request.model}` | Recommended |
| **Execute tool span** | **`gen_ai.operation.name = execute_tool`**；**span 名建议 `execute_tool {gen_ai.tool.name}`**；kind = INTERNAL | Recommended |
| `gen_ai.tool.name` | 工具名 | **Required** |
| `gen_ai.agent.name` | 执行该工具的 agent 名 | Conditionally Required |
| `gen_ai.tool.call.id` | 工具调用 ID | Recommended |
| `gen_ai.tool.type` | `function` / `extension` / `datastore` | Recommended |
| **`gen_ai.tool.call.arguments`** | **工具入参（对象）** | **Opt-In** |
| **`gen_ai.tool.call.result`** | **工具返回值（对象）** | **Opt-In** |

**⚠️ 极其重要的一条（直接决定你的合规与架构选择）**——【官方】同文档 *Capturing instructions, inputs, and outputs* 章节：

> "Model instructions, user messages, and model outputs are considered **sensitive** and are often large in size. Recording large or sensitive content in telemetry may be problematic due to high storage costs, **regulatory requirements**, or the need to enforce different access models for operational and user data."
> "OpenTelemetry instrumentations **SHOULD NOT capture them by default**, but SHOULD provide an option for users to **opt in**."
> 官方给出三种模式，其中第 3 种：
> "**Store content externally and record references on the spans.** This pattern is **recommended in production environments** where telemetry volume is a concern or **sensitive data needs to be handled securely**. Using external storage enables separate access controls."

👉 **这段话是本次调研最有价值的发现之一**：连 OpenTelemetry 官方都建议**生产环境把正文内容外置存储、span 上只留引用**。这与本报告 §6 推荐「MySQL 存轨迹正文」的方案**方向完全一致**，可以当作权威背书。

**Grafana 有没有 LLM observability 的官方方案？** ✅ **有，但是 Cloud 专属。**

**【官方】**[Grafana Cloud AI Observability（OpenLIT Observability）](https://grafana.com/docs/grafana-cloud/monitor-applications/ai-observability/)：
- 定位："**OpenTelemetry-native** observability with distributed tracing across your complete AI stack"，覆盖 LLMs / Vector DB / GPU / MCP Servers；
- 提供 **5 个开箱看板**：GenAI Observability、GenAI Evaluations、VectorDB、MCP、GPU；
- **含成本与 token**："Real-time spend tracking"、"Token analytics: Track consumption patterns"、"per-agent cost breakdown"；
- **含 Agent 轨迹**："Invocation tracking"、"**Logs and debugging: Integrated agent logs with OpenTelemetry trace and span ID correlation**"；
- 接入方式：**OTLP**（默认 OTLP Gateway 直连，或经 OTel Collector / Grafana Alloy）；
- 安装路径明确写着 **"In your Grafana Cloud stack, click Connections"**。

**【官方】佐证（第三方报道 + Grafana 官方博客）**：
- Grafana 官方博客（2026-02-24）[Build, buy, or open source?](https://grafana.com/blog/build-buy-or-open-source-understanding-your-options-with-grafana-s-ai-powered-observability/)；
- 行业报道（2026-04）："the company introduced **AI Observability in Grafana Cloud (public preview)**" ——[Open Source For You](https://www.opensourceforu.com/2026/04/grafana-labs-extends-ai-observability-to-oss-with-open-benchmark-launch/)。

> **关键限制【推断】**：这套 GenAI 看板是 **Grafana Cloud 的托管集成**，不是 Grafana OSS / 自建 LGTM 栈自带的能力。你在自己 docker compose 里跑 Grafana OSS + Tempo，**拿不到这 5 个看板**，只能自己用 Trace View / Logs 拼装通用视图。若要这套体验，等于把监管数据（含 AI 对话正文）送到 Grafana Cloud。

**TraceQL 检索**：Tempo 支持 TraceQL（[Query with TraceQL](https://grafana.com/docs/tempo/latest/traceql/)），可按 span 属性检索，例如筛出 `execute_tool` 的调用。**【推断】** 需自行确认 TraceQL 对 `gen_ai.*` 属性的过滤语法与性能，本次未逐条验证。

---

### 3.3 资源与运维成本

#### Loki

**【官方】**[Loki architecture](https://grafana.com/docs/loki/latest/get-started/architecture/)：
- **必须对象存储**："Loki stores all data in a **single object storage backend**"，支持 **S3 / GCS / Azure Blob / IBM COS / 百度 BOS / 阿里 OSS / S3 兼容（MinIO）**；
- 本地 **filesystem 后端明确不推荐用于生产**（[Storage | Loki](https://grafana.com/docs/loki/latest/operations/storage/)："Filesystem (please read more about the filesystem to understand the pros/cons before using with production data)"）；
- 推荐索引：**Single Store TSDB**（Loki 2.8+ 推荐），index 文件同样落对象存储；
- 组件：distributor / ingester / querier / query-frontend / compactor（+ index-gateway / ruler 可选），**单进程模式（monolithic）可全部合一**，容器数可压到 1；
- **无需独立数据库**（这是它比 Tempo/Langfuse 轻的地方）。

**【推断】部署形态**：
- **小规模（本项目的合理选择）**：Loki 单体（1 容器，filesystem 或 MinIO）+ 现有 Grafana → **2 个新增容器**，运维复杂度低；
- 生产/高可用：distributor/ingester/querier 拆分 + 对象存储 + 副本 → 复杂度接近一个中型分布式系统。
- **资源占用**：官方**未给出**权威的"每 GB/每日志量对应多少 CPU/内存"的换算表 —— 本次调研**未找到**官方 sizing 页面（`/docs/loki/latest/operations/size/`、`/sizing/` 均 404）**【待核实】**。请勿引用任何未经确认的资源数字。

#### Tempo

**【官方】**[Tempo architecture](https://grafana.com/docs/tempo/latest/operations/architecture/)：
- **必须对象存储**："**Tempo stores all trace data in object storage.**" 支持 **S3（及 S3 兼容如 MinIO）/ GCS / Azure Blob**；
- "**Object storage is recommended for production in both modes**; a **local filesystem backend is available for development and testing**."；
- 存储格式：**Apache Parquet block**；
- 组件（microservices 模式）：distributor / live-store / **block-builder（消费 Kafka）** / querier / query-frontend / compactor(backend scheduler & worker) / **object storage**；monolithic 模式则 live-store 直接 flush block 到对象存储；
- **metrics-generator**：Service Graph / span metrics / TraceQL metrics 需要它；它会**把生成的服务图指标写入 Prometheus（remote write）**。

**【推断】部署形态**：
- 小规模：Tempo 单体（1 容器）+ MinIO（1 容器）+ 现有 Grafana → **2 个新增容器**；
- 若要 Service Graph，还需 metrics-generator + 写回 Prometheus。

#### 集成难度对比（【推断】）

| 维度 | Loki | Tempo | Langfuse | MySQL 自建 |
|---|---|---|---|---|
| 新增容器数（小规模） | ~2（Loki + 对象存储/MiniIO，或 filesystem=1） | ~2（Tempo + MinIO） | **~6**（web/worker/Postgres/ClickHouse/Redis/MinIO） | **0** |
| 需要对象存储 | ✅（生产） | ✅（生产） | ✅（S3/MinIO） | ❌ |
| 需要额外数据库 | ❌ | ❌ | ✅ Postgres + ClickHouse + Redis | ✅（已有） |
| Java 侧接入 | 结构化 JSON 日志 + 日志采集（logback appender / OTel logs） | OTel tracing（Spring Boot 3 原生支持 OTLP） | OTLP 或 SDK | 现有 MyBatis/JDBC |
| 与现有 Grafana 集成 | 加一个 Loki 数据源即可 | 加一个 Tempo 数据源即可 | 独立 UI（不接 Grafana） | 加 MySQL 数据源，或自研前端 |
| **单任务轨迹体验** | 线性时间线，较好 | 树形，最贴切 | **最佳**（专用 UI） | **取决于自研前端** |

---

## 4. C. 与专用 LLM 可观测平台的对比

### 4.1 三方横向对比

| 维度 | **Grafana 栈**（Prometheus + Loki/Tempo） | **Langfuse**（自托管） | **Arize Phoenix**（自托管） |
|---|---|---|---|
| **定位** | 通用可观测平台，AI 是新增场景 | **LLM 原生**可观测 + 评测平台 | **LLM 原生**可观测 + 评测平台 |
| **单任务轨迹展示** | ⚠️ 通用：Loki=线性日志时间线；Tempo=span 树。**能表达，但无对话化 UI** | ✅ **最佳**：Trace 视图按 observation 树展开，`GENERATION`/`SPAN`/`TOOL` 类型化；支持 **Session** 聚合多轮对话 | ✅ 优秀：Trace 树 + 类型化 span，UI 专为 LLM 设计 |
| **token 统计** | ✅ 已有（`ai_model_tokens_total`），且可按 user/app/model 聚合 | ✅ 每次 generation 记录 usage，按 usage type 拆分（input/output/cached…） | ✅ 有 |
| **成本统计 / 按任务聚合** | ❌ **按任务聚合做不到**（基数爆炸，见 §5）；只能做聚合维度 | ✅ **开箱即用**：内置主流模型价格表，自动推断成本；支持**自定义 model definition**与**pricing tiers（按上下文长度/参数分档）**；支持按 user / tag / session 聚合与 Metrics API | ✅ 有成本追踪 |
| **接入改造量（Java / LangChain4j / Micrometer）** | Loki：加日志输出 + Loki 容器；Tempo：加 OTel tracing（Spring Boot 3 **原生支持 OTLP**）+ Tempo 容器。**都要自己定义 span/日志结构并手写 `gen_ai.*` 属性** | **OTLP 端点 `/api/public/otel`**，支持 HTTP/JSON 与 HTTP/protobuf（**gRPC 目前不支持**）。**无官方 Java SDK**，但可用任意 OTel Java SDK 直发；官方明确提到 OpenLLMetry / OpenLIT "extend Language support of Langfuse tracing to **Java** and Go" | **有 LangChain4j 的 Java 插桩**：`com.arize.instrumentation` / `io.openinference` 下的 `openinference-instrumentation-langchain4j`（含 **Agent 与 Tool Listener**，changelog 至 0.1.8）。**但见下方 ⚠️ 警告** |
| **许可** | Grafana OSS = **AGPLv3**；Loki/Tempo = **AGPLv3**（Prometheus = Apache-2.0） | **MIT Expat**（`ee/`、`web/src/ee/`、`worker/src/ee/` 目录除外，走 EE 许可） | **Elastic License 2.0 (ELv2)** |
| **运维复杂度** | 中（+1~2 容器，无额外 DB） | **高**：官方 compose 需 Postgres + ClickHouse + Redis/Valkey + S3/MinIO；官方明示 "docker compose setup lacks high-availability, scaling capabilities, and backup functionality" | **低**：**单容器** `arizephoenix/phoenix`；UI+OTLP HTTP 在 **6006**，OTLP gRPC 在 **4317**；默认 SQLite，可切 **PostgreSQL** 以持久化/共享 |
| **评分 / 数据集 / 人工标注** | ❌ 无 | ✅ **有**：Scores（含人工标注）、Datasets、Experiments、Evaluations | ✅ **有**：Evals、Annotations、Datasets、Experiments（Phoenix 主打评测） |
| **数据主权 / 合规** | ✅ 全自建，数据不出内网 | ✅ 自托管；⚠️ 但需注意 self-host 的备份与保留策略（详见 `docs/research/langfuse-接入调研报告.md`） | ✅ 自托管，单容器，数据不出内网 |
| **是否已有本仓库调研** | 本报告 | **已有**：`docs/research/langfuse-接入调研报告.md`（1857 行，含源码级查证） | 本报告首次对比 |

### 4.2 ⚠️ 关于 Phoenix 的 LangChain4j 插桩：一个必须说清的事实

**好消息**：Arize 的 OpenInference 项目**确实**提供了 LangChain4j 的 Java 自动插桩，且有 Phoenix 官方接入指南（[LangChain4j Tracing | Phoenix](https://arize.com/docs/phoenix/integrations/java/langchain4j/langchain4j-tracing)），代码形态完整（含 `OtlpGrpcSpanExporter` 初始化、`service.name` / `SEMRESATTRS_PROJECT_NAME` 等资源配置）。

- 模块清单（[openinference/java README](https://raw.githubusercontent.com/Arize-ai/openinference/main/java/README.md)）：`openinference-semantic-conventions`、`openinference-instrumentation`、`openinference-instrumentation-langchain4j`、`openinference-instrumentation-annotation`（注解式手动埋点 `@Chain`/`@LLM`/`@Tool`/`@Agent`）；
- 能力演进：changelog 显示 **0.1.7（2026-04-03）新增 "Agent and Tool Listeners"**，0.1.8（2026-04-04）修复 hide inputs/outputs 处理 —— **工具调用轨迹是被明确支持的**。

**坏消息（务必核实后再决策）**：
- 上述 README 给出的坐标是 **`io.openinference:*:0.1.0-SNAPSHOT`**（快照版），Phoenix 官方指南里演示的 Gradle 依赖用的是 `project(path: ':instrumentation:openinference-instrumentation-langchain4j')`（**源码子项目引用**）；
- 本次核查 Maven Central：`com/arize/openinference-semantic-conventions` **有正式版本 0.1.5 / 0.1.6 / 0.1.9**；但 `io/openinference/` 路径**不存在**，`com/arize/instrumentation/openinference-instrumentation-langchain4j/`、`.../springAI/`、`.../openinference-instrumentation/` 目录在 Central 上**无 `maven-metadata.xml`**（即**未见已发布的正式构件**）。
- **【待核实】**：无法确定是否存在其他发布渠道（Sonatype snapshots / JitPack）。**结论：采用前必须先在私有 Nexus 上做一次"能否拉到 LangChain4j 插桩依赖"的实测**，否则会误判接入成本。

> 这与本项目已有的 `docs/research/langfuse-接入调研报告.md` §2.5 的发现形成呼应：LangChain4j 官方有版本对齐的 `dev.langchain4j:langchain4j-observation` 与 `langchain4j-micrometer-metrics`，这条路径的**依赖确定性显著更高**。

---

## 5. D. 成本统计的通用工程问题

### 5.1 在 metrics 里按 taskId 打标签会有什么后果？（量化）

#### 先看现状（代码级）

现有 4 个指标的实际标签（`AiModelMetricsCollector.java`）：

| 指标 | 标签 |
|---|---|
| `ai_model_requests_total` | `user_id`, `app_id`, `model_name`, `status` |
| `ai_model_errors_total` | `user_id`, `app_id`, `model_name`, **`error_message`** ⚠️ |
| `ai_model_tokens_total` | `user_id`, `app_id`, `model_name`, `token_type` |
| `ai_model_response_duration_seconds` | `user_id`, `app_id`, `model_name` |

#### 加上 `task_id` 后的量化（【推断】，基于官方规则 + 本项目实际标签）

设定一个保守场景：**1000 任务/天、每任务 5 次 LLM 调用、Prometheus 默认保留 15 天**（保留期默认值来自 [Storage 文档](https://prometheus.io/docs/prometheus/latest/storage/)："the retention time defaults to `15d`"）。

| 指标 | 序列数估算 | 算法 |
|---|---|---|
| 窗口内不同 taskId 数 | **≈ 15,000** | 1000/天 × 15 天 |
| `ai_model_requests_total` | **≈ 15,000 ~ 45,000** | taskId × (model_name × status) 的每任务实际组合数，保守取 1~3 |
| `ai_model_tokens_total` | **≈ 45,000 ~ 180,000** | 上者 × `token_type`（input/output/total 共 ≥3 值） |
| `ai_model_response_duration_seconds` | **≈ 30,000 ~ 90,000** | Timer 至少产出 `_count` + `_sum`（若开启 histogram buckets 则再 ×N） |
| **合计（仅 task_id 一项带来）** | **≈ 9 万 ~ 30 万条序列** | |

**更致命的是增长模式**：

```
user_id 序列数  ∝  用户总数        → 有界，达到稳态后不再增长 ✅
task_id 序列数  ∝  历史任务累计数   → 无界，15 天内只增不减、且永不复用 ❌
```

- 每条 `task_id` 序列在 15 天窗口内**只被写入 5 次左右**（该任务的 5 次 LLM 调用），之后成为**纯垃圾**，但仍占用 Head block 内存直到保留期结束；
- 任务量翻倍 → 序列数翻倍；**这是一个随业务规模线性劣化的架构决策**，不是"调参能救"的问题。

**对照官方阈值**：
- 官方建议单指标基数 **<10**，>100 就该"**moving the analysis away from monitoring and to a general-purpose processing system**"；这里的量级是 **10 万级**，超了 3~4 个数量级；
- 官方 node_exporter 举例中，**"两位数百万级" 就被明确判定为 "too much for the current implementation of Prometheus"**；
- 代价还包括：`topk(10, sum by (task_id) ...)` 这类查询要对十几万条序列做聚合，面板会明显变慢；以及这些无用序列**挤占了其他有价值指标的额度**（官方原话："there's an opportunity cost as you can't have other, potentially more useful metrics on this machine any more"）。

#### ✅ 推荐做法

| 数据 | 放哪 | 理由 |
|---|---|---|
| **任务级明细**（每次 LLM 调用、每次工具调用、token、成本） | **关系库（MySQL）** | 需要按 taskId 精确检索、需要文本/JSON、需要事务与审计 |
| **任务级轨迹正文** | **关系库 / 对象存储**（大正文） | OTel 官方也建议"内容外置 + span 留引用" |
| **metrics 标签** | **只保留有界聚合维度**：`model_name`、`status`、`token_type`、（评估后的）`user_id` / `app_id` | 维度有界、可长期复用、适合做大盘与告警 |
| **metrics 定义** | 新增**低基数**的成本指标，如 `ai_model_cost_usd_total{model_name,status}` | 支撑"总花费/按模型花费"大盘与预算告警 |
| **跨系统跳转** | Prometheus **exemplars**（可选，挂 trace_id） | 从大盘曲线点进单条 trace；**不增基数**，但不能作为检索维度 |
| **`error_message`** | **从 label 中移除**，改为低基数 `error_type`；原文落日志/明细表 | 见 §2.6，这是现存隐患 |

> **一句话原则**：**metrics 回答"多少/多快/多贵"（聚合、有界），关系库回答"是哪一次、内容是什么"（明细、无界）。** 二者用同一个埋点**双写**，各司其职。

---

### 5.2 「一个任务多轮 LLM 调用费用累加」两种方案的实现与取舍

假设单次调用成本：

```
cost = prompt_tokens × price_in(model) + completion_tokens × price_out(model)
```

#### 方案 A：在 metrics（Prometheus）里实现

**做法**：
1. 在 `AiModelMetricsCollector` 里按模型价格算出 `cost`；
2. 累加到一个**低基数**计数器：`ai_model_cost_usd_total{user_id, app_id, model_name}`；
3. 大盘用 `sum by (model_name) (increase(ai_model_cost_usd_total[$__range]))` 展示。

**能否回答"这个任务花了多少钱"？**
- ❌ **不能**。若加 `task_id` 标签 → §5.1 的基数爆炸；
- ⚠️ 折中：写一条 Recording Rule 定期把 per-task 成本"压平"，但**任务维度本身就被丢掉了**，只剩总量；
- ⚠️ 另一个折中：用 exemplars 把单次调用成本挂到时间点上 —— **exemplar 不可作为查询维度**，无法"按 task 求和"。

| 优点 | 缺点 |
|---|---|
| 写路径极轻（内存计数，scrape 时暴露） | **无法按 taskId 归集**（根本性缺陷） |
| 天然适合大盘、告警、预算 | 精度受抓取/聚合影响；无法逐笔对账 |
| 已有 Grafana 看板可直接扩展 | 序列基数随维度增长 |
| 保留期外自动丢弃 | **价格变更无法回填历史**（价格写死在代码/配置里，历史成本不可重算） |

**适用**：**全局/按模型/按用户**的成本大盘与告警。

#### 方案 B：在关系库（MySQL）里实现

**做法**（【推断】的具体设计）：

```sql
-- ① 明细表：每次 LLM / 工具调用一行（source of truth）
CREATE TABLE ai_call_record (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  task_id           BIGINT       NOT NULL,           -- 关联 gen_task.id
  call_seq          INT          NOT NULL,           -- 任务内第几次调用
  call_type         VARCHAR(16)  NOT NULL,           -- llm / tool
  user_id           BIGINT       NULL,
  app_id            BIGINT       NULL,
  model_name        VARCHAR(64)  NULL,
  tool_name         VARCHAR(128) NULL,
  tool_arguments    JSON         NULL,               -- 工具入参
  tool_result       MEDIUMTEXT   NULL,               -- 工具返回值
  content           MEDIUMTEXT   NULL,               -- 对话正文（可选，考虑体积）
  prompt_tokens     INT          NULL,
  completion_tokens INT          NULL,
  -- ⭐ 落库当时生效的单价，保证历史成本可复算/可审计
  input_price       DECIMAL(18,10) NULL,
  output_price      DECIMAL(18,10) NULL,
  cost_usd          DECIMAL(18,10) NULL,
  status            VARCHAR(16)  NOT NULL,
  error_type        VARCHAR(64)  NULL,
  error_message     TEXT         NULL,
  started_at        DATETIME(3)  NOT NULL,
  finished_at       DATETIME(3)  NULL,
  duration_ms       INT          NULL,
  PRIMARY KEY (id),
  KEY idx_task_seq (task_id, call_seq),          -- 轨迹视图主查询路径
  KEY idx_user_time (user_id, started_at),       -- 用户维度分析
  KEY idx_app_time  (app_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ② 任务级成本冗余字段（O(1) 读取，避免每次 SUM）
ALTER TABLE gen_task
  ADD COLUMN prompt_tokens_total     INT           NULL,
  ADD COLUMN completion_tokens_total INT           NULL,
  ADD COLUMN cost_usd_total          DECIMAL(18,10) NULL;

-- ③ 每次调用完成后原子累加
UPDATE gen_task
   SET cost_usd_total          = cost_usd_total + :cost,
       prompt_tokens_total     = prompt_tokens_total + :pt,
       completion_tokens_total = completion_tokens_total + :ct
 WHERE id = :taskId;
```

- **轨迹视图**：`SELECT * FROM ai_call_record WHERE task_id = ? ORDER BY call_seq` —— 精确、有序、可展开 JSON；
- **单任务成本**：`SELECT cost_usd_total FROM gen_task WHERE id = ?` —— **O(1)**；
- **价格版本**：额外维护 `ai_model_price(model_name, input_price, output_price, effective_from)`；把单价冗余进明细行，保证历史成本可审计、可复算。

| 优点 | 缺点 |
|---|---|
| ✅ **能精确回答"这个任务的成本"** | 写放大：每次调用一条记录 |
| ✅ 轨迹正文/参数/返回值有处可放 | 需自己维护模型价格表并处理价格变更 |
| ✅ 可审计、可对账、可回填 | 数据量增长需要归档/TTL 策略 |
| ✅ 零新增中间件（复用现有 MySQL） | 高并发下需注意写入热点（可按 taskId 分片或异步批量写） |
| ✅ 与现有管理端/前端天然打通 | 聚合分析（跨任务多维）不如列式库 |

**适用**：**任务级/用户级明细**，即本需求的 ①③。

#### 两种方案的取舍总结

| 问题 | Metrics（Prometheus） | 关系库（MySQL） |
|---|---|---|
| 单任务成本 | ❌ 做不到 | ✅ 精确 |
| 全局成本大盘 | ✅ 强项 | ⚠️ 需聚合查询 |
| 轨迹正文 | ❌ 存不了 | ✅ |
| 基数风险 | ⚠️ 高（若加 taskId） | ✅ 无 |
| 历史价格回填 | ❌ | ✅（单价冗余） |
| 新增组件 | 0 | 0 |
| 读写成本 | 极低 | 中 |

> **结论：两者不是竞争关系，应当"同一埋点双写"。** metrics 侧保留 `ai_model_cost_usd_total{model_name,status}` 做大盘与预算告警；关系库侧保留明细做任务级监管。**唯一要避免的，是把 `task_id` 塞进 Prometheus 标签。**

---

## 6. 最终建议（分场景）

### 6.1 直接回答 C8

> **对于「看单任务轨迹 + token + 成本」这个具体需求，推荐：平台自己的管理端（MySQL 存轨迹 + 前端展示）作为主干，Langfuse 作为可选增强，Grafana 栈保持"聚合大盘"角色不变，不建议用 Loki/Tempo 承载轨迹正文。**

**理由（三条）**：
1. **Grafana 栈做不到单任务轨迹**（§2.5），补 Loki/Tempo 虽然能做到，但**引入新的存储系统 + 对象存储**，而**得到的效果（通用日志/树视图）反而弱于专门做这件事的平台**；
2. **MySQL 路径的边际成本接近于零**：数据库已有、ORM 已有、管理端与鉴权已有（见 `docs/research/AI管理端-可行性调研与设计方案.md` §1.5「管理端地基：已有 80%」）、项目**已经通过 SSE 推送四类消息**（思考/工具请求/工具执行结果/正文）——**轨迹数据本来就在手里，只是没落库**；
3. **OTel 官方规范本身**就建议生产环境"内容外置存储 + span 上留引用"（§3.2），**MySQL 存正文 + OTLP 上报引用**正是官方推荐形态，二者可同时成立、互不冲突。

### 6.2 分场景建议

| 场景 | 推荐方案 | 理由 |
|---|---|---|
| **只想要 ①②③，团队小、不想加中间件** | ✅ **纯自研管理端**：MySQL 明细表 + 现有管理端 UI | 零新增组件，工作量可控，完全满足需求 |
| **要 ①②③ + 想要成熟的 LLM UI / 会话视图 / 评测标注** | ✅ **管理端 + Langfuse**（管理端存权威明细，Langfuse 做探索与评测） | 已有 `docs/research/langfuse-接入调研报告.md` 的源码级结论支撑；注意 6 容器与备份成本 |
| **要 ①②③ + 已有 Grafana 信仰/统一看板诉求** | ⚠️ **管理端 + Loki**（Loki 只存**轻量轨迹事件**，正文留 MySQL，用 trace_id/task_id 关联） | 折中：Grafana 里"看得到节奏"，细节跳管理端；`task_id` 必须走 structured metadata |
| **要做 agent 链路性能分析（哪一步慢、哪一步失败）** | ⚠️ **Tempo**（OTLP trace + `gen_ai.*` semconv），正文外置 | 树形最贴切；配合 exemplars 从 Grafana 大盘跳转 |
| **不想自运维，且能接受数据出内网** | **Grafana Cloud AI Observability**（OpenLIT，public preview）或 Langfuse Cloud | 开箱看板；但 AI 对话正文属敏感数据，需过合规 |
| **预算/合规严格，禁止数据出内网 + 要 LLM 专用 UI** | **Arize Phoenix 自托管**（单容器，ELv2） | 轻；**但必须先验证 LangChain4j 插桩依赖能否获取**（§4.2） |

### 6.3 推荐的落地路线（【推断】，建议三步走）

**第 0 步（本周，零风险）——修基数隐患**
- 把 `error_message` 从 Prometheus label 中移除，改为低基数 `error_type`（§2.6）；
- 保留原 Grafana 大盘不动。

**第 1 步（主干）——MySQL 轨迹落库 + 管理端展示**
- 建 `ai_call_record` 表（§5.2 方案 B），在现有 SSE 四类消息的推送点与 `ChatModelListener` / `ToolExecutedEvent` 埋点处落库；
- `gen_task` 增加 token/成本冗余字段，写完即累加；
- 管理端新增「任务轨迹」页：按 `task_id` 查明细，按 `call_seq` 排序渲染（对话气泡 + 工具调用卡片 + token/成本小计）。
- **这一步完成后，需求 ①②③ 已全部满足，且没有任何新增中间件。**

**第 2 步（可选增强）——metrics 补齐 + 跨系统跳转**
- 新增 `ai_model_cost_usd_total{model_name,status}` 低基数成本指标 → Grafana 大盘具备"成本"视图与预算告警；
- 若已接 OTel tracing：开启 Prometheus `--enable-feature=exemplar-storage` + 配置 Micrometer `SpanContext`，实现"从 Grafana 成本曲线点一下跳进管理端该任务的轨迹页"。**exemplars 不增基数**（§2.3）。

**第 3 步（按需）——引入 Langfuse 或 Phoenix**
- 仅当出现"需要 LLM 专用 UI / 会话树 / 人工评分 / 数据集 / A-B 实验"的需求时再引入；
- 建议**只上报 OTLP span（含 `gen_ai.*` 属性与引用 ID），正文仍以 MySQL 为准**，兼顾体验、成本与合规。

---

## 附录 A：来源清单

**Prometheus**
- [Instrumentation（含 "Do not overuse labels" 基数准则）](https://prometheus.io/docs/practices/instrumentation/)
- [Naming](https://prometheus.io/docs/practices/naming/)
- [Storage（保留期默认 15d、1-2 bytes/sample、容量公式）](https://prometheus.io/docs/prometheus/latest/storage/)
- [FAQ（tens of millions of active series）](https://prometheus.io/docs/introduction/faq/)
- [Feature flags（Exemplars storage、circular buffer、~100 bytes/trace_id）](https://prometheus.io/docs/prometheus/latest/feature_flags/)
- [OpenMetrics v1.0.0 Spec §Exemplars（128 字符上限、references to data outside the MetricSet）](https://github.com/prometheus/OpenMetrics/blob/v1.0.0/specification/OpenMetrics.md#exemplars)
- [prometheus/docs issue #2291 — "Prometheus Docs Recommend Bizarrely Low Cardinality"](https://github.com/prometheus/docs/issues/2291)

**Grafana / Loki / Tempo**
- [Grafana data sources](https://grafana.com/docs/grafana/latest/datasources/)
- [Grafana — Traces in Explore（Trace View / Node graph / Service graph）](https://grafana.com/docs/grafana/latest/explore/trace-integration/)
- [Grafana — View logs（日志详情/字段展开/Logs Drilldown）](https://grafana.com/docs/grafana/latest/visualizations/simplified-exploration/logs/view-logs/)
- [Loki — Label best practices](https://grafana.com/docs/loki/latest/get-started/labels/bp-labels/)
- [Loki — Cardinality](https://grafana.com/docs/loki/latest/get-started/labels/cardinality/)
- [Loki — Structured metadata](https://grafana.com/docs/loki/latest/get-started/labels/structured-metadata/)
- [Loki — Architecture（单对象存储后端、组件清单）](https://grafana.com/docs/loki/latest/get-started/architecture/)
- [Loki — Storage（对象存储选型、Single Store TSDB）](https://grafana.com/docs/loki/latest/operations/storage/)
- [Tempo — Architecture（"Tempo stores all trace data in object storage"）](https://grafana.com/docs/tempo/latest/operations/architecture/)
- [Tempo — Configuration](https://grafana.com/docs/tempo/latest/configuration/)
- [Tempo — Query with TraceQL](https://grafana.com/docs/tempo/latest/traceql/)

**Grafana Cloud AI Observability（官方 LLM 方案）**
- [Grafana Cloud AI Observability（OpenLIT Observability）总览](https://grafana.com/docs/grafana-cloud/monitor-applications/ai-observability/)
- [GenAI 监控](https://grafana.com/docs/grafana-cloud/monitor-applications/ai-observability/genai/)
- [GenAI Observability 看板（成本/token）](https://grafana.com/docs/grafana-cloud/monitor-applications/ai-observability/genai/observability/)
- [GenAI Agent Observability（含 agent logs + trace/span ID 关联）](https://grafana.com/docs/grafana-cloud/monitor-applications/ai-observability/genai/agent-observability/)
- [Setup Guide（OTLP Gateway / OTel Collector / Alloy）](https://grafana.com/docs/grafana-cloud/monitor-applications/ai-observability/setup/)
- [Grafana 官方博客：Build, buy, or open source?（2026-02-24）](https://grafana.com/blog/build-buy-or-open-source-understanding-your-options-with-grafana-s-ai-powered-observability/)
- [Open Source For You：Grafana Labs Extends AI Observability To OSS（2026-04，佐证 "AI Observability in Grafana Cloud (public preview)"）](https://www.opensourceforu.com/2026/04/grafana-labs-extends-ai-observability-to-oss-with-open-benchmark-launch/)

**OpenTelemetry / Micrometer / Spring Boot**
- [GenAI semantic conventions — Spans（含 Execute tool span、`gen_ai.tool.call.arguments/result`、内容采集策略）](https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/gen-ai-spans.md)
- [OTel 原 GenAI Spans 页面（已迁移声明）](https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-spans/)
- [Spring Boot 3.4 — Tracing（`management.otlp.tracing.*`、OpenTelemetry + OTLP）](https://docs.spring.io/spring-boot/3.4/reference/actuator/tracing.html)
- [Micrometer — Prometheus registry（Exemplars 不增基数、需 SpanContext）](https://docs.micrometer.io/micrometer/reference/implementations/prometheus.html)

**Langfuse**
- [Self-hosting 总览（Postgres / ClickHouse / Redis / S3 组件）](https://langfuse.com/self-hosting)
- [Docker Compose 部署](https://langfuse.com/self-hosting/deployment/docker-compose)
- [OpenTelemetry 接入（`/api/public/otel`、HTTP/JSON + HTTP/protobuf、不支持 gRPC）](https://langfuse.com/integrations/native/opentelemetry)
- [Token 与成本追踪（内置模型价格、自定义 model definition、pricing tiers）](https://langfuse.com/docs/observability/features/token-and-cost-tracking)
- [Sessions（多轮会话聚合）](https://langfuse.com/docs/observability/features/sessions)
- [Scores / 评测总览](https://langfuse.com/docs/evaluation/overview)
- [LICENSE（MIT Expat，`ee/` 目录除外）](https://raw.githubusercontent.com/langfuse/langfuse/main/LICENSE)
- [Quarkus LangChain4j 集成（说明 Spring Boot 需走 OTLP）](https://langfuse.com/integrations/frameworks/quarkus-langchain4j)

**Arize Phoenix / OpenInference**
- [Phoenix — Self-Hosting（单容器；6006 = UI+OTLP HTTP，4317 = OTLP gRPC；SQLite/PostgreSQL）](https://arize.com/docs/phoenix/self-hosting)
- [Phoenix — LangChain4j Tracing 指南（含 Java OTLP 初始化代码）](https://arize.com/docs/phoenix/integrations/java/langchain4j/langchain4j-tracing)
- [OpenInference Java README（模块与坐标 `io.openinference:*:0.1.0-SNAPSHOT`）](https://raw.githubusercontent.com/Arize-ai/openinference/main/java/README.md)
- [openinference-instrumentation-langchain4j CHANGELOG（0.1.7 新增 Agent/Tool Listeners）](https://raw.githubusercontent.com/Arize-ai/openinference/main/java/instrumentation/openinference-instrumentation-langchain4j/CHANGELOG.md)
- [Phoenix LICENSE（Elastic License 2.0）](https://raw.githubusercontent.com/Arize-ai/phoenix/main/LICENSE)

**本仓库内既有资料（供交叉参考）**
- `docs/research/AI管理端-可行性调研与设计方案.md`
- `docs/research/langfuse-接入调研报告.md`
- `grafana/ai_model_grafana_config.json`（19 个面板，含 `sum by (error_message)` 面板）
- `src/main/java/com/yupi/yuaicodemother/monitor/AiModelMetricsCollector.java`
- `src/main/java/com/yupi/yuaicodemother/monitor/AiModelMonitorListener.java`
- `prometheus.yml`（`/api/actuator/prometheus`，10s 抓取）

---

## 附录 B：证据等级与未核实项（诚实声明）

| # | 事项 | 状态 |
|---|---|---|
| 1 | 「Prometheus 每活跃序列占用多少 KB 内存」 | **【待核实】** 官方未公布权威值；本报告刻意未引用任何具体 KB 数字 |
| 2 | 「Loki 的官方 sizing 指南（CPU/内存/磁盘换算）」 | **【待核实】** `operations/size/`、`operations/sizing/` 均返回 404；本报告未给出资源数字 |
| 3 | 「Tempo 的官方 sizing 指南」 | **【待核实】** 同上，未给出资源数字 |
| 4 | 「TraceQL 对 `gen_ai.*` 属性的过滤语法与性能」 | **【待核实】** 未逐条验证 |
| 5 | 「OpenInference LangChain4j 插桩是否有正式发布版本」 | **【待核实】** Maven Central 未见正式构件，README 坐标为 `0.1.0-SNAPSHOT`；**采用前必须实测依赖可获取性** |
| 6 | 「Grafana OSS 是否有官方的 LLM 会话/轨迹面板插件」 | **【待核实】/ 倾向否定** 官方文档与插件目录中未找到；现有能力为通用日志/追踪渲染 |
| 7 | §2.6 的 `error_message` 基数隐患 | **【推断】**（基于源码事实 + 官方基数准则） |
| 8 | §5.1 的序列数量化、§5.2 的表结构设计、§6.3 的路线 | **【推断】**（基于官方规则与本项目实际标签的工程估算/设计） |
| 9 | 「Grafana Cloud AI Observability 为 Cloud 专属」 | **【官方】** 文档安装路径为 Grafana Cloud stack + 官方博客/报道称 "in Grafana Cloud (public preview)" |

> **使用提醒**：本报告中所有标注 **【待核实】** 的数字与能力，**不应**直接写入方案文档或对外承诺；需要时请先做针对性实测或补查官方 sizing 文档。
