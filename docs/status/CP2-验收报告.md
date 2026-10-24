# CP2 验收报告 · 沙箱双通道 + React 构建链

> 时间：2026-10-20 21:02（Asia/Shanghai）
> 范围：Phase 0 / CP2 —— **应用进真实沙箱容器、双端口并存、进程属容器、React+Vite 构建链**
> 结论：**CP2 自动验收 10/10 通过，CP1 数据层 16/16 回归通过**
> 验收链接：<http://localhost:20081>（React 版，验收步骤与上一轮相同）

---

## 一、这一轮改变了什么

| | CP1 | CP2 |
|---|---|---|
| 运行载体 | 临时容器，应用=容器主进程 | **真实沙箱镜像**：PID1=文件服务 :8080，应用是子进程 :3000 |
| 启动方式 | `docker run ... node server.js` | **`start.sh` 容器启动时后台拉起**（等价于 `docker exec -d`，且容器重启自愈） |
| 端口 | 1 个（20081→3000） | **2 个**：18081→8080（工具）、20081→3000（应用） |
| 前端 | 单个静态 HTML | **React 18 + Vite 5**（HashRouter，`base: './'`） |
| 探活 | 一个 `/api/health` | **可区分**：文件服务 `{ok,data}` / 应用 `{ok,app:true}` |
| 重启 | 容器重启后应用丢失 | **容器重启后应用自动回来** |

---

## 二、交付物

新增：

| 路径 | 说明 |
|---|---|
| `templates/node-unified/vite.config.js` | `base:'./'` + `root:client` + `outDir:../public/dist` |
| `templates/node-unified/samples/expense/client/index.html` | Vite 入口 |
| `.../client/src/main.jsx` | HashRouter 挂载 |
| `.../client/src/App.jsx` | 登录/注册、列表、新增、删除、越权探针 |
| `.../client/src/api.js` | 只使用相对 `/api` 的 fetch 封装 |
| `.../client/src/styles.css` | 深色主题 |
| `scripts/sandbox-up.sh` | 建库建账号 + 构建 + 起双通道容器 + 等待两个健康检查 |
| `scripts/sandbox-app.sh` | 应用进程 start/stop/restart/status/logs |
| `scripts/verify-cp2.sh` | 10 条拓扑/自愈断言 + 内嵌 CP1 的 16 条回归 |

修改：

| 路径 | 改动 |
|---|---|
| `templates/node-unified/package.json` | 增加 react/react-dom/react-router-dom/vite/@vitejs/plugin-react，新增 `build`/`dev` 脚本 |
| `templates/node-unified/runtime/config.js` | **`PORT` → `APP_PORT`**（关键修复，见第五节） |
| `scripts/build-sample.sh` | 复制 `client/` 与 `vite.config.js`，执行 `npm install` + `vite build` |
| `sandbox/start.sh` | 容器启动时若 `/workspace/server.js` 存在，后台拉起应用 |
| `sandbox/Dockerfile` | `ENTRYPOINT` 由 `node /app/app.js` 改为 `/app/start.sh` |
| `scripts/verify-cp1.sh` | 注释中的启动脚本名改为 `sandbox-up.sh` |
| `docs/status/CP1-验收报告.md` | 加历史注记，命令改为新脚本 |

删除：`scripts/demo-up.sh`、`scripts/demo-down.sh`（被 `sandbox-up.sh` / `sandbox-app.sh` 取代）。

---

## 三、自动验收输出

命令：`bash scripts/verify-cp2.sh`

```text
== CP2 验收 ==
   容器: code-sandbox-900001   工具:18081   应用:20081

[✅] 容器 code-sandbox-900001 在运行
[✅] 端口映射：8080→18081 且 3000→20081
[✅] 工具通道 /api/health → ok:true 且不带 app 标记（文件服务）
[✅] 应用通道 /api/health → app:true（生成应用）
[✅] PID 1 是沙箱文件服务（node /app/app.js）
[✅] 容器内恰有 1 个应用进程（node server.js）

-- 数据层回归（scripts/verify-cp1.sh）--
[✅] CP1 数据层 16 条断言全部通过
     通过 16 条

-- 容器重启自愈（模拟主服务/容器重启）--
[✅] docker restart 后应用自动恢复（start.sh 拉起）
[✅] 重启后数据未丢失（expenses: 8 → 8）
[✅] 重启后没有进程重复启动（仍为 1 个）

-- 进程归属 --
  PID  PPID CMD
    1     0 node /app/app.js
    9     1 node server.js
   说明：应用进程的父进程在容器内（PPID=1），宿主侧不存在任何 Process 句柄。

== 结果：10 通过 / 0 失败 ==
```

其余证据：

| 项 | 结果 |
|---|---|
| 容器内 Node | `v18.20.4` |
| 构建产物 | `public/dist` 176 KB：`index.html` + 2 个带 hash 的 assets |
| 资源引用 | `./assets/index-*.js`、`./assets/index-*.css` —— **相对路径**，可挂子路径 |
| 资源可达 | `GET /` 200、`GET /assets/*.js` 200、`GET /assets/*.css` 200 |
| 工具通道响应 | `{"ok":true,"data":{"status":"ok","workspace":"/workspace"}}` |
| 应用通道响应 | `{"ok":true,"app":true,"db":"up",...}` |

---

## 四、启动链（当前真实形态）

```
docker run code-sandbox:latest
   │  ENTRYPOINT = /app/start.sh
   ├─ if [ -f /workspace/server.js ]
   │     └─ nohup node server.js  →  :3000（应用通道）
   └─ exec node /app/app.js        →  :8080（工具通道，PID 1）
```

两个通道职责分离：

- **8080（工具）**：AI 读写文件 / 执行命令，返回 `{ok, data}`，**没有 `app` 字段**；
- **3000（应用）**：生成应用的页面与 `/api/**`，返回 `{ok, app:true}`。

探活靠 `app:true` 区分，不会再出现"以为探到了应用、其实探到文件服务"。

---

## 五、过程中修掉的 3 个问题

| 问题 | 现象 | 根因 | 修法 |
|---|---|---|---|
| **端口变量冲突** | 容器里应用起不来或文件服务异常 | 镜像烤死 `PORT`，应用也读 `PORT`，两个进程抢同一个变量 | 应用改用 `APP_PORT`；容器级 env 只注入 `APP_PORT` |
| **容器重启丢应用** | `docker restart` 后 20081 无响应 | `exec -d` 的进程随容器重启消失，且没有补拉起逻辑 | `start.sh` 启动时检测 `/workspace/server.js` 并后台拉起；`ENTRYPOINT` 改为 `start.sh` |
| **容器内 npm 不可用** | `npm ping` 超时 | 镜像烤死 `http_proxy=host.docker.internal:7897`，本机该代理未运行 | 容器内执行 npm 前 `env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY`；换 `registry.npmmirror.com` 实测可用 |

第 1 条是这一轮最有价值的发现：**两个进程共享容器 env，"端口"这种通用变量名一定会打架。** 后续凡是与沙箱文件服务可能撞名的变量（PORT/HOST/PATH），应用侧都要加前缀。

第 2 条说明：`docker exec -d` 只解决"主服务重启不丢"，**不解决"容器重启不丢"**。真正的"进程属容器"必须写进入口脚本，这是 CP2 额外补上的。

---

## 六、人工验收（同一链接）

打开 **<http://localhost:20081>**，流程与上一轮一致（React 版界面）：

| 步骤 | 操作 | 预期 |
|---|---|---|
| 1 | 注册 `a@demo.com / pass123456`（或复用已有账号） | 进入主界面，显示"当前用户 id" |
| 2 | 添加两条支出 | 列表出现，金额格式 `¥32.50` |
| 3 | 退出，注册 `b@demo.com` | 列表为空（数据隔离） |
| 4 | 越权检查填 A 的记录 id，点"尝试修改" | **HTTP 404** + ✅ |
| 5 | `docker restart code-sandbox-900001`，等 5 秒后刷新 | 页面仍可访问，数据仍在 |

第 5 步是这一轮新增的验收项。

---

## 七、已知限制（CP2 未覆盖）

1. **前端构建在宿主机执行**，不是容器内。原因：Vite 的 esbuild 是平台相关二进制，macOS 产物不能直接在 Linux 容器里用；真实链路（`BuildProjectTool`）在沙箱内构建，CP3 接线时需验证容器内 `npm install`（并处理镜像里烤死的代理）。
2. **子路径 `/{accessCode}/` 未验证**。`base:'./'` 已就绪，但要等 proxy 才能真实验证（CP4）。
3. **应用崩溃不会自动重启**：只做了"容器重启自愈"，没做进程级看护（属于 CP5 reconcile）。
4. **迁移仍只做加法**，删列/改类型会 fail-fast。
5. **镜像被重建**：`code-sandbox:latest` 的 ENTRYPOINT 已改为 `start.sh`。已确认对没有 `/workspace/server.js` 的旧项目无影响（只会走文件服务）。

---

## 八、下一步（CP3）

1. Java 侧：`SandboxProperties` 增加 `appContainerPort/appPortRangeStart`；`SandboxContainerManager` 增加应用端口映射与容器级 env 注入。
2. 新增 `SandboxAppLauncher`：不再持有 `Process`，只负责"确保容器 + 等两个健康检查"。
3. `AppController.previewApp` 返回可访问地址（先走主服务临时转发，不引入 proxy）。
4. 前端 `AppChatPage.vue` 改为使用后端返回值。
5. 启动平台（后端 8123 + 前端），从 UI 点"预览"完成端到端验收。

CP3 的验收物仍是三样：**能点的链接 + 一键脚本 + 这份格式的报告**，外加第一个"从平台 UI 进得去"的里程碑。
