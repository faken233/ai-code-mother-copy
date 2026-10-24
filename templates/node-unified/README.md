# templates/node-unified

平台**固定**的一体化应用运行时（Node 18 + Express 4 + React/Vite + MySQL 8）。
一个应用 = 一个进程 = 一个端口：静态产物、`/api/**`、SPA 兜底全部由同一进程提供。

> 归属约定：`runtime/**`、`vite.config.js`、`package.json` 属于**平台**，生成时无条件覆盖，AI 不可写；
> `schema.json`、`client/**`、`routes/custom.js` 属于**AI**。

---

## 目录

```
templates/node-unified/
├── package.json          # 应用根依赖（锁版本；server 依赖 + 构建依赖分离）
├── vite.config.js        # base './' + root client + outDir ../public/dist
├── runtime/              # 会被复制到应用根目录
│   ├── server.js         # 入口：json → /api → static → SPA 兜底 → 错误处理
│   ├── config.js         # 只读环境变量（APP_PORT / APP_DB / DB_* / JWT_SECRET）
│   ├── db.js             # 唯一数据库入口（连接池 4，只导出参数化方法）
│   ├── migrate.js        # 启动建表 + 增量加列（破坏性变更 fail-fast）
│   ├── auth.js           # 注册/登录/JWT/bcrypt/内存限流
│   ├── lib/ddl.js        # schema.json → CREATE/ALTER
│   ├── lib/query.js      # 唯一允许拼 SQL 的地方（白名单 + 占位符 + 强制归属）
│   ├── lib/validate.js   # 按声明校验并丢弃未声明字段（含 user_id）
│   ├── lib/errors.js     # 统一错误码
│   └── routes/api.js     # 按表自动挂 CRUD + /api/health + /api/auth/**
└── samples/expense/      # 黄金样例（记账本）
    ├── schema.json
    └── client/           # React 源码（HashRouter，api 只用相对 /api）
```

---

## 四条不可破坏的约束

1. **只有 `lib/query.js` 能拼 SQL**，列名/表名只来自 `schema.json`，值只走 `?`。
2. **归属条件由 `query.scope()` 注入**，业务代码没有"忘记加 user_id"的入口。
3. **`db.js` 不导出裸连接**，自定义路由只能拿到受限方法。
4. **应用端口是 `APP_PORT`，不是 `PORT`** —— 沙箱容器里 `PORT` 归文件服务（8080），
   容器级 env 会被两个进程继承，必须用独立变量名才能共存。
5. **前端 API 基址必须按当前文档路径推导，禁止写死 `'/api'`** ——
   在 `/api/preview/{appId}/` 或 `/{accessCode}/` 下，绝对 `/api` 会打到平台后端。
   正确写法见 `samples/expense/client/src/api.js` 的 `resolveApiBase()`。

改代码前先跑 `scripts/verify-cp1.sh` 与 `scripts/verify-cp2.sh`，任何一条从 ✅ 变 ❌ 都算回归。

---

## 如何新增一张表

只改 `schema.json`：

```json
{
  "tables": {
    "notes": {
      "owner": true,
      "columns": {
        "content": { "type": "text", "max": 2000, "required": true }
      }
    }
  }
}
```

重启应用即可：`migrate.js` 会 `CREATE TABLE notes`，`routes/api.js` 自动挂
`GET/POST/PUT/DELETE /api/notes`。**不需要写任何后端代码。**

给已有表加一列同理，重启后自动 `ALTER TABLE ... ADD COLUMN`（新增列先允许 NULL，避免历史行插入失败）。

---

## 本地跑一遍

```bash
# 构建 + 起容器（工具通道 18081->8080，应用通道 20081->3000）
scripts/sandbox-up.sh

# 只重建产物、不动容器
scripts/sandbox-up.sh --no-build

# 验收（CP2：拓扑 + 重启自愈 + CP1 的 16 条数据层断言）
scripts/verify-cp2.sh

# 浏览器冒烟（需要先起带 --remote-debugging-port 的浏览器，见 scripts/dev/ui-smoke.mjs 头部注释）
node scripts/dev/ui-smoke.mjs <appId>

# 应用进程生命周期
scripts/sandbox-app.sh status | start | stop | restart | logs
```

沙箱容器里同时跑两个进程：

```
PID 1  node /app/app.js      # 工具通道 :8080（AI 读写文件用）
PID n  node server.js        # 应用通道 :3000（start.sh 后台拉起）
```

容器重启后应用由 `start.sh` 自动拉起，不依赖主服务保存进程句柄。

---

## 当前范围

- ✅ CP1：数据层（建表/迁移/CRUD/校验/归属/白名单）、认证、单端口
- ✅ CP2：React + Vite 构建链、沙箱双通道、`exec -d` 启动、容器重启自愈
- ✅ CP3：主服务接线（`SandboxAppLauncher` + 平台"预览"按钮 + `/api/preview/{appId}/` 透传）
- ✅ CP4：生成侧接线（AI 只产 `schema.json` + 页面；平台注入运行时；构建校验 + 兜底构建）
- ⬜ P1：accessCode 短链 + 独立 proxy（流式）、端口不再发布
