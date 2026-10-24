# 文档索引

> 项目：AI 零代码应用生成平台（`yu-ai-code-mother`）
> 整理日期：2026-10-20
> 说明：根目录的 `README.md` 是项目教程说明，不属于本目录。

---

## 一、按目的找文档

| 你想做什么 | 看哪里 |
|---|---|
| 改「生成什么、怎么访问」 | [design/](design/) —— 一体化方案 + 改造思路 |
| 改「怎么监管、怎么算钱」 | [research/](research/) —— 任务监管、计费、可观测性调研 |
| 了解「现在系统长什么样」 | [reference/](reference/) —— 旧链路架构、测试边界、配置来源 |
| 知道「方案落地了多少」 | [status/](status/) —— 42 项逐条对照 |
| 改简历项目段 | [resume/](resume/) |

---

## 二、目录结构

### design/ —— 方案

| 文档 | 内容 | 状态 |
|---|---|---|
| [LOWCODE-APP-DESIGN.md](design/LOWCODE-APP-DESIGN.md) | 一体化方案 v1.0：单端口 Node/Express + React、accessCode 短链、独立代理服务、薄数据层授权 | 设计完成，**零实现** |
| [代码生成一体化-改造思路.md](design/代码生成一体化-改造思路.md) | 交接稿：现状基线、5 个改造面、Phase 0 竖切、决策代价、风险清单、新会话开场提示词 | 承接上一份，可直接开工 |

### research/ —— 调研与设计

| 文档 | 内容 | 状态 |
|---|---|---|
| [任务监管端-可行性与设计方案.md](research/任务监管端-可行性与设计方案.md) | 按任务看轨迹 + token + 费用；P0 约 6 人日 | ✅ **现行实施主线** |
| [AI管理端-可行性调研与设计方案.md](research/AI管理端-可行性调研与设计方案.md) | 模型管控 + Langfuse 闭环 | ⚠️ **§2 已作废**，§1/§3/§4 作背景 |
| [Grafana技术栈-AI任务级轨迹监管-技术调研报告.md](research/Grafana技术栈-AI任务级轨迹监管-技术调研报告.md) | Grafana 能否做任务级轨迹 | 结论：不能，MySQL 管理端为主干 |
| [langfuse-接入调研报告.md](research/langfuse-接入调研报告.md) | Langfuse 接入方式与成本 | 结论：`langchain4j-observation` + OTLP，P2 |
| [DeepSeek-用量与费用调研报告.md](research/DeepSeek-用量与费用调研报告.md) | usage 结构、峰谷计价、落库建议 | 结论：三段式计费 + CNY 记账 |

### status/ —— 落地状态

| 文档 | 内容 |
|---|---|
| [交接-访问入口与双模式改造.md](status/交接-访问入口与双模式改造.md) | **现行主线**：P0~P5 改了什么、当前运行状态、剩余 P6、下轮开场提示词 |
| [CP5-访问入口-验收报告.md](status/CP5-访问入口-验收报告.md) | P0~P3：独立 proxy、`app_access_route`、`app-pv-*`、随机端口 |
| [生成模式收敛-两级模式.md](status/生成模式收敛-两级模式.md) | static / fullstack 两种模式的契约 |
| [预览生命周期-容器回收.md](status/预览生命周期-容器回收.md) | 预览容器休眠与唤醒 |
| [P4-工具通道与热更新-验收报告.md](status/P4-工具通道与热更新-验收报告.md) | 工具通道 token、rebuild/restart、preview_reload |
| [P5-部署容器化-验收报告.md](status/P5-部署容器化-验收报告.md) | 部署快照/镜像/版本容器、部署子域名、回滚、生产库拆分 |
| [设计文档-落地对照.md](status/设计文档-落地对照.md) | 早期 42 项对照（部分结论已被 P0~P5 超越，以交接文档为准） |

### reference/ —— 现状与说明

| 文档 | 内容 |
|---|---|
| [architecture.md](reference/architecture.md) | 旧链路：生成 → 沙箱 → 预览 → 部署 → 下载（Vue3 + Spring Boot 版本） |
| [testing.md](reference/testing.md) | 测试边界：用 JSONL 回放工具调用替代真实 LLM |
| [配置生效说明.md](reference/配置生效说明.md) | 三条启动路径（IDE / run-local.sh / docker compose）各自的配置来源 |

### resume/ —— 简历材料

| 文件 | 内容 |
|---|---|
| [简历-项目经历补充建议.md](resume/简历-项目经历补充建议.md) | 项目段诊断、亮点排序、方案 A/B、数字测法、面试追问 |
| [简历-项目经历-改写版.docx](resume/简历-项目经历-改写版.docx) | 可直接粘贴进简历的段落版 |
| [何松-后台开发-简历_副本.docx](resume/何松-后台开发-简历_副本.docx) | 原始简历 |

---

## 三、当前状态摘要

| 方向 | 结论 |
|---|---|
| 代码生成一体化 | **P0~P5 已落地**：预览/部署都走独立 proxy，域名即路由，两种生成模式，部署可回滚 |
| 部署 | 每次部署生成不可变镜像 + 版本容器，`{deployKey}.app.localhost` 固定地址，生产库与预览库拆开 |
| 剩余 | P6：reconcile（重启后端口刷新）、应用删除回收（容器/库/路由/镜像）、删旧预览链路 |
| 任务监管 / token 成本 | 地基已有（`ChatModelListener`、`chat_history.tool_call`、admin 骨架），**账本与页面未做** |
| 全局模型管控 | 需求已取消，不要按 `AI管理端` §2 开工 |
| Langfuse / OTel | 未接入，维持 P2 |
| 已知隐患 | `error_message` 高基数标签、`/admin` 无前端守卫；预览进程内存态与容器不回收已在 P3 修复 |

---

## 四、维护约定

1. **放哪**：新方案 → `design/`；新调研 → `research/`；现状说明 → `reference/`；状态盘点 → `status/`。
2. **文档头**：写清日期、版本、是否现行。被取代的文档在头部加「⚠️ 已作废，以 xxx 为准」，不要直接删。
3. **交叉引用**：统一用仓库根相对路径（`docs/design/xxx.md`），便于在 IDE 与 Git 平台跳转。
4. **表结构类方案**：以 `research/任务监管端` §4 为准；`Grafana`、`DeepSeek`、`AI管理端` 里的表名为早期版本，仅供对照（冲突点见 status 报告 §9）。
