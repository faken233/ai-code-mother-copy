# 低代码应用生成与访问路由一体化改造设计方案

> 版本：v1.0 ｜ 面向项目：`yu-ai-code-mother`（Spring Boot 3 + LangChain4j + Vue 3）
> 范围：**生成应用侧**（从"传统前后端分离 + Redis"改为"一体化单端口 + MySQL-only"）与**访问路由侧**（预览/部署的短链代理，从主服务抽离为独立代理服务）。
> 参考：百度秒哒（业务描述驱动建表 / 用户归属隔离）、Supabase（自动数据 API + RLS）。**本方案在数据权限上采用"薄数据层 + 代码内授权"，而非 Supabase 式 RLS**（见 §2 决策与 §4 安全），以规避 AI 生成 RLS 易导致的"数据库裸奔"风险，并继续使用 MySQL，避免引入 Postgres/自托管 Supabase 的运维成本。

---

## 1. 背景与目标

### 1.1 现状问题

当前 FULLSTACK 生成（`src/main/resources/prompt/codegen-fullstack-system-prompt.txt`）产出的是**传统前后端分离**工程：

```
backend/   Spring Boot 3（web + mybatis-flex + mysql + redis + knife4j + jjwt + security-crypto）
frontend/  Vue3 + Vite（base './' + hash 路由，axios baseURL '/api'）
```

对"低代码、开发小白"用户来说问题明显：

- **前后端分离**：两套代码、两个端口（8080 前端 / 8081 后端），预览/部署要处理两个端口与跨域，链路长。
- **引入了 Redis**：低代码场景几乎用不上缓存/分布式锁/限流，却塞进了 `data-redis`、`spring-session-data-redis`、Redisson，运维负担大。
- **生成的是"传统 Web 工程"而非"低代码应用"**：需要用户理解工程结构，与"说业务 → 自动建表建接口"的低代码定位相悖。
- **隔离逻辑都在应用内**：`application-sandbox.yml` + 一套环境变量注入，复杂且脆弱（已知问题：预览后端进程用内存态 `previewProcessMap` 跟踪，主服务重启后丢失句柄，导致预览后端不存活）。
- **预览地址端口错误**：`AppController.previewApp` 用 `code.deploy-host`（默认 `http://localhost`）拼 URL，落在 80 端口，本地/容器均无服务 → "拒绝连接"。

### 1.2 目标

1. 生成**一体化应用**：一个应用 = 一个服务 = **一个端口**，前端构建产物由该服务直接托管，同时提供 REST API。
2. 生成应用**仅用 MySQL**，不引入 Redis。
3. 生成技术栈：**Node.js + Express（薄数据服务）+ React（Vite 前端）**，安全逻辑放在代码层。
4. 访问路由：基于 **accessCode 短链**，由**独立代理服务**转发到对应沙箱：
   - 页面：`http://{proxy-host}/{accessCode}/`
   - 后端接口 baseURL：`http://{proxy-host}/{accessCode}/api`
5. 代理逻辑从主服务抽离为**独立模块**，主服务只负责"生成 + 沙箱编排 + 写路由表"。

---

## 2. 关键设计决策

| 决策点 | 结论 | 理由 |
|---|---|---|
| 应用形态 | **一体化单端口**（前端构建产物由后端托管 + `/api/**`） | 小白免去前后端分离、多端口、跨域 |
| 数据存储 | **仅 MySQL**，无 Redis | 低代码场景用不上缓存；登录鉴权用 JWT 无状态，限流用内存/DB 计数 |
| 生成技术栈 | **Node.js + Express** + **React（Vite）** | 沙箱镜像已有 Node18 + npm（`code-sandbox:latest` 基于 `maven:...-temurin-21`），无需改镜像；Express 是 LLM 最不易写错的栈；React/Supabase 是秒哒同款前端范式 |
| 数据权限模型 | **路径 A：薄数据层 + 代码内授权**（自己造"SQL 版 Supabase-lite"） | "前端直连 DB（RLS）"把安全搬到 RLS，而 RLS 恰是 AI 最容易配错、导致数据库裸奔的地方；把授权放代码层更可控、可单测、可审计，且不迁 Postgres、不自托管 Supabase |
| 访问短链 | 每个应用一个 `accessCode`（复用现有 `deployKey`，6 位 base62），预览/部署共用 | 预览地址 = 部署地址，部署后同一链接直接变线上站 |
| 代理模块 | **独立 Spring Boot 代理服务**（`proxy/`），只读路由表、流式反代 | 与主工程同栈、可测试、动态短链好做；主服务不再代理/托管生成项目 |
| 多租户隔离 | 每应用独立库 `app_{appId}`（复用现状）+ 应用内 `userId` 归属过滤 | 对齐秒哒"业务描述自动建表 + 用户归属隔离"，权限在代码里 |

---

## 3. 目标架构

```
┌─────────────────────────── 主服务 spring-boot(8123) ───────────────────────────┐
│ 生成/任务队列/应用管理/部署编排                                                  │
│ SandboxContainerManager   ：启动/停止 code-sandbox-{appId}（单端口映射）          │
│ SandboxAppLauncher(新)    ：docker exec -d node server.js（一体化，单端口）      │
│ RouteRegistryClient(新)   ：写 DB + Redis 路由表（PREVIEW/DEPLOY）               │
│ （调整）AppController.previewApp / AppServiceImpl.deployApp 返回 proxy-host 短链 │
│ （移除）PreviewProxyController 代理职责 / StaticResourceController（视情况迁移） │
└───────────────────────────────────────────────────────────────────────────────┘
                                  │ 写路由(DB+Redis)（主服务写，代理只读）
                                  ▼
┌──────────── 路由注册表 ─────────────┐
│ MySQL : app_access_route            │
│ Redis : proxy:route:{accessCode}    │
└────────────────────────────────────┘
                                  ▲ 只读(带 TTL 缓存)
                                  │
┌──────────── 独立代理服务 proxy/ ─────┐   http://{proxy-host}
│ Spring Boot WebFlux                  │
│   /{accessCode}/api/** -> 沙箱端口(前端/后端分离由应用内部处理)                 │
│   /{accessCode}/**    -> 沙箱单端口   │
│   流式反代(SSE/http) + 路由缓存        │
└────────────────────────────────────┘
                                  │ 转发（剥离 /{accessCode} 前缀）
                                  ▼
┌──────────── code-sandbox-{appId} ──┐
│ node server.js   : 8080 (一体化)   │   宿主 18080 + appId%1000
│  = Express 托管前端 static + /api  │
│  = mysql2 pool -> 独立库 app_{id}  │
└────────────────────────────────────┘
```

- **代理服务**：不感知生成逻辑、不依赖 `code_output` 磁盘路径，只按短链路由到沙箱端口；可横向扩展。
- **主服务**：把沙箱拉起来 → 双(单)健康探测 → 写路由表 → 返回短链；不再直接代理/托管生成项目。

---

## 4. 生成侧设计（Node/Express + React + MySQL-only）

### 4.1 生成工程结构

```
my-app/
├── package.json               # 依赖：express / mysql2 / jsonwebtoken / bcryptjs（可选 multer）
├── server.js                  # 入口：静态托管 public/dist + /api/** 路由 + 启动建库建表
├── config.js                  # 读环境变量（DB_HOST/DB_PORT/APP_DB/DB_USER/DB_PASS/JWT_SECRET）
├── db.js                      # mysql2/promise 连接池 + 轻量 CRUD 辅助（按表名、分页、userId）
├── auth.js                    # JWT 签发/校验中间件 + bcryptjs 密码哈希
├── routes/
│   └── api.js                 # 自动暴露 /api/{table} 的 GET/POST/PUT/DELETE + 校验
├── schema.sql                 # 启动执行 CREATE TABLE IF NOT EXISTS（由 AI 按业务描述生成）
├── .env                       # 本地配置（含 JWT_SECRET、APP_DB）
└── public/
    └── dist/                  # React(Vite) 构建产物 —— 由 Express static 托管
```

### 4.2 技术栈与依赖

- **后端**：Node.js 18 + Express 4 + `mysql2/promise` + `jsonwebtoken` + `bcryptjs`（纯 JS，无需原生编译）。
- **前端**：React 18 + Vite（**锁定 Node 18 兼容版本，如 Vite 5/6**；沙箱是 Node 18，Vite 7 需要 Node 20）。`vite.config.js` 用 `base: './'`，hash 路由。
- **明确移除**：Spring Boot / MyBatis / Redis(`data-redis`、`spring-session-data-redis`、Redisson) / knife4j / 双配置文件（`application.yml` + `application-sandbox.yml`）。

### 4.3 关键实现点

1. **一体化为一个端口**：`server.js` 里
   - `app.use(express.static(path.join(__dirname,'public/dist')))` 托管前端；
   - `app.use('/api', apiRouter)` 提供 REST；
   - SPA 兜底：非 `/api` 路径返回 `index.html`（支持刷新/直达）。
2. **启动即建库建表**：启动时执行 `schema.sql`（`CREATE TABLE IF NOT EXISTS`），无需用户手动初始化。
3. **自动 CRUD（薄数据层）**：基于生成的表结构，`routes/api.js` 自动暴露：
   - `GET /api/{table}`（分页 + 可选 `userId` 归属过滤 + 排序）
   - `GET /api/{table}/:id`（校验归属）
   - `POST /api/{table}`（校验 + 自动写入 `user_id`）
   - `PUT /api/{table}/:id`、`DELETE /api/{table}/:id`（校验归属）
   - 参数校验用轻量方式（手写白名单/`express-validator` 可选），字段/表名走白名单防注入。
4. **权限在代码层（代替 RLS）**：
   - 登录体系：涉及"用户私有数据"时自动生成 `users` 表 + 注册/登录接口（bcryptjs），下发 JWT。
   - 中间件 `authRequired` 从 `Authorization: Bearer xxx` 解析 `userId`。
   - **所有归属查询强制带 `WHERE user_id = :userId`**（相当于 SQL 版 RLS，但逻辑在代码、可单测）。
   - 公开浏览型应用（如展示页）可不生成登录。
5. **相对 API base**：React 用 `axios.create({ baseURL: import.meta.env.BASE_URL + 'api' })`（`base: './'` → `./api`）。于是：
   - 本地/部署时请求 `http://host/api/...`；
   - 短链下请求 `http://{proxy-host}/{accessCode}/api/...` 自动命中代理到沙箱。
   - 全程同源、无 CORS。
6. **限流**（如需）：用内存桶或 DB 计数，**不引入 Redis**。

### 4.4 生成模板改动（`codegen-fullstack-system-prompt.txt`）

- 结构约束改为 §4.1 的 Node/Express + React 工程。
- 依赖白名单：`express`、`mysql2`、`jsonwebtoken`、`bcryptjs`。
- 明确"由业务描述推导 schema + 用户归属自动生成登录"（吸纳秒哒最佳实践）。
- 明确"前端 API base 用相对 `./api`，禁止携带 appId/端口/隔离标识"。
- 明确"不用 Redis；数据源仅 `app_{应用名}_db`，通过环境变量注入"。
- 构建校验：前端 `npm run build` 产物进 `public/dist`；后端 `node -c server.js` 语法检查 + 探活 `/api/health`（不再 `mvn package`）。

---

## 5. 沙箱与运行改造

- **单端口映射**：`SandboxContainerManager` 只映射一个端口（如容器 8080 → 宿主 `18080 + appId % 1000`），去掉原来的 `19080+...` 后端端口。
- **serve 模式**：`SandboxAppLauncher`（替代 `PreviewService`）用 `docker exec -d code-sandbox-{appId} node server.js` 启动一体化进程；**进程属容器、不随主服务重启丢失**。
- **健康探测**：只探 `http://{sandbox.host}:{hostPort}/api/health`，就绪后写路由表。
- **环境变量注入**：`APP_DB`/`DB_HOST`/`DB_PORT`/`DB_USER`/`DB_PASS`/`JWT_SECRET`。**无 Redis 相关变量**。
- 沙箱镜像无需改造（Node18 + npm 已在）；如生成 React 需构建，锁定 Node18 兼容的 Vite 版本。

---

## 6. 访问路由（短链 + 代理）

### 6.1 URL 与路由规则

`accessCode` = 复用现有 `deployKey`（6 位 base62，`App` 实体已具备）。

| 请求 | 处理 |
|---|---|
| `/{accessCode}`（无斜杠） | 301 → `/{accessCode}/` |
| `/{accessCode}/api/**` | 剥离 `/{accessCode}` → 转发沙箱单端口（应用内部命中 `/api/**`） |
| `/{accessCode}/**` | 剥离 `/{accessCode}` → 转发沙箱单端口（应用内部托管静态 + SPA 兜底） |

> 因为是一体化单端口，代理**无需区分前后端端口**，只需一条 subpath 反代规则；`/api` 与静态的分流由应用内部完成。

### 6.2 路由注册表

```sql
CREATE TABLE app_access_route (
  access_code   VARCHAR(16) PRIMARY KEY,
  app_id        BIGINT      NOT NULL,
  kind          VARCHAR(8)  NOT NULL,          -- PREVIEW / DEPLOY
  sandbox_host  VARCHAR(64) NOT NULL,          -- localhost / host.docker.internal / 沙箱IP
  port          INT         NOT NULL,          -- 一体化应用单端口
  status        VARCHAR(16) NOT NULL,          -- STARTING / READY / STOPPED
  expire_at     DATETIME NULL,                  -- 预览 TTL；部署为 NULL(永久)
  updated_at    DATETIME,
  KEY idx_app_id (app_id)
);
```

- Redis 缓存 `proxy:route:{accessCode}` = JSON（ttl 对齐 `expire_at`）。
- 路由表由**主服务写**、代理服务**只读**。
- 预览写 `PREVIEW`（带 TTL）；部署写 `DEPLOY`（无 TTL，覆盖并删除 PREVIEW），保证"预览链接=部署链接"。

### 6.3 代理服务实现要点（`proxy/`）

- **独立 Spring Boot WebFlux**：用 `WebClient`/`HttpClient` 做**流式反代**（生成后端可能带 SSE；旧的 `RestTemplate` 缓存 byte[] 会断流，须弃用）。
- 一个统一切面：解析 `/{accessCode}/xxx` → 查路由（本地 TTL 缓存 → Redis → DB）→ 剥离前缀 → 转发，透传 `method/headers/body/query`，校验同源。
- 路由为 `READY` 才转发；`STARTING` 返回 503、`accessCode` 不存在返回 404。
- 找不到 `index.html` 时回退到 `/`（SPA 兜底）。

---

## 7. 主服务改造点

| 位置 | 改动 |
|---|---|
| `AppController.previewApp` | 返回 `http://{code.proxy-host}/{accessCode}/`；先写 PREVIEW 路由并启动一体化沙箱 |
| `AppServiceImpl.deployApp` | 返回 `http://{code.proxy-host}/{deployKey}/`；写入 DEPLOY 路由并清 PREVIEW |
| `PreviewService` → `SandboxAppLauncher` | `docker exec -d node server.js`；单端口健康探测；不再 `docker exec mvn spring-boot:run` |
| `PreviewProxyController` | 移除代理/托管职责（生成项目不再经主服务代理） |
| `StaticResourceController` | 非全栈静态应用视情况迁移到代理侧或保留 |
| 配置 | 新增 `code.proxy-host`（生成项目公开地址），与平台 UI 的 `code.deploy-host` 解耦 |

**配置示例**：
```yaml
code:
  proxy-host: ${CODE_PROXY_HOST:http://localhost:8180}   # 生成项目访问入口（独立于平台 UI）
```
`docker-compose.yml` 增加 `proxy` 服务（监听 8180，读平台 Redis/MySQL 路由表）。

---

## 8. 生命周期与清理

- **预览**：`PREVIEW` 路由带 TTL（如 24h），到期或应用删除时主服务 `stopContainer` 并清路由。
- **部署**：`DEPLOY` 路由无 TTL，容器加 `--restart unless-stopped` 持久运行，不随主服务重启丢失。
- 预览/部署共用 `accessCode`，部署时原子地"写 DEPLOY + 删 PREVIEW"，避免链接闪断。

---

## 9. 本地/演示约束（保持"生成即运行"）

- 单一启动命令：`npm install && node server.js`（沙箱、本地、部署三态一致）。
- `schema.sql` 启动自动建表，无需手动导入。
- 无需 Redis；无需 Maven 冷启动与依赖下载。
- 前端无需单独起 dev server（构建产物由 Express 托管）；开发期也可用 Vite dev + `/api` 代理。

---

## 10. 分期实施

1. **生成侧改造**：重写 `codegen-fullstack-system-prompt.txt` 为 Node/Express + React 一体化 + MySQL-only；`BuildProjectTool` 改为前端 build + 后端探活。产出：AI 能生成一个 `node server.js` 即可跑、仅 MySQL 的应用。
2. **沙箱单端口**：`SandboxContainerManager` 单端口映射 + `SandboxAppLauncher`（`docker exec -d`）+ `/api/health` 探活。
3. **代理服务 `proxy/`**：WebFlux 流式反代 + 短链路由 + 路由缓存；造一条路由做集成测试（`/api` 与静态各走对）。
4. **主服务改造**：`previewApp/deployApp` 返回 `code.proxy-host` 短链；写路由表；移除以 `PreviewProxyController` 代理职责为核心的旧逻辑。
5. **配置与编排**：新增 `code.proxy-host` + `docker-compose` 的 `proxy` 服务；端到端联调（生成 → 预览 → 部署）。

---

## 11. 取舍与待确认

- **前端形式**：默认 React + Vite（秒哒同款）；若更简单，可退回"原生 HTML/CSS/JS，无构建"，由 Express 直接托管。
- **API 文档**：一体化后去掉 knife4j；如需可提供极简 `/doc.html` 或 `/api` 自描述。
- **文件上传**：用 `multer` 存本地目录并由 Express 托管；大文件不推荐入库（BLOB）。
- **限流/审计**：低代码场景用内存/DB 计数即可；若后续要更严格，再在代理层或薄数据层补。
- **是否需要"多应用共享后端"（秒哒式）**：当前先做"每应用独立库 + 独立沙箱容器"（隔离强、实现简单）；"共享后端 + 按 schema 隔离"作为后续平台级演进选项。

---

## 12. 参考

- 百度秒哒·后端存储使用技巧：https://cloud.baidu.com/doc/MIAODA/s/Dmh2w3m8t （业务描述驱动建表 / 用户归属隔离 / 自动生成登录）
- 秒哒×Supabase（托管后端）：https://baijiahao.baidu.com/s?id=1843478594374643912
- Supabase 安全（RLS / anon / service_role）：https://supabase.com/docs/guides/database/secure-data
- Vibe-Coded 应用 RLS 风险（170+ 应用数据库裸奔）：https://rafter.so/blog/vibe-coding-security/supabase-rls
- Supabase 自托管：https://supabase.com/docs/guides/self-hosting
