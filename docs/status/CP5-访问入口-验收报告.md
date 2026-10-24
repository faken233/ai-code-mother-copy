# CP5 访问入口改造 · 验收报告

> 时间：2026-10-21（Asia/Shanghai）
> 范围：P0（清场与基线）、P1（proxy 骨架 + 流式转发）、P2（路由表 + 预览地址切换）、P3（容器新形态 + 端口收敛）
> 状态：**P0–P3 完成，等待人工验收；P4 未开始**

---

## 0. 结论

| 阶段 | 结果 | 关键证据 |
|---|---|---|
| P0 清场与基线 | ✅ 通过 | 8 个旧容器删除，旧代码能从零重建容器并返回 200 |
| P1 proxy 骨架 | ✅ 通过 | `verify-proxy.sh` 14/14；CP1 经 proxy 16/16；CP3 回归 7/7 |
| P2 路由表 + 地址切换 | ✅ 通过 | 预览地址变成 `{code}.pv.localhost:8124`；**主服务停机时预览仍 200** |
| P3 容器新形态 | ✅ 通过 | 容器 `app-pv-*`、随机端口 + 回读；CP1 16 / CP2 10 / CP3 7 / proxy 14 全绿 |

一句话：预览已经不再经过主服务，地址由后端的路由服务生成、proxy 只读 Redis 转发。

---

## 1. P0 · 清场与基线

- 新增 `scripts/reset-sandbox.sh`（默认删容器；`--purge-db --yes` 才删库；不动中间件与源码）。
- 删除 8 个旧容器，用旧代码触发预览，容器重建成功、页面 200。

---

## 2. P1 · proxy 骨架

新增独立 WebFlux 工程 `proxy/`（端口 8124），按 Host 路由 + `Flux<DataBuffer>` 流式转发，主服务零改动。

| 文件 | 作用 |
|---|---|
| `proxy/.../web/ProxyHandler.java` | Host 路由、流式转发、逐跳头过滤、`X-Forwarded-*`、404/502/503 |
| `proxy/.../route/RouteStore.java` | 路由接口（P2 已改为响应式 `Mono<Route>`） |
| `proxy/.../web/SelfTestController.java` | `/__health`、`/__selftest/stream` |
| `scripts/proxy-up.sh` / `scripts/verify-proxy.sh` | 启停 / 14 条断言 |
| `scripts/verify-cp1.sh` | 新增 `EXTRA_HEADER`，支持经代理跑数据层断言 |

关键验收：`TTFB 1.007s << total 5.008s`（真流式），旧桥 `/api/preview/{appId}/` 仍 200。

---

## 3. P2 · 路由表 + 预览地址切换

### 3.1 改动清单

| 文件 | 改动 |
|---|---|
| `sql/app_access_route.sql` | 新表（host 主键 + app_id/kind/upstream/status/expire_at） |
| `model/entity/AppAccessRoute.java` | 实体（host 为 `KeyType.None` 主键） |
| `mapper/AppAccessRouteMapper.java` | MyBatis-Flex mapper |
| `service/AppRouteService.java` | **新增**：生成/复用 8 位 `previewCode`、写 DB + Redis、返回 URL |
| `controller/AppController.java` | `previewApp` 改为调用 `AppRouteService`，删除 `code.preview-base` 拼接 |
| `application.yml` | 用 `code.preview-host-suffix / preview-port / preview-scheme / preview-ttl-hours` 替换 `preview-base` |
| `proxy/.../route/RedisRouteStore.java` | **新增**：读 `route:{host}`，Caffeine 1s 缓存，异常映射为 503 |
| `proxy/.../route/StaticRouteStore.java` | 改为只保留排障别名（echo/dead），返回 `Mono` |
| `proxy/.../route/CompositeRouteStore.java` | **新增**：静态优先 → Redis |
| `proxy/.../route/RouteStoreUnavailableException.java` | **新增**：区分"Redis 挂了"(503) 与"路由不存在"(404) |
| `proxy/pom.xml` | 加 `spring-boot-starter-data-redis-reactive`、`caffeine` |
| `AppChatPage.vue` | 删除地址拼接兜底；跨域预览时隐藏「编辑模式」按钮 |

### 3.2 数据流

```
previewApp(appId)
  ├─ PreviewService.startPreview      // 确保容器 + 应用就绪
  ├─ AppRouteService.ensurePreviewRoute
  │    ├─ 复用/分配 previewCode → host = {code}.pv.localhost
  │    ├─ upstream = SandboxContainerManager.appBaseUrl(appId)
  │    ├─ UPSERT app_access_route (expire_at = now + 24h)
  │    └─ SET route:{host} {...} EX 90000   // Redis 比 DB 多留 1h
  └─ 返回 http://{code}.pv.localhost:8124/

浏览器 → proxy:8124 → [静态别名?] → Redis route:{host} → 127.0.0.1:{容器端口}
```

设计取舍：proxy **只读 Redis**，不查 MySQL、不调主服务——这是"主服务重启链接不断"的实现基础。

### 3.3 自动验收

```
previewApp → http://h2lms44r.pv.localhost:8124/
Redis GET route:h2lms44r.pv.localhost
  {"host":"h2lms44r.pv.localhost","appId":451590758596771845,"kind":"PREVIEW",
   "upstream":"http://localhost:20925","status":"READY"}
Redis TTL = 89997s（25h）
DB 行：kind=PREVIEW, expire_at=2026-10-22 20:42:45
页面：200 text/html；/api/health: {"app":true,"db":"up"}
注册 + 记账 + 列表（经新域名）：全部成功
两次 preview 返回同一地址（✅ 地址稳定）
```

回归与可用性：

| 项 | 结果 |
|---|---|
| `scripts/verify-proxy.sh` | 14/14（Host 自动从 Redis 发现） |
| CP1 数据层经 proxy | 16/16 |
| `scripts/verify-cp3.sh` | 7/7（预览地址已是新域名） |
| 旧桥 `/api/preview/{appId}/` | 200（保留兼容） |
| **主服务完全停止后访问预览** | **200** —— 预览不再依赖主服务 |

### 3.4 需要你人工验收的 4 件事

1. **前端点「预览」**
   平台 `http://localhost:5173` → 打开应用 451590758596771845 → 点「预览」。
   预期：iframe 地址为 `http://{8位code}.pv.localhost:8124/`，页面正常、能注册记账。
2. **刷新浏览器仍在登录态**
   iframe 内刷新（或右键重新加载 frame），预期应用内登录不丢（localStorage 属于该子域）。
3. **主服务重启不破链**
   保持 iframe 打开 → 重启后端 → 不刷新 iframe，直接再记一笔。预期正常。
4. **旧地址仍可用**
   `http://localhost:8123/api/preview/451590758596771845/` 预期 200（P2 保留兼容，P6 删除）。

> 提示：`{code}` 每次都是随机新值。想知道当前的自检域名，可跑
> `docker exec yu-ai-platform-redis redis-cli --scan --pattern 'route:*'`。

### 3.5 已知限制（后续阶段处理）

| 限制 | 影响 | 阶段 |
|---|---|---|
| 「编辑模式」在跨域预览下不可用 | 按钮已隐藏，不再是"点了就报错" | 后续（需要 postMessage 代理或应用内注入） |
| 路由 TTL 到期后只是 404，不回收容器 | Redis key 过期即失效；容器靠 P6 reconcile | P6 |
| 容器若在 preview 之外被重建（如崩溃），Redis 里 upstream 可能过期 | 需再点一次「预览」刷新；P3 改随机端口后更明显 | P3 + P6 |
| 预览是能力 URL（拿到 code 即可访问） | 单机阶段可接受 | 后续可加入场券 |
| 端口仍按 `appId % 1000` 计算 | 与路由无关，但会撞端口 | P3 |

---

## 4. P3 · 容器新形态

### 4.1 改动清单

| 文件 | 改动 |
|---|---|
| `SandboxContainerManager` | 容器名 `code-sandbox-*` → **`app-pv-{appId}`**；端口改 `-p 127.0.0.1::8080` / `::3000`（内核随机分配）+ `docker port` 回读；删掉 8081 映射与全部端口公式；新增 `--label app.id` / `app.kind=preview`；端口缓存 30s TTL + `invalidatePorts()`；`stopContainer` 清缓存 |
| `SandboxProperties` | 删除 `previewPortRangeStart/Size`、`appPortRangeStart`、`backendPortRangeStart`、`containerBackendPort` |
| `SandboxClient` | `getPreviewPort` 改走 `toolHostPort`（回读）；删除 `getBackendPort` |
| `PreviewService` | **删除内存进程模型**（`previewProcessMap` / `docker exec mvn spring-boot:run`）。旧格式项目预览直接报错 |
| `PreviewProxyController` | 删除 `proxyToBackend`（8081）与 `serveStatic`（旧格式静态托管）；非一体化项目返回 **410**；取端口失败返回 503 |
| `SandboxAppLauncher` | 预览前 `invalidatePorts` 强制回读；报错文案改用 `containerNameOf` |
| `scripts/lib/sandbox.sh` | **新增**：容器名/回读端口的唯一来源 |
| `sandbox-up.sh` / `sandbox-app.sh` / `verify-cp2.sh` / `verify-cp3.sh` | 改用 helper；CP2 在 `docker restart` 后重新回读端口 |

### 4.2 一个重要发现（已处理）

**随机宿主端口在 `docker restart` 后会被 Docker 重新分配**：

```
restart 前 127.0.0.1:57977  →  restart 后 127.0.0.1:58512
```

这带来两个问题，都已修掉：

1. **服务端缓存必须带 TTL**：`portCache` 改成 30s 过期，并在 `stopContainer` / 每次预览前主动失效。实测：重启容器后点一次「预览」，路由 upstream 从 `57977` 刷新为 `58512`，页面 200。
2. **脚本不能沿用重启前的端口**：`verify-cp2.sh` 重启后重新 `docker port`，否则必然误报。

> P6 的 reconcile 也要覆盖这一点：容器重启后要刷新 `app_access_route.upstream`，否则路由指向旧端口。

### 4.3 自动验收

```
容器：app-pv-451590758596771845  127.0.0.1:58512->3000, 127.0.0.1:58511->8080
      app-pv-470496822998237184  127.0.0.1:58091->3000, 127.0.0.1:58090->8080
      app-pv-470499886224277504  127.0.0.1:58703->3000, 127.0.0.1:58704->8080
      app-pv-900001              127.0.0.1:58499->3000, 127.0.0.1:58498->8080
      ↑ 四个容器、八个随机端口，互不冲突；只有 8080 与 3000 两个通道（8081 已消失）
```

| 项 | 结果 |
|---|---|
| `scripts/verify-proxy.sh` | 14/14（Host 逐个探活，自动跳过死路由） |
| CP1 数据层经 proxy | 16/16 |
| `scripts/verify-cp2.sh` | 10/10（含 restart 自愈、数据不丢、进程不重复） |
| `scripts/verify-cp3.sh` | 7/7（预览地址为新域名，旧桥仍 200） |
| 完整生成链路 | 新任务 `completed` → `http://qanv4o3v.pv.localhost:8124/` → 页面 200、`app:true` |
| `grep -rn "appPortRangeStart\|previewPortRange\|legacyBackendHostPort" src scripts` | 无结果 |

### 4.4 需要你人工验收的 3 件事

1. **新建一个应用并预览**：地址应是新域名，页面正常。
2. **`docker ps` 看容器名与端口**：应是 `app-pv-{appId}`、`127.0.0.1:{随机}->3000`，没有 2008x/1808x 段。
3. **旧格式应用**（`vue_project` / 老 `fullstack`）：点预览应提示「旧格式项目已停止支持预览，请重新生成一体化应用」。

### 4.5 已知限制

| 限制 | 影响 | 阶段 |
|---|---|---|
| `docker restart` 会换宿主端口 | 已用 TTL + 预览前回读缓解；重启后不点预览仍可能 502 | P6 reconcile |
| 旧格式项目不可预览 | 410 + 明确提示 | 按决策接受 |
| 端口缓存 30s | 极端情况下最多 30s 的旧端口 | 可接受 |
| 预览是能力 URL | 拿到 code 即可访问 | 后续可加入场券 |

---

## 5. 环境说明

- 后端已用新代码重启（日志 `/tmp/backend-p2.log`，`.env` 已加载，密钥未输出）。
- proxy 以独立进程运行在 8124；`scripts/proxy-up.sh status` 可看健康与 Redis 路由。
- `~/.m2` 受限不可写，proxy 使用工程内 `.m2repo/`（已 gitignore）。

---

## 6. 下一步（未开始，等你确认）

**P4 · 工具通道 token + 对话热更新**：`sandbox/app.js` 除 `/api/health` 外加 `X-Tool-Token`（HMAC(secret, appId) 注入容器）、新增 `/api/app/rebuild` 与 `/api/app/restart`；生成结束后平台调用重建 + 聊天 SSE 推 reload，前端在 URL 不变时 `iframe.reload()`。

你说「继续」，我才开始 P4。
