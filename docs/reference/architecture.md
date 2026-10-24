# 架构文档：代码生成链路与沙箱生命周期

> 本文档介绍「AI 零代码应用生成平台」从用户对话到代码生成、运行、浏览、下载的全链路架构，重点说明 AI 在整个过程中的行动逻辑，以及代码沙箱围绕链路各阶段的容器生命周期规划。

---

## 1. 总体架构图

```
┌───────────┐   SSE(流式)   ┌──────────────────────────────────────────────┐
│  前端      │ ──────────▶  │              主服务 (Spring Boot)             │
│ Vue3+AntD │ ◀──────────  │  ┌────────────┐ ┌────────────┐ ┌────────────┐  │
└───────────┘              │  │ 任务队列    │ │ AI 编排     │ │ 工具系统    │  │
     │                     │  │ GenTaskExec │ │ AiCodeGen   │ │ Tools      │  │
     │   HTTP               │  │ or          │ │ Facade      │ │ (write/read│  │
     ▼                     │  └────────────┘ └────────────┘ │  /build...)│  │
┌───────────┐              │                                └─────┬──────┘  │
│ 浏览/下载/ │◀── HTTP ────│                                      │         │
│ 部署/预览  │              │                              sandbox HTTP      │
└───────────┘              │                                      │         │
                           └──────────────────────────────────────┼─────────┘
                                                                  ▼
                                                    ┌───────────────────────────┐
                                                    │ 代码沙箱容器 (sidecar)      │
                                                    │  - Node 文件服务 :8080      │
                                                    │  - 后端 Spring Boot :8081   │
                                                    │  - /workspace = 卷挂载      │
                                                    └───────────────────────────┘
                                                                  │
                                                            host.docker.internal
                                                                  ▼
                                                       宿主机 MySQL(app_{id}) / Redis(db{id%16})
```

**核心组件：**

| 组件 | 职责 |
|---|---|
| 前端 (Vue3) | 用户发起对话、消费 SSE 流式展示（正文/思考块/工具块）、触发预览/部署/下载 |
| 主服务 (Spring Boot) | 任务排队、AI 编排、工具执行、代码落盘、SSE 推送、预览/部署/下载接口 |
| 工具系统 | AI 可调用的原子能力：写文件/读文件/改文件/删文件/读目录/构建项目 |
| AI 编排 (Facade) | 按 codeGenType 装配 AI 服务（模型 + 工具 + 记忆 + 护轨）并驱动流式生成 |
| 代码沙箱 (Docker) | 隔离的容器环境，承载文件操作、构建、后端运行，连隔离中间件 |
| 宿主机中间件 | MySQL（每 appId 独立库）、Redis（每 appId 独立 db） |

---

## 2. 端到端链路：从对话到可浏览、可下载

### 2.1 发起：用户对话

前端用户在输入框描述需求（如「做一个带登录的博客系统」），点击发送：

1. 前端调用 `POST /gen-task/create`，body 为 `{ appId?, message, codeGenType? }`。
   - `appId` 为空时代表**首次对话**，后端会自动创建应用。
   - `codeGenType` 为用户手动选择（自动/前端/全栈），可空，用于**覆盖** AI 路由结果。
2. 前端收到任务对象 `GenTaskVO`（含 `taskId/appId/status/codeGenType`）：
   - 若后端返回了新的 `appId`（首次对话创建了应用），前端更新路由为 `/app/chat/{appId}` 并拉取应用信息。
   - 若任务为 `PENDING`，显示排队位置。
3. 前端建立 SSE 连接 `GET /gen-task/stream/{taskId}`（支持断线重连）。

### 2.2 任务创建与排队（主服务侧）

`GenTaskController.createTask` → `GenTaskServiceImpl.createTask`：

1. **创建/复取应用**：
   - `appId` 为空 → `AppService.createApp()`。内部用 **AI 智能路由**（`AiCodeGenTypeRoutingService`）根据用户提示词返回四种类型之一：`HTML / MULTI_FILE / VUE_PROJECT / FULLSTACK`。
   - 若用户手动传了 `codeGenType` 且合法，则**覆盖** AI 路由结果，更新 `app.codeGenType`。
   - `appId` 非空 → 校验归属权限后复用。
2. 检查该用户该 app 是否已有**活跃任务**，有则直接复用返回。
3. 创建 `GenTask`（`PENDING` 状态，携带 `codeGenType` 快照）入库。

`GenTaskExecutor`（任务队列执行器）：
- 启动时有 `Sinks.Many<GenTask>` 任务流 + 调度线程池。
- 每 `poll-interval`(2s) 轮询一个 `PENDING` 任务，推入任务流。
- 任务流用 `flatMap(..., maxConcurrentTasks=3)` **控制并发**（同时最多执行 3 个任务）。
- 另有两个定时任务：每分钟**清理超时任务**、每 5 秒**刷新队列位置**（心跳推给前端）。

### 2.3 任务执行：AI 开始行动（关键）

`GenTaskExecutor.executeTask` 拿到任务，核心流程：

1. 标记任务 `RUNNING`，通过 SSE 推送 `status=running`。
2. 加载 `App` / `User`，解析 `codeGenType`。
3. 保存用户消息到对话历史。
4. 调用 `AiCodeGeneratorFacade.generateAndSaveCodeStream(message, codeGenType, appId)` 获取流式 `Flux<String>`。
5. 用 `StreamHandlerExecutor.doExecute` 处理流（按类型选处理器）。
6. 对**瞬时上游错误**（`overloaded`/`429`/`503` 等）做指数退避重试（`max-retries=3`）。
7. `doOnNext` 按消息类型分发到不同 SSE 命名事件；`doOnComplete` 标记完成、推送 `done`；`doOnError` 标记失败并推送 `error`。

**AI 服务装配（`AiCodeGeneratorServiceFactory.createAiCodeGeneratorService`）**：
取决于 `codeGenType`（决定 AI 拿到的工具、模型、记忆）：

| 类型 | 模型 | 工具 | 记忆 | 护轨 |
|---|---|---|---|---|
| `HTML` / `MULTI_FILE` | 流式对话模型 | 无 | 共享记忆 | 输入护轨 |
| `VUE_PROJECT` / `FULLSTACK` | **推理模型**(reasoning) + 全量工具 | `toolManager.getAllTools()` | 独立记忆(id=appId) | 输入护轨 + 工具幻觉兜底 + `maxSequentialToolsInvocations=20` |

### 2.4 AI 如何行动：工具驱动的代码生成

对于 `VUE_PROJECT` / `FULLSTACK`，AI 通过**工具调用**逐文件生成代码，整个过程通过 SSE 实时反馈给前端：

1. **思考**：AI 先输出推理内容（`onPartialThinking` → `thinking` 事件），前端折叠展示。
2. **计划说明**：AI 输出简短生成计划（正文增量，`ai_response` 事件）。
3. **工具调用**（循环直到 `exit` 或达到 20 次上限）：
   - AI 发起工具请求（如 `writeFile(backend/UserController.java, ...)`）。
   - `onToolExecuted` 触发时，主服务先发一个 `tool_request` 事件（只含工具名+参数摘要，避免把完整文件内容推给前端），再发 `tool_executed` 事件（含结果）。
   - 工具本体内**实际写盘**（走 `SandboxClient.writeFile`，见 §4）。
   - 工具执行结果作为 **AI 的上下文**回传给模型（用于下次决策/自我修正）。
4. **里程碑**：关键节点（如「后端编译通过」「前端构建成功」）通过正文醒目标出。
5. **构建验证**：AI 在文件生成完毕后调用 `buildProject`（`scope=backend`→`mvn compile`；`scope=frontend`→`npm run build`），根据失败信息自我修复后重试。
6. **结束**：AI 输出简短的生成完毕提示，调用 `exit` 工具退出循环。

> `maxSequentialToolsInvocations=20` 防止 AI 陷入无限工具循环。

---

## 3. 生成完成后的三个出口

代码全部落在宿主机 `tmp/code_output/{codeGenType}_{appId}/`（通过沙箱卷挂载落盘，见 §4.2）。

### 3.1 浏览 / 预览（全功能）

**纯前端类（HTML/MULTI_FILE/VUE_PROJECT）**：
- 前端 `getStaticPreviewUrl` 生成静态预览 URL，`StaticResourceController` 从 `tmp/code_output/{deployKey}/` 托管 dist/index.html 等静态资源。

**全栈类（FULLSTACK）**：
- 前端调用 `POST /app/preview/{appId}` → `PreviewService.startPreview(appId)`：
  1. `SandboxContainerManager.ensureContainer(appId)` 拉起沙箱容器（§4.3）。
  2. 注入隔离环境变量（`APP_DB=app_{appId}`、`REDIS_DB=appId%16`、`MYSQL_HOST=host.docker.internal` 等）。
  3. 容器内启动后端 `mvn spring-boot:run -Dspring-boot.run.profiles=sandbox -Dspring-boot.run.arguments=--server.port=8081`。
  4. 返回预览 URL `{host}/api/preview/{appId}/`。
- 该 URL 由 `PreviewProxyController` 统一处理：
  - 静态资源从 `frontend/dist` 托管；
  - `/api/**` 反代到容器内后端（`http://localhost:{backendPort}`）。
- **数据隔离**：后端连 `app_{appId}` 库 + Redis `db{appId%16}`，不同 app 数据完全隔离。

### 3.2 部署（可选）

`AppController.deployApp` → `AppServiceImpl.deployApp`：
- **VUE_PROJECT**：`vueProjectBuilder.buildProject` → 拷贝 `dist` 到 `tmp/code_deploy/{deployKey}`。
- **FULLSTACK**：先 `mvn package`（生成 jar，`-DskipTests`）+ 前端 `npm run build`；`frontend/dist` 作为静态托管根，jar 放 `backend/` 子目录。
- 返回 `{deploy-host}/{deployKey}/` 静态访问地址，并异步生成截图。

### 3.3 下载源码

`AppController.downloadAppCode` → `ProjectDownloadServiceImpl.downloadProjectAsZip`：
- 从 `tmp/code_output/{codeGenType}_{appId}/` 打包为 zip。
- `isPathAllowed` 过滤器**排除**：`node_modules/.git/dist/build/target/.env` 等，以及**任何含 `sandbox` 的文件/目录**（确保平台的 `application-sandbox.yml`、`SandboxConfig.java` 等**不进用户包**）。
- 用户下载得到**干净源码**：
  - `application.yml`（用户通用配置，连本地 `localhost`，**无 `${}` 占位符、无隔离痕迹**）。
  - `application-sandbox.yml` 被排除（沙箱专用，仅预览时用）。
  - 前端用相对 `api/xxx` 路径、hash 路由（本地部署即命中用户自己的后端）。
  - README 含建库语句 `CREATE DATABASE IF NOT EXISTS {app}_db ...`。

---

## 4. 代码沙箱与生命周期

### 4.1 沙箱镜像（sidecar）

统一镜像 `code-sandbox:latest`（`sandbox/Dockerfile`）：
- 基础：`maven:3.9.9-eclipse-temurin-21`（JDK21 + Maven），安装 Node18 + npm。
- 预缓存 Maven 依赖（`sandbox/maven-repo/scaffold-pom.xml`，`dependency:go-offline`），加速全栈后端构建。
- 内嵌 Node HTTP 服务 `app.js`：
  - `POST /api/files/write|read|modify|delete|list`：读写改删列文件，路径严格限制在 `/workspace` 内（防路径穿越）。
  - `POST /api/exec`：执行命令（`npm/mvn/java/node/...` 白名单前缀），带超时强杀、输出截断。
  - 命令白名单、路径归一化等安全约束（demo 级）。
- 容器暴露 `8080`（node 文件服务）。

### 4.2 数据流：宿主 ↔ 容器（关键约定）

沙箱容器启动时用 **卷挂载** `-v tmp/code_output/fullstack_{appId}:/workspace`：
- 容器内写 `/workspace/xxx` **即落盘到宿主机** `tmp/code_output/fullstack_{appId}/xxx`，是**同一份文件**。
- 因此：沙箱开启（HTTP 写 /workspace→卷落盘）与关闭（宿主直写）**下载/预览/部署读到的都是同一份宿主机文件**。
- 容器内 `mvn` 产生的 `target/`、`npm` 的 `node_modules/` 也在卷里，下载过滤器已排除。
- **结论**：隔离只存在于沙箱运行时层，用户拿到的源码文件与沙箱内的完全一致且干净。

### 4.3 容器生命周期

**创建/复用**（`SandboxContainerManager.ensureContainer(appId)`）：
```
docker run -d --name code-sandbox-{appId}
  -p {18080+appId%1000}:8080     # node 文件服务
  -p {19080+appId%1000}:8081     # 后端 Spring Boot
  --add-host=host.docker.internal:host-gateway
  -v tmp/code_output/fullstack_{appId}:/workspace
  --memory 2g --cpus 2
  code-sandbox:latest
```
- 容器已存在则复用；不存在则创建。
- 同时调用 `createIsolatedDatabase(appId)`：连宿主机 MySQL 执行 `CREATE DATABASE IF NOT EXISTS app_{appId}`。
- 每 appId 独立容器、独立端口、独立库、独立 Redis db ⇒ **强隔离**。

**预览期间**（`PreviewService`）：
- 在容器内 `docker exec -e APP_DB=... -e REDIS_DB=... ... code-sandbox-{appId} mvn spring-boot:run ...` 启动后端（激活 `application-sandbox.yml`，注入隔离环境变量）。
- `PreviewProxyController` 把页面 `/api/**` 反代到容器内后端。

**停止/清理**（`stopPreview` / `stopPreviewAndContainer`）：
- 结束后端进程（`previewProcessMap` 中记录的 `Process` 强制终止）。
- 可选停止并删除容器。

**销毁/超时兜底**：
- 任务执行有 `timeout-minutes`；`GenTaskExecutor` 每分钟清理超时任务。
- 应用关闭时 `@PreDestroy` 关闭 Docker 客户端、停止相关容器。

### 4.4 沙箱生命周期与链路阶段的对应

| 链路阶段 | 沙箱状态 | 服务器动作 |
|---|---|---|
| 用户发起对话 | 未起容器 | 仅任务排队，不涉及沙箱 |
| AI 生成代码（写文件） | **容器可能已起**（若 sandbox.enabled=true） | 文件工具经 `SandboxClient.writeFile` 走 HTTP 写 `/workspace`（卷落盘宿主） |
| AI 构建验证 | 容器运行中 | `buildProject` 经 `sandbox.exec` 在容器内跑 `mvn`/`npm` |
| 预览 | 容器运行中 | `ensureContainer` + `docker exec` 启动后端 + 注入隔离 env |
| 部署（可选） | 宿主侧构建（或容器内） | `mvn package` / `npm build`，拷贝到部署目录 |
| 下载源码 | 容器可停 | 打包 `tmp/code_output/{type}_{appId}`，过滤沙箱文件 |
| 会话结束/超时 | 停止并删除容器 | `stopPreviewAndContainer` 释放端口与资源 |

---

## 5. 安全与防滥用

- **输入护轨** `PromptSafetyInputGuardrail`：AI 服务装配时注入，拦截危险输入。
- **工具幻觉兜底** `hallucinatedToolNameStrategy`：AI 调用不存在的工具时返回错误而非崩溃。
- `maxSequentialToolsInvocations=20`：限制连续工具调用次数。
- **瞬时错误重试**：指数退避（`overloaded/429/503` 等），`max-retries=3`。
- **沙箱隔离**：路径白名单、命令前缀白名单、内存/CPU 限制、超时强杀、每 appId 独立容器。
- **Redis 隔离**：`appId % 16` 分配 db（demo 级上限 16 并发预览）。
- **MySQL 隔离**：每 appId 独立库 `app_{appId}`。

---

## 6. 关键设计取舍

| 决策点 | 结论 | 原因 |
|---|---|---|
| 生成模式 | 新增 `FULLSTACK` 枚举 | 区分纯前端与全栈 |
| 前端/全栈路由 | AI 自动识别 + 前端手动兜底 | 复用路由服务，用户体验好 |
| AI 行动方式 | 工具驱动逐文件生成 | 贴合现有 BaseTool 工具系统，可控、可流式反馈 |
| 代码落盘 | 沙箱卷挂载 = 宿主机文件 | 下载/预览/部署读同一份文件，简单一致 |
| 配置隔离 | 双 profile（application.yml 通用 / application-sandbox.yml 隔离） | 用户源码干净零隔离痕迹 |
| 容器管理 | `docker` CLI（进程）而非 docker-java | 避免版本类路径问题，跨平台稳定 |
| 预览 | 全功能：沙箱内真跑后端连隔离库 | 用户看到真实可用的全栈应用 |
