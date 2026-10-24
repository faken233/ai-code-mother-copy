# 核心功能测试用例设计

> 目标：在不依赖真实 LLM 的情况下，模拟 AI 输出，走一遍「用户生成任务 → 沙箱拉起 → 沙箱内写文件 → 打包编译 → 预览 → 下载」全链路。

## 一、分层与可测边界

真实链路中，AI（LLM）输出的 tool call 由 langchain4j 框架解释并驱动 `BaseTool` 执行。要"模拟 AI 输出"但**不 mock 掉被测核心逻辑**，采用以下边界：

| 层 | 测试策略 |
|---|---|
| LLM 网络调用 | 不发起；用直接调用工具/服务替代 |
| langchain4j 框架的 tool 调度 | 不 mock（框架行为），而是**直接驱动**平台能力组件 |
| 沙箱写文件 / 读目录 / 编译 | **真实走沙箱**（docker 可用时）|
| 预览 / 下载 / 部署 | **真实走服务**（docker 可用时）|
| 中间件隔离 | 用沙箱专用 MySQL/Redis 端口真实验证 |

**原则**：AI 的"输出"等价于一组「工具调用序列 + 正文流」。测试里用一个 `SimulatedAiActor` 把这段序列"播放"出来，落到真实的 `SandboxClient` / `BuildProjectTool` / `PreviewService` / 下载服务上。

## 二、用例清单

### TC1 — 沙箱全链路集成测试（docker 必需）
`SandboxFullChainIntegrationTest`

模拟 AI 输出以下动作序列，端到端验证：

1. **拉起沙箱**：`SandboxContainerManager.ensureContainer(appId)` 创建/复用 `code-sandbox-{appId}`，验证容器运行、端口映射（node 18080+ / 后端 19080+）。
2. **沙箱写文件**：模拟 AI 调用 `writeFile(backend/pom.xml)`、`writeFile(backend/src/main/java/.../Main.java)`、`writeFile(frontend/package.json)` 等，验证：
   - 沙箱 `/workspace` 落盘 = 宿主机 `tmp/code_output/fullstack_{appId}`。
   - `readDir` 能看到文件。
3. **后端编译**：模拟 AI 调用 `BuildProjectTool.buildProject(scope=backend)`，验证 `mvn -q compile` 成功（真实沙箱内编译）。
4. **前端构建**：模拟 AI 调用 `BuildProjectTool.buildProject(scope=frontend)`，验证 `npm run build` 成功并生成 `dist`。
5. **预览**：`PreviewService.startPreview(appId)` 拉起后端（sandbox profile + 隔离 env），验证 `PreviewProxyController` 能反代到后端健康接口。
6. **下载**：验证 `ProjectDownloadServiceImpl` 打包 zip，且 `application-sandbox.yml` 等 sandbox 文件被排除。

> 该用例标记 `@Tag("docker")` / 前置检查 docker 可用，CI 无 docker 时跳过。

### TC2 — 下载过滤器单元测试（无需 docker）
`ProjectDownloadServiceTest`

构造一个含 `application-sandbox.yml`、`SandboxConfig.java`、`node_modules/`、`target/`、普通源码的临时目录，验证：
- zip 内**不含**任何 `sandbox` 相关文件。
- zip 内**不含** node_modules / target / dist / .git。
- 普通 `application.yml`、`backend/`、`frontend/` 源码保留。

> 关键：验证"用户下载源码零隔离痕迹"。

### TC3 — BuildProjectTool 单元测试（mock sandboxClient）
`BuildProjectToolTest`

mock `SandboxClient` 与 `ProjectPathResolver`，验证：
- `scope=backend` 路由到 `mvn -q compile`。
- `scope=frontend` 路由到 `npm install` + `npm run build`。
- `scope` 缺省时按目录自动探测。
- 构建失败时返回含 `❌` 的错误信息。

### TC4 — 预览地址与隔离 env 单元测试（mock）
`PreviewServiceTest`

mock `SandboxContainerManager` / `SandboxProperties`，验证：
- `startPreview` 注入 `APP_DB=app_{id}`、`REDIS_DB=0`、`REDIS_KEY_PREFIX=app_{id}:`、`MYSQL_PORT=3307`、`REDIS_PORT=6380`。
- 后端以 `application-sandbox.yml`（`spring-boot.run.profiles=sandbox`）启动，`server.port=8081`。

### TC5 — SandboxClient 路径穿越与命令白名单（docker 必需）
`SandboxSecurityTest`

- `readFile("../../etc/passwd")` 应被拒绝（403）。
- `exec("rm", ...)` 应被拒绝（命令白名单）。
- `exec("node", ["-v"])` 正常返回。

## 三、模拟 AI 输出

用一个内部 `SimulatedAiActor` 类承载"AI 原本要做的动作"：

```java
// 模拟 AI 的一次全栈生成：写文件 → 编译 → 构建 → 预览 → 下载
actor.writeFile("backend/pom.xml", pom);
actor.writeFile("backend/src/main/java/com/example/Application.java", java);
actor.writeFile("frontend/package.json", pkg);
actor.writeFile("frontend/index.html", html);
actor.buildBackend();     // 对应 AI 调用 buildProject(scope=backend)
actor.buildFrontend();    // 对应 AI 调用 buildProject(scope=frontend)
actor.startPreview();     // 对应预览入口
actor.download();         // 对应下载入口
```

它**不是 mock AI**，而是把 AI 的 tool call 翻译成对真实服务的调用，从而让"写文件/编译/预览/下载"走真实链路。

## 四、执行方式

```bash
# 仅编译测试代码
mvn test-compile

# 跑无需 docker 的单元测试（快）
mvn test -Dtest=ProjectDownloadServiceTest,BuildProjectToolTest,PreviewServiceTest

# 跑 docker 全链路（本机已装 docker + 中间件 compose 已起）
mvn test -Dtest=SandboxFullChainIntegrationTest,SandboxSecurityTest
```

## 五、隔离验证要点（对应真实需求）

| 隔离点 | 用例覆盖 |
|---|---|
| 平台 vs 沙箱中间件实例 | TC1 验证沙箱后端连 3307/6380，平台连 3306/6379 |
| 任务间 Redis 前缀 | TC4 验证 `REDIS_KEY_PREFIX=app_{id}:` 注入 |
| 用户下载零隔离痕迹 | TC2 验证 sandbox 文件被过滤 |
| 沙箱路径/命令安全 | TC5 验证穿越与白名单 |
