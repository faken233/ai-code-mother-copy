# CP1 验收报告 · 平台数据层黄金样例

> 时间：2026-10-20 20:35（Asia/Shanghai）
> 范围：Phase 0 / CP1 —— **模板能跑 + 数据层安全**（不含 React 构建链、不含沙箱工具通道、不含短链代理）
> 结论：**自动验收 16/16 全通过**，人工验收链接已就绪 → <http://localhost:20081>
>
> ⚠️ 本报告是 CP1 的历史记录。CP2 起脚本更名：`scripts/demo-up.sh` → `scripts/sandbox-up.sh`，
> `scripts/demo-down.sh` 已移除（改用 `scripts/sandbox-app.sh stop` 或 `docker rm -f`）。
> 端口变量 `PORT` 也已改为 `APP_PORT`（避免与沙箱文件服务的 `PORT` 冲突），详见 `CP2-验收报告.md`。

---

## 一、环境核查（开工前）

| 项 | 结果 |
|---|---|
| Docker | Server 29.1.2（Docker Desktop，8 CPU / 8 GB） |
| 镜像 | `code-sandbox:latest`（1.43 GB，含 Node 18）、`mysql:8.0`（1.06 GB） |
| 容器启停 | `start` / `stop` / `restart` 均正常；MySQL 健康检查回到 `healthy` 后查询成功 |
| MySQL | 8.0.46，宿主 3307，`max_connections=151` |
| 宿主机 Node | v24.11.1（**不用于验收**，验收跑在容器内 Node 18） |
| 平台后端 8123 / 前端 5173 | 未启动（CP1 不依赖平台，CP3 才需要） |

结论：环境齐全，可以开工。

---

## 二、交付物

| 路径 | 行数 | 说明 |
|---|---|---|
| `templates/node-unified/package.json` | 19 | 应用根依赖（锁版本） |
| `templates/node-unified/runtime/server.js` | 64 | 单端口入口：API → 静态 → SPA 兜底 → 错误出口 |
| `templates/node-unified/runtime/config.js` | 26 | 只读环境变量 |
| `templates/node-unified/runtime/db.js` | 45 | 唯一数据库入口（连接池 4，只导出参数化方法） |
| `templates/node-unified/runtime/migrate.js` | 100 | 启动建表 + 增量加列 |
| `templates/node-unified/runtime/auth.js` | 101 | 注册/登录/JWT/bcrypt/内存限流 |
| `templates/node-unified/runtime/lib/ddl.js` | 97 | 声明 → DDL |
| `templates/node-unified/runtime/lib/query.js` | 154 | 白名单 + 占位符 + 强制归属 |
| `templates/node-unified/runtime/lib/validate.js` | 97 | 声明式校验，丢弃未声明字段 |
| `templates/node-unified/runtime/lib/errors.js` | 26 | 统一错误形状 |
| `templates/node-unified/runtime/routes/api.js` | 107 | 自动 CRUD + health + auth |
| `templates/node-unified/samples/expense/schema.json` | 54 | 黄金样例声明（记账本） |
| `templates/node-unified/samples/expense/public/dist/index.html` | 275 | 验收用前端（含越权探针） |
| `templates/node-unified/README.md` | — | 模板说明与扩展方式 |
| `scripts/build-sample.sh` | 24 | 组装应用目录 |
| `scripts/demo-up.sh` | 68 | 起 MySQL + 建库建账号 + 起容器 |
| `scripts/demo-down.sh` | 14 | 清理 |
| `scripts/verify-cp1.sh` | 114 | 16 条自动验收断言 |

合计 **19 个新文件 / 约 1400 行**，未修改任何既有 Java 代码（`git status` 可核对）。

---

## 三、自动验收结果

命令：`bash scripts/verify-cp1.sh`

```text
== CP1 验收 ==
   目标: http://127.0.0.1:20081

[✅] 健康检查 GET /api/health → 200，且 app=true
[✅] 注册账号 A → 201
[✅] 注册账号 B → 201
[✅] A 新增记录 → 201
[✅] 请求体注入 user_id=999999 被丢弃，实际归属为 A(id=1)
[✅] A 新增第二条 → 201
[✅] A 的列表 total=2
[✅] B 的列表 total=0（数据隔离）
[✅] 未登录访问 owner 表 → 401
[✅] B 修改 A 的记录 → 404（不是 200/403）
[✅] B 删除 A 的记录 → 404
[✅] A 的记录未被篡改，标题仍是「午饭」
[✅] 金额为负 → 400（声明式校验）
[✅] 非法枚举值 → 400
[✅] 未声明的表 → 404（表名白名单）
[✅] 非法排序字段被拒绝（不拼进 SQL）

== 结果：16 通过 / 0 失败 ==
```

补充证据：

| 检查 | 证据 |
|---|---|
| 运行在目标运行时 | `docker exec demo-expense node -v` → **v18.20.4**（Ubuntu 24.04.2） |
| 单端口 | `docker port demo-expense` → 只有 `3000/tcp -> 0.0.0.0:20081` |
| SPA 兜底 | `/` → 200，`/some/deep/route` → 200（都返回 index.html） |
| 数据落库且是 UTF-8 | `expenses` 表：`午饭 / 32.50 / 餐饮 / user_id=1`、`地铁 / 6.00 / 交通 / user_id=1` |
| 重启不丢数据 | `docker restart demo-expense` 后 health 仍 `{"ok":true,"app":true}`，列表仍两条 |
| 账号隔离 | 库 `app_demo` 使用独立账号 `app_demo`，仅授权 `app_demo.*` |

---

## 四、人工验收（请你现在做）

打开 **<http://localhost:20081>**

| 步骤 | 操作 | 预期 |
|---|---|---|
| 1 | 切到「注册」，用 `a@demo.com / pass123456` 注册 | 进入主界面 |
| 2 | 填标题/金额/分类/时间，点「保存」两次 | 列表出现两条，右上角显示当前用户 |
| 3 | 点「退出登录」，用 `b@demo.com / pass123456` 注册 | 列表**为空**，提示“数据隔离生效” |
| 4 | 在「越权检查」里填 **1**，点「尝试修改」 | 返回 **HTTP 404**，页面显示 ✅ 已拦下 |
| 5 | 退出、用 A 登录，刷新 | A 的两条记录**原样未变** |

> 页面在新增时**故意**往请求体里塞了 `user_id: 999999`。你看到的数据仍归属自己 —— 这就是第 3 条自动断言在界面上的表现。

停止/重启：

```bash
docker restart code-sandbox-900001   # 重启容器（CP2 起应用会自动拉起）
bash scripts/sandbox-up.sh           # 重新拉起（幂等）
bash scripts/sandbox-app.sh stop     # 停掉应用进程（容器保留）
docker rm -f code-sandbox-900001     # 删掉容器
```

---

## 五、这次验证到的设计点

| 设计目标 | 落地位置 | 验证方式 |
|---|---|---|
| 一个应用 = 一个端口 | `server.js` 单进程 | `docker port` 只有一个映射 |
| 前端产物与 API 同源 | `express.static` + `/api` 同端口 | 页面与接口同在 20081 |
| 声明式建表 | `schema.json` → `lib/ddl.js` → `migrate.js` | 启动日志建表，表结构由声明决定 |
| 归属过滤不可遗漏 | `lib/query.js` 的 `scope()` | B 改/删 A 的记录 → 404 |
| 请求体无法覆盖归属 | `lib/validate.js` 丢弃未声明字段 | 注入 `user_id=999999` 无效 |
| 表名/列名白名单 | `routes/api.js` 的 `param('table')`、`buildFilters` | 未声明表 404，非法排序 400 |
| 无 Redis | `auth.js` 内存限流 | 依赖里没有 redis |
| 声明式校验 | `lib/validate.js` | 负数/非法枚举 → 400 |

---

## 六、已知限制（CP1 明确不覆盖）

1. **应用是容器主进程**（`sh -c "node server.js"`），不是目标形态的 `docker exec -d`。
   所以本报告只证明“进程重启可用”，**没有证明“主服务重启后应用仍在”** —— 那是 CP2 的验收点。
2. **没有 React/Vite 构建链**：前端是一个静态 HTML，用来验证数据层与页面同源，不验证构建。
3. **没有沙箱工具通道**：8080 文件服务未启动，AI 读写文件的链路未参与。
4. **迁移只做加法**：删列/改类型会报错而不执行（有意为之，防止静默丢数据）。
5. **限流是进程内 Map**：单实例有效；多副本时需换成 DB 计数。
6. **MySQL `max_connections=151`**：连接池 4 × 20 应用 = 80，短期够用；上量前需调到 500+。

---

## 七、开工过程中修掉的 3 个问题（留档）

| 现象 | 根因 | 修法 |
|---|---|---|
| 容器报 `Access denied for 'app_demo'` | `docker exec` 未加 `-i`，heredoc 没进 stdin，建账号语句被**静默丢弃**（退出码仍 0） | `demo-up.sh` 改为 `docker exec -i`；**幂等初始化必须回读校验** |
| 脚本报 `APP_USER…: unbound variable` | bash 把紧跟变量的全角字符并入了变量名 | 脚本内变量统一写成 `${VAR}` |
| `mysql` 查询中文显示 `??` | 客户端未指定字符集（库内其实是 UTF-8） | 查询加 `--default-character-set=utf8mb4`；以 API 返回为准 |

第 1 条是这次最有价值的教训：**“命令成功”不等于“事情做成了”**。后续所有初始化动作都要有回读断言。

---

## 八、下一步（CP2）

1. 应用改为 `docker exec -d` 启动，与 8080 工具通道并存（容器内两个端口）。
2. 验收“主服务重启后应用仍可访问” —— 用 `docker restart` 模拟主服务重启，链接不断。
3. 引入 React + Vite：`npm run build` → `public/dist`，`base: './'` + hash 路由。
4. 探活拆分：工具通道 `/api/health`（文件服务）与应用 `/api/health`（`app:true`）分开报错。

CP2 结束时你会拿到同一形状的三样东西：**能点的链接 + 一键验收脚本 + 这份格式的报告**。
