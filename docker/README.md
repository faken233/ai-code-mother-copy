# 代码生成系统 Docker 一键部署

本文档说明如何用 `docker-compose` 一键部署 **AI 代码生成系统**（主服务 + 前端 + 代码沙箱）。

## 架构

```
┌───────────────┐   nginx /api 反代   ┌──────────────────────┐
│   frontend    │ ────────────────▶   │   backend (Spring)   │
│  (nginx:80)   │                     │        :8123          │
└───────────────┘                     └──────────┬───────────┘
                                                  │ docker.sock + host.docker.internal
                                                  ▼
                                        ┌─────────────────────┐
                                        │  code-sandbox sidecar│
                                        │  (按 appId 动态起)    │
                                        └─────────────────────┘
```

- **backend**：Spring Boot 3 主服务，多阶段 Docker 镜像（Maven 编译 → JRE 运行）。
- **frontend**：Vue3 构建产物由 nginx 托管，`/api` 反代到 backend。
- **code-sandbox**：全栈项目代码沙箱镜像（JDK21 + Maven + Node18 + 依赖缓存），由主服务通过 `docker.sock` 按 appId 动态拉起。
- **MySQL / Redis**：复用宿主机已有实例，通过 `host.docker.internal` 连接（不占用额外端口）。

## 前置条件

1. 本机已装 **Docker Desktop**（含 docker compose v2）。
2. 本机已有 **MySQL(3306)** 和 **Redis(6379)** 在运行，且允许容器经 `host.docker.internal` 访问。
3. 数据库 `yu_ai_code_mother` 已创建（或用你自定义的连接信息）。

## 一键启动

```bash
# 1. 准备环境变量（首次会自动从 .env.example 复制，请填写 LLM_API_KEY 等真实值）
cp .env.example .env
vim .env                        # 填入 AI 模型 API Key、数据库密码等

# 2. 构建并启动
./deploy.sh                     # 等价于 docker compose up -d --build
```

启动后：

- 前端： http://localhost:8080
- 后端接口文档(knife4j)： http://localhost:8123/api/doc.html
- 健康检查： http://localhost:8123/api/actuator/health

## 常用命令

```bash
./deploy.sh logs        # 跟踪日志
./deploy.sh down        # 停止并移除
./deploy.sh rebuild     # 强制重建镜像
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
| `MYSQL_USER` | root | 连接宿主机 MySQL 的用户 |
| `MYSQL_PASSWORD` | 123456 | 连接宿主机 MySQL 的密码 |
| `LLM_API_KEY` | - | AI 模型 API Key（必填） |
| `LLM_BASE_URL` | https://api.deepseek.com | AI 模型 base URL |
| `SANDBOX_IMAGE` | code-sandbox:latest | 沙箱镜像名 |
| `CODE_OUTPUT_DIR` | ./tmp/code_output | 代码输出目录（与沙箱共享） |
| `CODE_DEPLOY_DIR` | ./tmp/code_deploy | 部署目录（与沙箱共享） |

## 注意事项

- **密钥不入 git**：`.env` 已被 `.gitignore` 忽略，请勿提交真实 `LLM_API_KEY`。
- **复用宿主机 MySQL/Redis**：主服务与沙箱容器都通过 `host.docker.internal` 连宿主机数据库，因此 compose 只需挂 `docker.sock` 即可动态管理沙箱容器。
- **沙箱容器数据隔离**：每个 appId 一个独立容器/库/Redis db（见主方案 A7）。
