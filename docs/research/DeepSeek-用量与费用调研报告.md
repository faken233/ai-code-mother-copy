# DeepSeek 开放平台 API：Token 用量返回与费用计算 · 技术调研报告

- **调研日期**：2026-09-10（UTC）/ 2026-09-11（北京时间）
- **调研方法**：官方文档 + 官方定价页 + **实机 API 探针验证**（使用本项目 `.env` 中的真实 `LLM_API_KEY` 发起了若干最小化请求，用量成本约几厘钱）
- **适用对象**：Java Spring Boot 3 + LangChain4j 1.18.x AI 代码生成平台（多轮工具调用 / LangGraph 工作流，需要按「任务」累计 token 与费用）
- **所有价格数字均为 2026-09-10 查询所得**

> ⚠️ **本报告最重要的结论**：官方 changelog 声明 `deepseek-chat` / `deepseek-reasoner` 已于 **2026-07-24 15:59 UTC 退役**，当前官方仅支持 `deepseek-flash` 与 `deepseek-v4-pro`。但**实机探针显示这两个旧名字目前仍可调用，并被静默路由到 `deepseek-flash`**，响应体 `model` 字段返回 `deepseek-flash`。这直接影响你项目里写死的模型名与计价口径。详见 §0.1。

---

## 0. 结论速览（TL;DR）

| # | 问题 | 结论 |
|---|---|---|
| A1 | usage 结构 | 6 个 token 计数字段 + 2 个 details 子对象，**没有费用字段**。`prompt_cache_hit_tokens`/`prompt_cache_miss_tokens` 存在且为必返字段 |
| A1 | 思维链是否单独计数/计费 | 单独计数（`completion_tokens_details.reasoning_tokens`），但**已包含在 `completion_tokens` 内**，因此按输出价计费，**不要再加一次** |
| A2 | 流式是否返回 usage | **返回，且无需任何参数**；`stream_options.include_usage=true` 也支持。usage 挂在最后一个带 `finish_reason` 的 chunk 上，之后是 `data: [DONE]`，**没有独立的 usage-only chunk** |
| A3 | 是否返回本次费用 | **不返回**。API 无任何 cost/price 字段（文档、OpenAPI schema、实测三方一致） |
| A4 | 上下文缓存 | **默认自动开启**，无需参数；命中部分走命中价（约为未命中价的 1/50）。实测第二次相同前缀调用 `hit=1152 / miss=143` |
| B5 | 当前价格 | `deepseek-flash` 空闲 **0.02 / 1 / 4 元**（命中/未命中/输出，每百万 tokens）；`deepseek-v4-pro` 空闲 **0.15 / 4.5 / 13.5 元**（查询日期 2026-09-10） |
| B6 | 错峰折扣 | 有。**空闲价 = 高峰价的一半**；高峰 = 北京时间周一至周五 9:00–12:00、14:00–18:00（其余全天含周末均为空闲） |
| B7 | 价格能否查询 API | 不能。`/models` 只返回 id/object/owned_by，无价格。官方明文保留调价权 |
| C8 | 余额 API | **有**：`GET /user/balance`，Bearer 鉴权，无需特殊权限。实测返回 CNY 余额 |
| C9 | 历史用量/账单 API | **没有公开 API**，只能在控制台网页看。第三方项目用的是 `platform.deepseek.com/api/v0/usage/*` 私有接口（需登录态 userToken，随时可能变更） |
| C10 | 按 API Key / 用户维度用量 | **API 侧没有**。后台页面支持按 API Key 筛选；请求参数 `user_id` 仅用于隔离与并发统计，**不产生用量报表**。多租户统计必须自建 |
| D11 | 费用怎么算 | 自建价格表 + 按次累加：`Σ(hit×P_hit + miss×P_miss + out×P_out) / 1e6`，用 `BigDecimal`，并按请求时刻判定峰谷价 |
| D12 | 开源方案 | litellm / genai-prices / tokencost 均覆盖 DeepSeek，但**价格数据全部滞后**（litellm 已有 open issue 承认）。genai-prices 的峰谷建模最接近官方，可作数据结构参考 |
| D13 | 币种 | **人民币为准确认**：官方有独立的中文（元）与英文（$）定价表，不是简单汇率换算；余额 API 可返回 `CNY` 或 `USD` |

---

## 0.1 必读：模型名现状（影响计价正确性）

### 官方说法

- **2026-04-24（V4 发布）**：*"⚠️ Note: deepseek-chat & deepseek-reasoner will be fully retired and inaccessible after Jul 24th, 2026, 15:59 (UTC Time). (Currently routing to deepseek-v4-flash non-thinking/thinking)"* —— [news260424](https://api-docs.deepseek.com/news/news260424)
- **Changelog**：*"The two legacy API model names, deepseek-chat and deepseek-reasoner, will be discontinued in three months (2026-07-24). During the current period, these two model names point to the non-thinking mode and thinking mode of deepseek-v4-flash, respectively."* —— [updates](https://api-docs.deepseek.com/updates)
- **2026-09-10（V4.1-Flash 发布）**：V4-Flash 与 V4-Flash-Vision-Exp 也已下线，兼容名 `deepseek-v4-flash` / `deepseek-v4-flash-vision-exp` **临时路由**到 V4.1-Flash 并按 Flash 价计费；`deepseek-v4-pro` 自 **2026-09-14 12:00 北京时间**起也将被路由到 V4.1-Flash 并按 Flash 价计费。 —— [news260910](https://api-docs.deepseek.com/news/news260910)

### 实机验证结果（2026-09-10 实测）

```bash
# 1) 请求 model = "deepseek-chat"
{"id":"39470ada-...","object":"chat.completion","created":1789058521,
 "model":"deepseek-flash",              # ← 实际服务模型被改写
 "choices":[{"index":0,"message":{"role":"assistant","content":"Hi"},"finish_reason":"length"}],
 "usage":{"prompt_tokens":5,"completion_tokens":1,"total_tokens":6,
          "prompt_tokens_details":{"cached_tokens":0},
          "prompt_cache_hit_tokens":0,"prompt_cache_miss_tokens":5},
 "system_fingerprint":"aeb56401ca74e127821c4f9126dcb669"}

# 2) 请求 model = "deepseek-reasoner"
{"id":"29eb0159-...","model":"deepseek-flash",
 "choices":[{"index":0,"message":{"role":"assistant","content":"",
            "reasoning_content":"We"},"finish_reason":"length"}],
 "usage":{"prompt_tokens":31,"completion_tokens":1,"total_tokens":32,
          "prompt_tokens_details":{"cached_tokens":0},
          "completion_tokens_details":{"reasoning_tokens":1},   # ← 思考模式才有
          "prompt_cache_hit_tokens":0,"prompt_cache_miss_tokens":31},
 "system_fingerprint":"aeb56401ca74e127821c4f9126dcb669"}

# 3) 请求 model = "deepseek-v3"（不存在的名字）
HTTP 400
{"error":{"message":"The supported API model names are deepseek-flash, deepseek-v4-pro, but you passed deepseek-v3.",
          "type":"invalid_request_error","param":null,"code":"invalid_request_error"}}

# 4) GET /models
{"object":"list","data":[{"id":"deepseek-flash","object":"model","owned_by":"deepseek"},
                         {"id":"deepseek-v4-pro","object":"model","owned_by":"deepseek"}]}
```

**给项目的三条硬结论：**

1. **不要用响应里的 `model` 直接反推请求参数**：你请求 `deepseek-chat`，响应会告诉你 `deepseek-flash`。**计价必须用响应体的 `model` 字段**（LangChain4j 的 `chatResponse().modelName()` 正是取响应 `model` 字段，见 §D11.4），否则会按过时的价格算。
2. **旧模型名目前是"能用但不保证"**：官方文档说已"不可访问"，实测仍可用（截至 2026-09-10）。生产环境不应依赖，建议把 `application.yml` / `application-prod-sample.yml` / 微服务 `application.yml` 中的 `deepseek-chat` / `deepseek-reasoner` 统一改为 `deepseek-flash`。
3. **"思考模式"不再是独立的模型名**：改用请求参数 `thinking: {"type": "enabled"|"disabled"}`（**默认 enabled**）与 `reasoning_effort: "none"|"low"|"high"|"max"`（默认 high；`minimal→low`、`medium/xhigh→high`、`ultra→max`）。 —— [Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode)

---

## A. 用量返回

### A1. `/chat/completions` 响应体 `usage` 完整结构

来源：[Chat Completions API 官方规范](https://api-docs.deepseek.com/api/create-chat-completion)（`usage` schema）+ 实机验证。

| 字段 | 类型 | 必返 | 含义 | 实测 |
|---|---|---|---|---|
| `prompt_tokens` | integer | ✅ | 输入 token 总数。**恒等于 `prompt_cache_hit_tokens + prompt_cache_miss_tokens`**（官方原文如此定义） | 实测 1295 = 1152 + 143 ✅ |
| `completion_tokens` | integer | ✅ | 生成 token 总数。**包含思维链 token** | ✅ |
| `total_tokens` | integer | ✅ | `prompt_tokens + completion_tokens` | ✅ |
| `prompt_cache_hit_tokens` | integer | ✅ | 本次输入中**命中**上下文缓存的 token 数 | ✅ |
| `prompt_cache_miss_tokens` | integer | ✅ | 本次输入中**未命中**缓存的 token 数 | ✅ |
| `prompt_tokens_details.cached_tokens` | integer | 否（对象在，字段可空） | OpenAI 兼容写法，**语义与数值等同 `prompt_cache_hit_tokens`** | 实测两者都是 1152 ✅ |
| `completion_tokens_details.reasoning_tokens` | integer | **否（思考模式才出现）** | 思维链（CoT）token 数 | ✅ 思考模式返回 1；**非思考模式整个 `completion_tokens_details` 对象不出现** |

**回答你的具体疑问：**

- **`prompt_cache_hit_tokens` / `prompt_cache_miss_tokens` 是否存在？** 存在，且是**必返**字段（非可选）。官方已经在 [Context Caching](https://api-docs.deepseek.com/guides/kv_cache) 页面明确说明「we have added two fields in the usage section」。
- **`completion_tokens_details.reasoning_tokens` 是否单独计数？是否计费？** 单独计数（有独立字段）。**关于计费，官方没有一句"reasoning tokens 单独计费/单独计价"的说明**（我在定价页、Thinking Mode 页、Chat Completions 页全文检索均未找到）。但有一条**强证据链**证明它**已经包含在 `completion_tokens` 里、按输出价计费**：
  1. 官方 Chat Completions schema 定义 `completion_tokens = "Number of tokens in the generated completion"`，而 Responses API 的官方示例给出 `output_tokens: 29` 且 `output_tokens_details.reasoning_tokens: 27` —— 27 个思维链 token 被算进了 29 个输出 token 里。
  2. 实机探针：`max_tokens: 1`、思考模式下返回 `completion_tokens: 1` 且 `reasoning_tokens: 1`，说明只生成 1 个 token（思维链）时，`completion_tokens` 就是 1，两者不是并列关系。
  3. 唯一的价格表只有一个「百万 tokens 输出」单价，没有「思维链 token 单价」这一行。

  → **结论：计费时只按 `completion_tokens × 输出单价` 计算，绝对不要再把 `reasoning_tokens` 加一遍。**（这也是所有主流实现——litellm / genai-prices——的做法：`reasoning_tokens` 只作分析维度。）
- **还有其他字段吗？** 没有了。`usage` 对象就只有上述 6 个 token 字段 + 2 个 details 子对象。**没有** `prompt_cache_miss_tokens` 之外的缓存计数字段，没有 `cost`、`price`、`currency`、`billing` 之类的字段。
- 顶层响应字段（实测）：`id`、`object`(`chat.completion`)、`created`、`model`、`choices`、`usage`、`system_fingerprint`。

### A2. 流式（`stream=true`）下的 usage

**官方原文**（[Chat Completions API](https://api-docs.deepseek.com/api/create-chat-completion)，`stream_options.include_usage` 字段说明）：

> *"If set to `true`, all chunks in the stream will include a `usage` field, whose value is `null` on every chunk except the last one. If omitted or set to `false`, the `usage` field is absent from all chunks except the last one.*
> *Either way, the last chunk before the `data: [DONE]` message carries the token usage statistics for the entire request in its `usage` field. Note that no separate usage-only chunk is emitted: the statistics ride on the last content chunk, whose `choices` array always contains exactly one element that carries no new content and a non-null `finish_reason`."*

**实机验证（两种写法都试了）：**

```bash
# A) stream=true，不带 stream_options
curl https://api.deepseek.com/chat/completions -d '{"model":"deepseek-v4-flash",...,"stream":true}'
# 最后两个 SSE 事件：
data: {"id":"ee7fc4c8-...","object":"chat.completion.chunk","model":"deepseek-flash",
       "choices":[{"index":0,"delta":{"content":"","reasoning_content":null},
                   "finish_reason":"length"}],
       "usage":{"prompt_tokens":37,"completion_tokens":8,"total_tokens":45,
                "prompt_tokens_details":{"cached_tokens":0},
                "completion_tokens_details":{"reasoning_tokens":8},
                "prompt_cache_hit_tokens":0,"prompt_cache_miss_tokens":37}}
data: [DONE]

# B) stream=true + "stream_options":{"include_usage":true}
# → 结果与 A 完全一致（同样只有一个带 usage 的 chunk）
```

**要点：**

1. **DeepSeek 不需要 `stream_options`**：与 OpenAI 不同（OpenAI 必须显式 `include_usage: true` 才有 usage），DeepSeek **默认就在最后一个 chunk 返回 usage**。你即使传了 `include_usage`，也不会多出独立的 usage chunk。
2. **usage 出现的时机**：紧贴在 `data: [DONE]` 之前的那个 chunk，它同时满足：`finish_reason` 非空 + `choices` 长度为 1 + `delta.content` 为空串。
3. **解析注意事项**：这个末尾 chunk 的 `choices` **不是空数组**（OpenAI 的 usage-only chunk 是 `choices: []`），`delta.content` 是空串而**不是** `null`。不要因为 `choices` 非空就把它当成普通内容 chunk 继续拼接（会把空串拼进去，无害但要小心）。
4. **思维链增量在流式里走 `delta.reasoning_content`**，与 `delta.content` 并列，实机确认（chunk 里同时出现 `"content":""` 与 `"reasoning_content":null` 的键位）。

**LangChain4j 侧（好消息）**：`langchain4j-open-ai` 的 `OpenAiStreamingChatModel` **自动注入** `stream_options.include_usage(true)`（`OpenAiStreamingChatModel.java:161`），并且 `OpenAiStreamingResponseBuilder` 会把 chunk 里的 `usage` 聚合，最终通过 `ChatResponse.metadata().tokenUsage()` 暴露给 `ChatModelListener.onResponse`。所以流式调用在 LangChain4j 里**能拿到完整 usage**，无需自己解析 SSE。

### A3. 是否返回本次请求的费用？

**没有。确认无此字段。**

- 官方 [Chat Completions API](https://api-docs.deepseek.com/api/create-chat-completion) 的 `usage` schema 里没有任何 cost/price 相关字段（只有 token 计数）。
- 官方 [定价页](https://api-docs.deepseek.com/quick_start/pricing) 只写「扣减费用 = token 消耗量 × 模型单价」（英文：*"The expense = number of tokens × price."*），并在余额侧扣减，**API 不吐费用**。
- 实机三重验证：非流式响应、流式末尾 chunk、Responses API 示例，全部只有 token 数。
- 官方也未提供任何"预估费用"或"本次请求计价"接口。

→ 结论与你的预期一致：**费用必须在应用侧自算**。

### A4. 上下文缓存（Context Caching）机制

官方文档：[Context Caching](https://api-docs.deepseek.com/guides/kv_cache)（中文页：[zh-cn/guides/kv_cache](https://api-docs.deepseek.com/zh-cn/guides/kv_cache)）

| 问题 | 官方答案 |
|---|---|
| 自动开启还是需要参数 | **自动，默认对全部用户开启**：*"The DeepSeek API Context Caching on Disk Technology is enabled by default for all users, allowing them to benefit without needing to modify their code."* **没有任何开关参数**（也没有 OpenAI 的 `prompt_cache_key` / `prompt_cache_retention`——Responses API 文档明确说这两个参数 *Not supported. Context caching is managed automatically*） |
| 对 usage 的影响 | 新增 `prompt_cache_hit_tokens` / `prompt_cache_miss_tokens` 两个字段（见 A1），且 `hit + miss == prompt_tokens` |
| 命中/未命中计费差异 | 命中价远低于未命中价：当前 `deepseek-flash` 命中 0.02 元/百万 vs 未命中 1 元/百万（**50 倍差**）；`deepseek-v4-pro` 命中 0.15 元 vs 未命中 4.5 元（**30 倍差**）。**费用必须分段计算，不能只用 prompt_tokens** |
| 命中条件 | 只能命中**完整匹配某个已持久化的缓存前缀单元**。持久化时机有三类：① 请求边界（用户输入末尾 / 模型输出末尾各产生一个单元）；② 系统检测到多个请求存在公共前缀时，把该公共前缀持久化为独立单元；③ 长输入/长输出按固定 token 间隔切出单元 |
| 可靠性 | *"best-effort"*，不保证 100% 命中；缓存构建需要数秒；不再使用后**几小时到几天**内自动清理 |
| 对随机的影響 | 缓存只匹配**输入前缀**，输出仍真实推理，`temperature` 等照常生效 |

**实机验证（这是给你项目最有价值的实测）：**

用同一段约 6836 字符的前缀（1295 个输入 token）连续调用三次 `deepseek-v4-flash`：

| 调用 | prompt_tokens | cached_tokens | prompt_cache_hit_tokens | prompt_cache_miss_tokens |
|---|---|---|---|---|
| 第 1 次 | 1295 | 0 | 0 | 1295 |
| 第 2 次（隔 6s） | 1295 | **1152** | **1152** | 143 |
| 第 3 次 | 1295 | **1152** | 1152 | 143 |

**结论：**
- 缓存**确实无需任何参数就生效**；
- **`prompt_tokens_details.cached_tokens` 与 `prompt_cache_hit_tokens` 数值完全一致**——这对 LangChain4j 用户极其关键，因为 LangChain4j 只解析前者（见 §D11.4）；
- 命中率不是 100%（1152/1295 ≈ 89%），因为前缀单元的切分是固定间隔/边界的，尾部不足一个单元的部分仍按未命中计价。

**对"工具调用循环 / LangGraph 多节点"的业务含义**：多轮请求天然共享前缀（系统提示词 + 已生成的代码 + 工具返回），**缓存命中率会很高**，如果只用 `prompt_tokens × 未命中价` 估算，会显著高估成本。

---

## B. 计费与价格

### B5. 当前官方价格表（查询日期：2026-09-10）

**官方定价页**：
- 英文（美元）：https://api-docs.deepseek.com/quick_start/pricing
- 中文（人民币）：https://api-docs.deepseek.com/zh-cn/quick_start/pricing

当前生效价格自 **2026-09-10 04:00 UTC** 起（随 DeepSeek-V4.1-Flash 发布下调）。 —— [news260910](https://api-docs.deepseek.com/news/news260910)

**人民币（元 / 百万 tokens）—— 官方中文页原文：**

| 计费项 | 时段 | `deepseek-flash` | `deepseek-v4-pro` |
|---|---|---|---|
| 输入（缓存命中） | 空闲 | **0.02 元** | 0.15 元 |
| 输入（缓存命中） | 高峰 | **0.04 元** | 0.30 元 |
| 输入（缓存未命中） | 空闲 | **1 元** | 4.5 元 |
| 输入（缓存未命中） | 高峰 | **2 元** | 9.0 元 |
| 输出 | 空闲 | **4 元** | 13.5 元 |
| 输出 | 高峰 | **8 元** | 27.0 元 |

**美元（$ / 1M tokens）—— 官方英文页原文：**

| 计费项 | 时段 | `deepseek-flash` | `deepseek-v4-pro` |
|---|---|---|---|
| Input (cache hit) | off-peak | $0.003 | $0.022 |
| Input (cache hit) | peak | $0.006 | $0.044 |
| Input (cache miss) | off-peak | $0.15 | $0.66 |
| Input (cache miss) | peak | $0.30 | $1.32 |
| Output | off-peak | $0.60 | $1.98 |
| Output | peak | $1.20 | $3.96 |

**模型元信息（同一页面）**：

| 项 | `deepseek-flash` | `deepseek-v4-pro` |
|---|---|---|
| 模型版本 | DeepSeek-V4.1-Flash | DeepSeek-V4-Pro-0813 |
| 上下文长度 | 1M | 1M |
| 最大输出 | 384K | 384K |
| 思考模式 | 支持，**默认开启** | 支持，默认开启 |
| 图像理解 | ✅ 支持 | ❌ 不支持 |
| Tool Calls / JSON / Responses API / Anthropic API | ✅ | ✅ |
| 并发限制 | 2500 | 500（2026-09-14 后请求将路由至 Flash） |

**关于你最初问的 `deepseek-chat` / `deepseek-reasoner` 价格：官方已不再列出**（当前价目表只有上面两个模型）。其**最后一次官方价格**（V3.2 时代，两模型同价）经 Wayback 快照查证为：

| 模型 | 输入（缓存命中） | 输入（缓存未命中） | 输出 |
|---|---|---|---|
| `deepseek-chat` / `deepseek-reasoner` | 0.2 元 | 2 元 | 3 元 |

来源：[2025-10-01 快照](https://web.archive.org/web/20251001000000/https://api-docs.deepseek.com/zh-cn/quick_start/pricing)（V3.2-Exp）、[2026-02-01 快照](https://web.archive.org/web/20260201000000/https://api-docs.deepseek.com/zh-cn/quick_start/pricing)（V3.2）数值一致。

> ⚠️ **不要用这个 0.2/2/3 去算今天的账**：旧名字现在实际由 `deepseek-flash` 服务，按 Flash 价格计费（官方 2026-07-01 定价页脚注 + 实测响应 `model` 字段均可佐证）。此数据仅供历史对账。

**价格变迁时间线**（用于对账历史任务）：

| 时间 | 事件 | 来源 |
|---|---|---|
| 2024-08-02 | 引入硬盘缓存，价格再降一个数量级 | [news0802](https://api-docs.deepseek.com/news/news0802) |
| 2025-09-29 | V3.2-Exp，API 价格下调 50%+ | [news250929](https://api-docs.deepseek.com/news/news250929) |
| 2026-04-26 20:15 (北京) | 全系输入缓存命中价降至首发价 1/10 | [2026-06-01 快照](https://web.archive.org/web/20260601000000/https://api-docs.deepseek.com/zh-cn/quick_start/pricing) 脚注 (2) |
| 2026-07-24 15:59 UTC | `deepseek-chat` / `deepseek-reasoner` 名义退役 | [updates](https://api-docs.deepseek.com/updates) |
| **2026-08-16 16:00 UTC** | **引入峰谷价（空闲 = 高峰一半），并整体上调** | [news260813](https://api-docs.deepseek.com/news/news260813)、[updates](https://api-docs.deepseek.com/updates) |
| 2026-08-23 起 | 周末全天按空闲价计费 | [genai-prices 备注](https://github.com/pydantic/genai-prices/issues/582)（官方现价目表脚注 (3) 亦为「周一至周五高峰」） |
| **2026-09-10 04:00 UTC** | **V4.1-Flash 发布，价格下调至现价** | [news260910](https://api-docs.deepseek.com/news/news260910) |
| 2026-09-14 12:00 (北京) | `deepseek-v4-pro` 请求将全部路由到 V4.1-Flash，按 Flash 计价 | [定价页脚注 (2)](https://api-docs.deepseek.com/zh-cn/quick_start/pricing) |

### B6. 错峰折扣（Off-Peak Discount）

**官方原文（英文定价页脚注 (3)）：**

> *"(3) Off-peak rates are half of the peak rates. Peak hours are 01:00 - 04:00 and 06:00 - 10:00 UTC, Monday through Friday (all other hours are off-peak)."*

**官方原文（中文定价页脚注 (3)）：**

> *(3) 空闲时段价格为高峰时段价格的一半。高峰时段为北京时间周一至周五 9:00 - 12:00、14:00 - 18:00（其余为空闲时段）。*

**要点：**

| 问题 | 答案 |
|---|---|
| 是否有错峰折扣 | **有**，空闲价 = 高峰价的 **50%** |
| 高峰时段（UTC） | 周一至周五 **01:00–04:00** 与 **06:00–10:00** |
| 高峰时段（北京时间） | 周一至周五 **9:00–12:00** 与 **14:00–18:00**（与 UTC 表述完全一致，UTC+8） |
| 其余时间 | 全天空闲价，**周末 48 小时全为空闲** |
| chat 和 reasoner 折扣是否不同 | 该二模型名已退役。当前两个模型 `deepseek-flash` / `deepseek-v4-pro` **折扣机制相同**（都是空闲 = 高峰一半），但**基准价不同** |
| 每日高峰总时长 | 7 小时/工作日（3 + 4），即每周 35 小时高峰、133 小时空闲 |

**未找到官方说明的点（重要，做实现时要自己定策略）：**

- **一次请求跨越峰谷边界时按哪个时刻判定？** 官方文档**未说明**。litellm 的实现选择是「**按请求完成时刻**」定价（*"A request is priced by its completion time."*，见 [litellm Off-Peak Pricing 文档](https://docs.litellm.ai/docs/proxy/off_peak_pricing)），但这只是 litellm 的约定，**不是 DeepSeek 的官方规则**。
- **建议**：以「请求发起时刻」判定（简单、可复现），并在日志里记录 `start_time`/`end_time`/是否高峰；对跨边界请求（极少）可在对账时按高峰价保守处理，或直接打标以便人工核对。
- 计费按**整个请求**落在高峰还是空闲（不会把一次请求的 token 按时间切段）。

### B7. 价格是否可能随时调整？有 API 查价格吗？

**会调整，且没有价格查询 API。**

官方定价页原文：

> *"Product prices may vary and DeepSeek reserves the right to adjust them. We recommend topping up based on your actual usage and regularly checking this page for the most recent pricing information."*
> 中文：*"产品价格可能发生变动，DeepSeek 保留修改价格的权利。请您依据实际用量按需充值，定期查看此页面以获知最新价格信息。"*

- **没有价格 API**：`GET /models`（[Lists Models](https://api-docs.deepseek.com/api/list-models)）只返回 `id` / `object` / `owned_by`，**不含价格**。实测确认：`{"id":"deepseek-flash","object":"model","owned_by":"deepseek"}`。
- **没有 `/pricing` 之类的端点**：官方 API sitemap（`https://api-docs.deepseek.com/sitemap.xml`）全部端点为：`create-chat-completion`、`create-completion`、`create-file`、`create-response`、`delete-file`、`get-user-balance`、`list-files`、`list-models`、`retrieve-file`。**价格页只是网页**，且是 SSR 的 HTML 表格（可抓取但无正式 API，且页面结构可能变）。
- **价格调整频率很高**：仅 2026 年就至少 4 次（4/26、8/16、8/23、9/10）。

**工程建议**：把价格表做成**带生效时间的配置**（`model` + `price_version` + `effective_from`），任务记账时**固化当时的单价快照**，这样历史任务的费用永远可复现，也不会因为改了配置导致历史金额漂移。

---

## C. 账户与用量查询 API

### C8. 余额查询 API —— 有，且公开可用 ✅

**官方文档**：https://api-docs.deepseek.com/api/get-user-balance

| 项 | 内容 |
|---|---|
| 端点 | `GET /user/balance`（完整：`https://api.deepseek.com/user/balance`） |
| 鉴权 | `Authorization: Bearer <API Key>`（HTTP Bearer Auth，与调用模型同一个 Key） |
| 特殊权限 | **不需要**，官方文档公开列出（注意：这是唯一一个非 Inference 的公开账户端点） |
| 响应字段 | `is_available`(boolean，余额是否够用) ／ `balance_infos` 数组，每项含 `currency`(`CNY`/`USD`)、`total_balance`(含赠送+充值)、`granted_balance`(未过期赠送)、`topped_up_balance`(充值) |

**实机验证（本项目的 Key）：**

```bash
curl https://api.deepseek.com/user/balance -H "Authorization: Bearer $LLM_API_KEY"
# → {"is_available":true,
#    "balance_infos":[{"currency":"CNY","total_balance":"20.65",
#                      "granted_balance":"0.00","topped_up_balance":"20.65"}]}
```

注意：**所有金额都是字符串**（不是数字），且是**最小货币单位之外的常规小数表示**（"20.65" = 20.65 元）。Java 侧用 `BigDecimal` 直接解析字符串，**不要**先转 `double`。

鉴权失败返回 `401`：`{"error":{"message":"Authentication Fails, Your api key: ****alid is invalid","type":"authentication_error",...}}`

**官方扣费规则**（[定价页](https://api-docs.deepseek.com/quick_start/pricing)）：

> *"The corresponding fees will be directly deducted from your topped-up balance or granted balance, with a preference for using the granted balance first when both balances are available."*
> 中文：*"当充值余额与赠送余额同时存在时，优先扣减赠送余额。"*

→ 做"任务费用"时要注意：**余额减少量 ≠ 任务费用**（余额是账户级、连续变化的，且优先扣赠送余额）。**不要用余额差值反推单任务费用。**

### C9. 历史用量 / 账单查询 API —— **没有公开 API** ❌

**结论：官方没有提供按时间段查询 token 消耗或费用的公开 API。只能在控制台网页看。**

论据：
1. 官方 API sitemap 全量端点列表（见 §B7）中**没有任何 usage / billing / cost / invoice 端点**，只有 `get-user-balance`。
2. 官方文档中没有 "usage query"、"billing API"、"invoice API" 之类的页面。
3. 社区实测：有两个独立开源项目都绕道使用了**平台网页的私有接口**，并明确标注其为非公开：
   - [AzureHalcyon/dsh-deepseek-usage](https://github.com/AzureHalcyon/dsh-deepseek-usage)（DSH 插件的 DeepSeek 余额/用量面板）：
     > 余额：`api.deepseek.com/user/balance`（官方公开），认证 API Key；
     > 本月用量/消费：`platform.deepseek.com/api/v0/usage/amount`、`usage/cost`（网页接口），认证**登录态 userToken**；
     > 今日用量/消费：`platform.deepseek.com/api/v0/usage/export`（北京时间窗口）。
     > **"用量/消费接口是 platform.deepseek.com 的私有接口，非官方公开 API，可能随时变更。"**
   - [steipete/CodexBar `docs/deepseek.md`](https://github.com/steipete/CodexBar/blob/main/docs/deepseek.md)：
     > `GET https://platform.deepseek.com/api/v0/usage/amount?month=<month>&year=<year>`
     > `GET https://platform.deepseek.com/api/v0/usage/cost?month=<month>&year=<year>`
     > *"These are private dashboard endpoints rather than documented public API endpoints and may change without notice."*
     并指出 *"an API key cannot authenticate the private dashboard endpoints"*——**API Key 无法用于这些私有接口**，必须用浏览器登录态的 `userToken`（`localStorage.getItem("userToken")`）。
     另有 *"There is no session or weekly window — DeepSeek does not expose per-window quota via API."*

**给项目的判断**：不要基于这些私有接口做生产功能（拿不到 userToken、随时可能挂）。**逐任务费用只能靠调用侧自算并落库**（见 §D）。

### C10. 能否按 API Key / 按用户区分用量？

| 维度 | 能力 | 说明 |
|---|---|---|
| 按 API Key（API 侧） | ❌ 无 | 没有任何接口能按 Key 查用量 |
| 按 API Key（网页侧） | ✅ 有 | 控制台用量页面支持「按 API 筛选」——这是上述 DSH 插件 README 里提到的功能（`按 api_key_name 筛选单个 key 的用量（对应官方页面的「按 API 筛选」）`），但依赖私有接口 + 登录态 |
| 请求参数 `user_id` | ⚠️ 仅隔离，**不是用量维度** | 官方 [Rate Limit & Isolation](https://api-docs.deepseek.com/quick_start/rate_limit)：`user_id` 用于 **① 内容安全隔离 ② KVCache 隔离（隐私）③ 调度隔离 ④ 按 user_id 的并发限制**。**官方明确说并发限制是账户级、与 API Key 无关**（*"Concurrency limits are calculated at the account level, regardless of which API Key is used"*），且**从未提及 user_id 会产生任何用量/费用报表** |
| 多租户统计 | ❌ 必须自建 | 结论：**要在应用侧按 `userId` / `appId` / `taskId` 记账**，DeepSeek 官方不提供此能力 |

`user_id` 的两种传法（如果你仍想用它做隔离）：
- HTTP body：`{"model":"deepseek-flash","messages":[...],"user_id":"your_user_id"}`（正则 `[a-zA-Z0-9\-_]+`，≤512 字符，**不得包含用户隐私信息**）
- OpenAI SDK：需放在 `extra_body` 里

---

## D. 落地建议（面向本项目的具体方案）

### D11. 「每个任务的费用」怎么算

#### D11.1 推荐计算公式

对**一个任务**（Task = 多轮 / 多节点 / 多模型调用的集合），按**每次 LLM 调用**计算后累加：

```
task_cost = Σ_{call ∈ task} call_cost(call)

call_cost = ( cache_hit_tokens  × P_input_cache_hit (t)
            + cache_miss_tokens × P_input_cache_miss(t)
            + completion_tokens × P_output           (t)
            ) / 1_000_000

其中：
  cache_hit_tokens   = usage.prompt_cache_hit_tokens
                     （等价于 usage.prompt_tokens_details.cached_tokens）
  cache_miss_tokens  = usage.prompt_cache_miss_tokens
                     （等价于 usage.prompt_tokens - usage.prompt_cache_hit_tokens）
  completion_tokens  = usage.completion_tokens        ← 已含 reasoning_tokens，不再另加
  t                  = 该次调用的请求时刻（用于选空闲/高峰价）
  P_*(t)             = 该模型在时段 t 的单价（元/百万 tokens），查价格表得到
```

**必须遵守的 5 条规则：**

1. **三段式，不能合并**：缓存命中 / 未命中 / 输出是三个不同单价，`prompt_tokens` 单独乘一个价会严重失真（命中价是未命中的 1/30 ~ 1/50）。
2. **思维链 token 不重复计**：`reasoning_tokens ⊆ completion_tokens`（见 §A1），只按输出价算一次。`reasoning_tokens` 仅用作分析指标（如"思考开销占比"）。
3. **按时段取价**：用请求时刻判定该次调用走空闲价还是高峰价（高峰 = 北京时间工作日 9:00–12:00、14:00–18:00）。
4. **计价币种与单价表一致**：官方中文页是元、英文页是美元，二者不是精确汇率换算（见 §D13）。国内充值余额是 CNY，**建议统一用人民币表记账**。
5. **按响应 `model` 取价**：请求 `deepseek-chat` 时响应 `model` 是 `deepseek-flash`（§0.1），用响应模型名查价格表才不会算错。

#### D11.2 精度与舍入

- **单位换算**：单价是「元 / 百万 tokens」，务必用 **`BigDecimal`**，`divide(BigDecimal.valueOf(1_000_000), 8, RoundingMode.HALF_UP)` 保留 8 位中间精度（单次调用金额极小，用 `double` 累加会有可见误差）。
- **累加**：每次调用的 cost 以 `BigDecimal`（scale 8）落库，任务汇总时同 scale 相加。
- **展示**：任务汇总金额最终 `setScale(4, HALF_UP)` 或展示到「分」（scale 2, HALF_UP）。**不要**在中途做 scale 2 的舍入，否则多轮累加会产生较大偏差。
- **token 数**：整数 `long`，注意 `usage` 里的字段在 Java 包装类中可能是 `Integer`，**判空**（`completion_tokens_details` 整个对象在非思考模式下不存在！）。
- **汇率/币种**：不涉及汇率换算（见 §D13）；若余额是 USD 而你想展示 CNY，**不要用固定汇率反推费用**，应直接采用官方人民币价目表。

#### D11.3 Java 实现骨架

**(1) `ChatModelListener` 里采集完整用量**（扩展现有的 `AiModelMonitorListener`）

```java
// 现有代码取的是 TokenUsage，只能拿到 input/output/total。
// 实际运行时对象是 OpenAiTokenUsage，可向下转型拿到缓存与思维链明细。
TokenUsage tokenUsage = responseContext.chatResponse().metadata().tokenUsage();
if (tokenUsage instanceof OpenAiTokenUsage u) {          // dev.langchain4j.model.openai.OpenAiTokenUsage
    long prompt  = nz(u.inputTokenCount());
    long out     = nz(u.outputTokenCount());
    long hit     = (u.inputTokensDetails()  != null) ? nz(u.inputTokensDetails().cachedTokens()) : 0L;
    long reason  = (u.outputTokensDetails() != null) ? nz(u.outputTokensDetails().reasoningTokens()) : 0L;
    long miss    = Math.max(0L, prompt - hit);           // 未命中 = 总输入 - 命中

    String servedModel = responseContext.chatResponse().modelName();  // ← 实际服务模型（deepseek-flash）
    // ... 落库：taskId, servedModel, prompt, out, hit, miss, reason, startTime, endTime
}
private static long nz(Integer v) { return v == null ? 0L : v.longValue(); }
```

> **为什么能转型成功**：`OpenAiChatModel`/`OpenAiStreamingChatModel` 构造 metadata 时用的是 `OpenAiChatResponseMetadata`，其 `tokenUsage()` 返回 `OpenAiTokenUsage`。
> **为什么只拿 `cachedTokens` 就够**：LangChain4j 的 `Usage` 类只解析 `prompt_tokens_details.cached_tokens` 与 `completion_tokens_details.reasoning_tokens`，**不解析** DeepSeek 专有的 `prompt_cache_hit_tokens`/`prompt_cache_miss_tokens`。但实测两者数值完全相等（§A4），所以 `miss = prompt - cached` 完全等价。
> 若确实需要原始字段（例如将来字段语义变化），可用 `((OpenAiChatResponseMetadata) metadata).rawHttpResponse()` 取原始 JSON 自行解析。

**(2) 峰谷判定（北京时间）**

```java
private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

/** 高峰：周一至周五 09:00-12:00 与 14:00-18:00（官方中文定价页脚注 3） */
static boolean isPeak(Instant requestTime) {
    ZonedDateTime bj = requestTime.atZone(CN);
    DayOfWeek d = bj.getDayOfWeek();
    if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) return false;
    LocalTime t = bj.toLocalTime();
    return (!t.isBefore(LocalTime.of(9, 0))  && t.isBefore(LocalTime.of(12, 0)))
        || (!t.isBefore(LocalTime.of(14, 0)) && t.isBefore(LocalTime.of(18, 0)));
}
```

> 注意：**判定用的时刻官方未定义**（跨边界情形），建议用请求发起时刻并记录，见 §B6。

**(3) 价格表实体（务必带生效时间与版本）**

```java
/** 单价单位：元 / 百万 tokens；金额展示单位：元 */
public record ModelPrice(
        String   model,            // 用"响应 model"，如 deepseek-flash / deepseek-v4-pro
        int      priceVersion,     // 价格版本号，落库到每条用量记录
        Instant  effectiveFrom,    // 官方生效时间（如 2026-09-10T04:00:00Z）
        BigDecimal hitOffPeak,     // 0.02
        BigDecimal hitPeak,        // 0.04
        BigDecimal missOffPeak,    // 1
        BigDecimal missPeak,       // 2
        BigDecimal outOffPeak,     // 4
        BigDecimal outPeak         // 8
) { }
```

**(4) 单次调用计费**

```java
private static final BigDecimal MILLION = new BigDecimal("1000000");

BigDecimal costOf(ModelPrice p, Instant t,
                  long hit, long miss, long out) {
    BigDecimal hitPrice  = isPeak(t) ? p.hitPeak()  : p.hitOffPeak();
    BigDecimal missPrice = isPeak(t) ? p.missPeak() : p.missOffPeak();
    BigDecimal outPrice  = isPeak(t) ? p.outPeak()  : p.outOffPeak();

    return BigDecimal.valueOf(hit) .multiply(hitPrice)
         .add(BigDecimal.valueOf(miss).multiply(missPrice))
         .add(BigDecimal.valueOf(out) .multiply(outPrice))
         .divide(MILLION, 8, RoundingMode.HALF_UP);   // 中间精度 8 位
}
```

**(5) 多轮 / 多节点的累加归集**

- `ChatModelListener` 是**全局**的，`OpenAiChatModel` / `OpenAiStreamingChatModel` 的每次调用（含工具调用循环的每一轮、LangGraph4j 每个节点）**都会触发 `onResponse`**，天然覆盖"一个任务多次调用"。
- 归集键建议：在 `MonitorContext` 里加 `taskId`（现有代码已有 `userId` / `appId`，在 `onRequest` 时从 `MonitorContextHolder` 快照进 `attributes`，`onResponse` 从 `attributes` 取——**流式场景 onResponse 与 onRequest 可能不在同一线程，务必沿用 `attributes` 传值，不要依赖 ThreadLocal**，这一点你们现有实现已经处理对了）。
- **落库建议两张表**：
  - `ai_usage_call`（每次调用一行）：`task_id, user_id, app_id, request_model, served_model, price_version, prompt_tokens, completion_tokens, cache_hit_tokens, cache_miss_tokens, reasoning_tokens, is_peak, cost_cny(decimal(18,8)), started_at, finished_at`
  - `ai_usage_task`（任务一行）：`task_id, total_*_tokens, total_cost_cny(decimal(18,6)), call_count`
  - 明细表是**对账与追溯的唯一依据**（因为 DeepSeek 不提供按任务/按 key 的用量 API，见 §C9）。
- **不要**用 Micrometer 计数器当账本（现有 `AiModelMetricsCollector` 是监控指标，适合看趋势、不适合做精确账目）。
- **注意混入的非 DeepSeek 模型**：项目的 `routing-chat-model` 用的是 DashScope `qwen-turbo`。这些调用也会进 listener，但**不能用 DeepSeek 价格**计费。按 `servedModel` 查价格表，查不到就记 `cost = null` 并打标（或单独维护 DashScope 价目表）。
- **失败/重试**：`max-retries: 3` 情况下，失败的重试通常不产生 token（4xx/5xx），但成功的重试要各自记账；`onError` 里不要记账金额，只记事件。
- **缓存命中率监控**：把 `cache_hit_tokens / prompt_tokens` 作为指标（Grafana 已有）非常值得加——它是这个平台最大的成本杠杆。

#### D11.4 LangChain4j 版本相关结论（已在本项目依赖中核实）

本机 `.m2` 中 `langchain4j-open-ai:1.18.0`（由 starter `1.18.0-beta28` 引入）源码核实：

| 事实 | 位置 |
|---|---|
| `OpenAiTokenUsage extends TokenUsage`，含 `inputTokensDetails().cachedTokens()` 与 `outputTokensDetails().reasoningTokens()` | `OpenAiTokenUsage.java` |
| 解析映射：`prompt_tokens_details.cached_tokens` → `InputTokensDetails.cachedTokens`；`completion_tokens_details.reasoning_tokens` → `OutputTokensDetails.reasoningTokens` | `OpenAiUtils.tokenUsageFrom()` |
| **不解析** `prompt_cache_hit_tokens` / `prompt_cache_miss_tokens` | 同上（`Usage` 类仅有 5 个字段） |
| 流式**自动**带上 `stream_options.include_usage(true)` | `OpenAiStreamingChatModel.java:161` |
| 流式 chunk 的 usage 被聚合成 `ChatResponse.metadata().tokenUsage()` | `OpenAiStreamingResponseBuilder.java:123-125, 223-225, 298-315` |
| metadata 的 `modelName` 取自**响应**的 `model` 字段（非流式/流式皆是） | `OpenAiChatModel.java:177` `.modelName(openAiResponse.model())`；`OpenAiStreamingResponseBuilder.java:114` `this.model.set(partialResponse.model())` |
| 原始报文可取：`((OpenAiChatResponseMetadata) meta).rawHttpResponse()` / `.rawServerSentEvents()` | `OpenAiChatResponseMetadata` |
| 基础类 `ChatResponseMetadata`（1.18.1 core）只有 `id / modelName / tokenUsage / finishReason`，**无 attributes map** | `ChatResponseMetadata` javap |
| LangChain4j **无内置成本计算**（全部 jar 中无 cost 相关类） | 已全量扫描 `.m2` |

### D12. 现成开源方案（及其可靠性评估）

| 方案 | 覆盖 DeepSeek | 缓存价 | 峰谷价 | 数据新鲜度（2026-09-10 核查） | 结论 |
|---|---|---|---|---|---|
| **[litellm](https://github.com/BerriAI/litellm)** `model_prices_and_context_window.json` | ✅ 214 个 deepseek 相关 key | ✅ `cache_read_input_token_cost` | ✅ 支持 `off_peak_pricing`（`hours_utc` / `windows` / `weekday_timezone`） | ❌ **已过期**：`deepseek-v4-flash` 仍为 $0.44/$1.32/cache $0.014（2026-08-16 之前的价），且**没有 `deepseek-flash` 这个 key** | 价格数据**不可直接依赖**；其 `off_peak_pricing` 的**建模方式值得借鉴** |
| **[pydantic/genai-prices](https://github.com/pydantic/genai-prices)** | ✅ `prices/providers/deepseek.yml`，7 个模型 | ✅ `cache_read_mtok` | ✅ 用 `constraint{start_date / start_time / end_time}` 表达峰谷与历史价，**最贴合 DeepSeek 语义** | ⚠️ `prices_checked: 2026-08-22`（V4 时代），无 `deepseek-flash`，`deepseek-chat/reasoner` 条目仍在 | **数据结构最值得参考**（含 usage→成本的 extractor 映射：`prompt_tokens`→input、`prompt_tokens_details.cached_tokens`→cache_read、`completion_tokens_details.reasoning_tokens`→reasoning） |
| **[AgentOps-AI/tokencost](https://github.com/AgentOps-AI/tokencost)** | ⚠️ 仅 `deepseek-chat` / `deepseek-coder` | ❌ 无缓存价 | ❌ 无 | ❌ 极旧（`deepseek-chat` 仍为 $0.14/$0.28，是 2024 年的价位） | **不建议使用** |
| **LangChain4j** | — | — | — | — | 无成本模块，需自建 |

**litellm 的过期问题有官方 issue 佐证**（可直接引用）：
- [BerriAI/litellm#37255 "DeepSeek V4 Pro and Flash prices are stale — the flat rate no longer matches the provider at any hour"](https://github.com/BerriAI/litellm/issues/37255)（**2026-08-18 创建，state: open**）：
  > *"DeepSeek moved to time-of-day pricing on 2026-08-16 16:00 UTC and raised both bands. `model_prices_and_context_window.json` still carries the previous flat rate, so today it matches the provider at **no hour of the day**…"*
  > *"Picking the replacement value is the part #31606 exists for: with a single scalar, off-peak understates cost during the 7 peak hours and peak overstates it during the other 17."*
- [BerriAI/litellm#31606 "Support time-based / peak-offpeak pricing for model cost calculation"](https://github.com/BerriAI/litellm/issues/31606)（峰谷建模的原始 feature request）

**litellm 的 `off_peak_pricing` 配置方式**（[文档](https://docs.litellm.ai/docs/proxy/off_peak_pricing)）——注意文档示例里的 `hours_utc: "16:30-00:30"` 是 **2025 年的旧时段，已过期**，如果照抄会算错。按**当前**官方规则应表达为（`weekday_timezone: Asia/Shanghai` 只影响"算星期几"，小时永远是 UTC）：

```yaml
# 仅供参考的建模思路（若要迁移到 Java 侧自建，可照此结构设计）
off_peak_pricing:
  windows:
    # 工作日：高峰是 01:00-04:00 与 06:00-10:00 UTC，故空闲为其补集
    - hours_utc: ["00:00-01:00", "04:00-06:00", "10:00-00:00"]
      weekdays: [mon, tue, wed, thu, fri]
    # 周末：全天空闲
    - hours_utc: "00:00-00:00"     # start == end 表示整天
      weekdays: [sat, sun]
  input_cost_per_token:  ...        # 空闲单价
  output_cost_per_token: ...
  cache_read_input_token_cost: ...
```
litellm 还支持 `output_cost_per_reasoning_token`，但**对 DeepSeek 不要设置它**（思维链已含在输出 token 内，重复计费会翻倍）。

**推荐做法**：
1. **不要在运行时依赖任何第三方价格表**——三次官方调价都在 5 个月内发生，第三方同步明显滞后。
2. 自建一张**带 `effective_from` 的价格表**（数据来源：官方定价页，人工或半自动维护，变更时新增版本而不是覆盖）。
3. 需要历史对账时，参考 **genai-prices 的 YAML 结构**（`constraint: {start_date|start_time/end_time}`）来组织多版本价格。
4. 若服务端也用 litellm 做代理，用它的 `off_peak_pricing` 配置并按上表校准；但仍建议业务侧独立记账（litellm 的 spend 是代理侧口径）。

### D13. 汇率 / 币种

- **DeepSeek 同时发布人民币价目表与美元价目表**（中文页 vs 英文页），**不是**同一张表按汇率换算出来的：
  - 人民币：`deepseek-flash` 输出空闲 **4 元**/百万；美元页同项 **$0.60**/百万。4 / 0.6 ≈ **6.67**。
  - 缓存命中：0.02 元 vs $0.003 → ≈ **6.67**；未命中 1 元 vs $0.15 → ≈ **6.67**。
  - `deepseek-v4-pro`：0.15 元 vs $0.022 → ≈ 6.82；4.5 元 vs $0.66 → ≈ 6.82；13.5 元 vs $1.98 → ≈ 6.82。
  - → 两套表各自独立取整，**不存在可依赖的固定汇率**（在 6.67 ~ 6.82 之间浮动）。
- **余额 API 明确返回币种**（`currency: "CNY" | "USD"`），实测本项目账号为 **CNY**（`"total_balance":"20.65"`）。
- **建议**：
  1. **以人民币表为准记账**（国内账号充值/扣费都是 CNY，余额 API 也返回 CNY）。
  2. 费用字段直接命名 `cost_cny`，**不要**设"币种可切换"的模糊字段。
  3. 若账号是 USD 计价（境外账号），用英文页的美元表并记 `cost_usd`，**不要**用汇率把人民币价换算过去。
  4. 展示给用户时可用 `¥` 符号；**金额一律 `BigDecimal` 字符串存取**。

---

## 附录 A：实测原始记录（2026-09-10）

环境：本机通过 HTTPS 代理（DNS fake-IP，`198.18.0.6`）直连 `api.deepseek.com`；Python `urllib` 因代理自签证书失败，改用 `curl`。

| # | 探针 | 结果 |
|---|---|---|
| 1 | `POST /chat/completions`，`model=deepseek-chat`，`max_tokens=1` | 200，`model` 被改写为 `deepseek-flash`，非思考模式（无 `reasoning_content`），usage 无 `completion_tokens_details` |
| 2 | 同上，`model=deepseek-reasoner` | 200，`model=deepseek-flash`，思考模式（有 `reasoning_content`），`completion_tokens_details.reasoning_tokens=1` |
| 3 | `model=deepseek-v3`（不存在） | **400** `"The supported API model names are deepseek-flash, deepseek-v4-pro, but you passed deepseek-v3."` |
| 4 | `stream=true` 不带 `stream_options` | usage 出现在最后一个带 `finish_reason` 的 chunk，随后 `data: [DONE]` |
| 5 | `stream=true` + `stream_options.include_usage=true` | 与 #4 完全一致（无独立 usage chunk，无差异） |
| 6 | `GET /models` | 仅 `deepseek-flash`、`deepseek-v4-pro` |
| 7 | `GET /user/balance` | `{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"20.65",...}]}` |
| 8 | `GET /user/balance` + 无效 Key | **401** `{"error":{"message":"Authentication Fails, ...","type":"authentication_error",...}}` |
| 9 | 缓存测试：同 1295-token 前缀连发 3 次（间隔 6s） | hit: 0 → 1152 → 1152；miss: 1295 → 143 → 143；`cached_tokens == prompt_cache_hit_tokens` |

## 附录 B：来源 URL 汇总

**官方文档 / 定价**
- 定价页（英文·美元）：https://api-docs.deepseek.com/quick_start/pricing
- 定价页（中文·人民币）：https://api-docs.deepseek.com/zh-cn/quick_start/pricing
- Chat Completions API（usage / stream_options 规范）：https://api-docs.deepseek.com/api/create-chat-completion
- 余额查询 API：https://api-docs.deepseek.com/api/get-user-balance
- 模型列表 API：https://api-docs.deepseek.com/api/list-models
- 上下文缓存：https://api-docs.deepseek.com/guides/kv_cache
- 思考模式：https://api-docs.deepseek.com/guides/thinking_mode
- 工具调用：https://api-docs.deepseek.com/guides/tool_calls
- 限速与隔离（`user_id`）：https://api-docs.deepseek.com/quick_start/rate_limit
- Token 与用量说明：https://api-docs.deepseek.com/quick_start/token_usage
- Responses API：https://api-docs.deepseek.com/guides/responses_api ／ https://api-docs.deepseek.com/api/create-response
- 错误码：https://api-docs.deepseek.com/quick_start/error_codes
- 变更日志：https://api-docs.deepseek.com/updates
- API 概览（Bearer 鉴权）：https://api-docs.deepseek.com/api/deepseek-api

**官方公告**
- V4.1-Flash 发布 + 降价（2026-09-10）：https://api-docs.deepseek.com/news/news260910
- V4-Pro GA + 引入峰谷价（2026-08-13）：https://api-docs.deepseek.com/news/news260813
- V4 发布 + 旧模型名退役预告（2026-04-24）：https://api-docs.deepseek.com/news/news260424
- V3.2-Exp 降价 50%+（2025-09-29）：https://api-docs.deepseek.com/news/news250929
- 硬盘缓存（2024-08-02）：https://api-docs.deepseek.com/news/news0802

**历史价格快照（Wayback，用于旧模型对账）**
- 2025-10-01（V3.2-Exp，deepseek-chat/reasoner 0.2/2/3 元）：https://web.archive.org/web/20251001000000/https://api-docs.deepseek.com/zh-cn/quick_start/pricing
- 2026-02-01（V3.2）：https://web.archive.org/web/20260201000000/https://api-docs.deepseek.com/zh-cn/quick_start/pricing
- 2026-06-01（V4 首发热价 + 2.5 折活动）：https://web.archive.org/web/20260601000000/https://api-docs.deepseek.com/zh-cn/quick_start/pricing
- 2026-07-01（退役脚注）：https://web.archive.org/web/20260701000000/https://api-docs.deepseek.com/zh-cn/quick_start/pricing

**开源生态**
- litellm 价格表：https://github.com/BerriAI/litellm/blob/main/model_prices_and_context_window.json
- litellm 峰谷定价文档：https://docs.litellm.ai/docs/proxy/off_peak_pricing
- litellm issue #37255（价格过期，open）：https://github.com/BerriAI/litellm/issues/37255
- litellm issue #31606（峰谷建模 feature request）：https://github.com/BerriAI/litellm/issues/31606
- pydantic/genai-prices：https://github.com/pydantic/genai-prices ／ DeepSeek 数据：https://github.com/pydantic/genai-prices/blob/main/prices/providers/deepseek.yml ／ issue #582：https://github.com/pydantic/genai-prices/issues/582
- AgentOps tokencost：https://github.com/AgentOps-AI/tokencost
- 平台用量私有接口的社区实践：https://github.com/AzureHalcyon/dsh-deepseek-usage ／ https://github.com/steipete/CodexBar/blob/main/docs/deepseek.md

## 附录 C：明确「未找到官方说明」的条目

调研中确实**没有**在官方渠道找到以下内容，实现时需自行定义策略并在代码里注明假设：

1. **思维链 token 是否单独计费/单独定价**——官方无明文。证据链表明其**包含在 `completion_tokens` 内按输出价计费**（§A1），但严格来说是推断而非官方声明。
2. **一次请求跨越峰谷边界时按哪个时刻判定**——官方无明文。litellm 采用「完成时刻」，非官方规则。
3. **缓存命中/未命中的计费在账单上如何分列**——只能从价目表两行不同单价推断。
4. **历史用量/账单的公开 API**——不存在（不是"没找到"，是确认没有；官方 sitemap 与文档均无）。
5. **官方是否保证旧模型名 `deepseek-chat`/`deepseek-reasoner` 继续可用**——文档说已退役，实测仍可用，官方无任何 SLA 承诺。
6. **价格变更的提前通知机制**——官方只说"保留调整权利，请定期查看该页"，无 API、无订阅、无 webhook。
7. **`usage` 字段缺失时的行为**（如请求被限流、超长被截断等边界）——未逐一验证；建议 Java 侧对 `usage == null`、`tokenUsage == null` 全部按 0 处理并记录告警。
