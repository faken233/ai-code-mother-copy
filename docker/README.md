# 代码生成系统 Docker 一键部署

本文档说明如何用 `docker-compose` 一键部署 **AI 代码生成系统**（主服务 + 访问入口代理 + 前端 + 沙箱依赖）。

## 架构

```
                          ┌──────────────────────────┐
   浏览器 ───────────────▶│  frontend (nginx:80)      │
     │  平台页面           └───────────┬──────────────┘
     │                                │ /api 反代
     │                                ▼
     │                    ┌──────────────────────────┐
     │                    │  backend (Spring Boot)   │
     │                    │        :8123             │
     │                    └───────┬─────────┬────────┘
     │                            │         │ docker.sock
     │  预览/部署域名              │ 写路由   │ 动态拉起沙箱
     ▼                            ▼         ▼
┌─────────────────────┐   ┌─────────────┐  ┌──────────────────────┐
│ proxy (WebFlux)     │◀──│ Redis       │  │ code-sandbox sidecar │
│ :8124               │   │ route:{host}│  │ app-pv-* / app-dep-* │
└──────────┬──────────┘   └─────────────┘  └──────────┬───────────┘
           │ 按 Host 转发到随机宿主端口                │
           └──────────────────────────────────────────┘
```

- **backend**：Spring Boot 3 主服务，多阶段镜像（Maven 编译 → JRE 运行），挂 `docker.sock` 管理沙箱。
- **proxy**：访问入口代理（独立镜像、独立进程）。只读 Redis 路由表，按 Host 流式转发到沙箱容器；
  主服务发版/重启不影响已发出的预览链接。
- **frontend**：Vue3 构建产物由 nginx 托管，`/api` 反代到 backend。
- **code-sandbox**：代码沙箱镜像（JDK21 + Maven + Node18 + 依赖缓存），由主服务按 appId 动态拉起。
- **MySQL / Redis**：compose 内自带平台库/缓存与沙箱专用库/缓存（见服务清单）。

## 服务与端口

| 服务 | 容器名 | 宿主机端口 | 说明 |
|---|---|---|---|
| `frontend` | `yu-ai-code-mother-frontend` | 8080 | 平台页面 |
| `backend` | `yu-ai-code-mother-backend` | 8123 | 主服务 `/api` |
| `proxy` | `yu-ai-code-mother-proxy` | 8124 | 预览/部署域名入口 |
| `platform-mysql` | `yu-ai-platform-mysql` | 3306 | 平台主库 |
| `platform-redis` | `yu-ai-platform-redis` | 6379 | 平台缓存 + 路由表 |
| `sandbox-mysql` | `yu-ai-sandbox-mysql` | 3307 | 生成应用库（物理隔离） |
| `sandbox-redis` | `yu-ai-sandbox-redis` | 6380 | 生成应用缓存 |

## 一键启动

```bash
# 1. 准备环境变量（首次会自动从 .env.example 复制，请填写 LLM_API_KEY 等真实值）
cp .env.example .env
vim .env                        # 填入 AI 模型 API Key 等

# 2. 构建并启动（首次会构建 backend / frontend / proxy 三个镜像）
./deploy.sh                     # 等价于 docker compose up -d --build
```

启动后：

- 平台页面： <http://localhost:8080>
- 后端接口文档： <http://localhost:8123/api/doc.html>
- 健康检查： <http://localhost:8123/api/actuator/health>
- 代理入口： `http://localhost:8124`（自检端点 `/__health` 默认关闭，
  排障时给 proxy 加 `PROXY_SELFTEST_ENABLED=true` 再重启）

## 生成应用的访问地址

平台在生成/预览/部署时返回的就是这两个域名，浏览器直接访问（`*.localhost` 由浏览器解析到 127.0.0.1）：

- 预览： `http://{8位code}.pv.localhost:8124/`
- 部署： `http://{6位deployKey}.app.localhost:8124/`

路由链路：主服务写 `app_access_route` + Redis `route:{host}` → proxy 读 Redis →
转发到沙箱容器随机宿主端口。**proxy 不可用时这两个地址都会打不开**（平台页面本身不受影响）。

## 常用命令

```bash
./deploy.sh logs        # 跟踪日志
./deploy.sh down        # 停止并移除
./deploy.sh rebuild     # 强制重建镜像
docker compose up -d proxy            # 只重建/启动代理
docker logs -f yu-ai-code-mother-proxy
```

## 沙箱镜像说明

沙箱镜像 `code-sandbox:latest` 需提前构建（首次构建含 Maven 依赖缓存，耗时较长）：

```bash
cd sandbox
docker build -t code-sandbox:latest .
```

`deploy.sh` 会自动检测，若不存在则自动构建。

## 配置项（.env）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `BACKEND_PORT` | 8123 | 后端映射端口 |
| `FRONTEND_PORT` | 8080 | 前端映射端口 |
| `PROXY_PORT` | 8124 | 代理映射端口（同时作为生成应用入口端口） |
| `MYSQL_USERNAME` / `MYSQL_PASSWORD` | root / 123456 | 平台库账号 |
| `LLM_API_KEY` | - | AI 模型 API Key（必填） |
| `LLM_BASE_URL` | https://api.deepseek.com | AI 模型 base URL |
| `SANDBOX_IMAGE` | code-sandbox:latest | 沙箱镜像名 |
| `CODE_OUTPUT_DIR` | ./tmp/code_output | 代码输出目录（与沙箱共享） |
| `CODE_DEPLOY_DIR` | ./tmp/code_deploy | 部署目录（与沙箱共享） |

## 注意事项

- **密钥不入 git / 不进镜像**：`.env` 已被 `.gitignore` 与 `.dockerignore` 排除，请勿提交真实 `LLM_API_KEY`。
- **proxy 与 backend 要一起跑**：代理只读 Redis 路由，路由里的上游地址由 backend 按 `SANDBOX_HOST=host.docker.internal`
  写入。如果 backend 跑在宿主机、proxy 跑在容器里，宿主机写入的 `localhost` 上游代理容器访问不到 ——
  这种混合模式请改用 `scripts/proxy-up.sh` 在宿主机起代理。
- **Linux 宿主机**：沙箱端口默认只绑 `127.0.0.1`，容器经 `host-gateway` 访问不到；如需全 Linux 部署，
  需把 `SANDBOX_BIND_ADDRESS` 调整为容器可达的地址（注意工具通道 `/api/exec` 不要暴露到公网）。
- **沙箱数据隔离**：每个 appId 独立容器 + 独立库 + 独立账号，凭据只注入容器 env。
