package com.yupi.yuaicodemother.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.yupi.yuaicodemother.constant.AppConstant;
import com.yupi.yuaicodemother.deploy.DeployContainerManager;
import com.yupi.yuaicodemother.deploy.DeployProperties;
import com.yupi.yuaicodemother.exception.BusinessException;
import com.yupi.yuaicodemother.exception.ErrorCode;
import com.yupi.yuaicodemother.exception.ThrowUtils;
import com.yupi.yuaicodemother.mapper.AppDeployMapper;
import com.yupi.yuaicodemother.mapper.GenTaskMapper;
import com.yupi.yuaicodemother.mapper.AppMapper;
import com.yupi.yuaicodemother.model.entity.App;
import com.yupi.yuaicodemother.model.entity.AppDeploy;
import com.yupi.yuaicodemother.model.entity.User;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import com.yupi.yuaicodemother.sandbox.SandboxClient;
import com.yupi.yuaicodemother.sandbox.SandboxProperties;
import com.yupi.yuaicodemother.ai.tools.ProjectPathResolver;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 容器化部署服务（P5）。
 * <p>
 * 一次部署的固定顺序（任一步失败都不能影响线上旧版本）：
 * <pre>
 * 快照（tmp/code_deploy/{appId}/v{n}）
 *   → docker build app-dep-{appId}:v{n}
 *   → docker run app-dep-{appId}-v{n}（只发布 3000，随机宿主端口）
 *   → 等 /api/health 命中 "app":true
 *   → 写 app_access_route（DEPLOY）与 Redis route:{key}.app.localhost（此刻切流量）
 *   → 停旧版本容器（保留镜像，供回滚）
 * </pre>
 * 数据库与预览隔离：fullstack 生产库为 {@code app_{appId}_prod}，凭据与 JWT 密钥存在
 * app_deploy 行里，回滚/重建容器时复用，避免用户掉线或数据连到预览库。
 */
@Slf4j
@Service
public class AppDeployService {

    public static final String STATUS_BUILDING = "BUILDING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_PRUNED = "PRUNED";

    private static final char[] KEY_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final int KEY_LENGTH = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Resource
    private AppMapper appMapper;

    @Resource
    private AppDeployMapper appDeployMapper;

    @Resource
    private GenTaskMapper genTaskMapper;

    @Resource
    private AppRouteService appRouteService;

    @Resource
    private DeployContainerManager deployContainerManager;

    @Resource
    private DeployProperties deployProperties;

    @Resource
    private SandboxProperties sandboxProperties;

    @Resource
    private ProjectPathResolver projectPathResolver;

    @Resource
    private SandboxClient sandboxClient;

    /** 同一个应用串行部署/回滚，避免两个版本同时切路由 */
    private final Map<Long, ReentrantLock> appLocks = new ConcurrentHashMap<>();

    /**
     * 部署应用：返回固定域名的部署地址（重复部署地址不变，内容更新）。
     */
    public String deploy(Long appId, User loginUser) {
        ReentrantLock lock = lockOf(appId);
        lock.lock();
        try {
            return doDeploy(appId, loginUser);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 回滚到上一个版本（或指定版本）：只切路由，生产库数据不动。
     */
    public String rollback(Long appId, Integer targetVersion, User loginUser) {
        ReentrantLock lock = lockOf(appId);
        lock.lock();
        try {
            return doRollback(appId, targetVersion, loginUser);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 部署信息（前端展示当前版本 / 可回滚版本用）。
     */
    public Map<String, Object> deployInfo(Long appId, User loginUser) {
        App app = requireDeployableApp(appId, loginUser);
        List<AppDeploy> versions = versionsDesc(appId);
        AppDeploy current = versions.stream()
                .filter(v -> STATUS_ACTIVE.equals(v.getStatus()))
                .findFirst()
                .orElse(null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("appId", appId);
        data.put("deployKey", app.getDeployKey());
        data.put("currentVersion", current == null ? null : current.getDeployVersion());
        data.put("deployUrl", current == null ? null : appRouteService.deployUrlOf(current.getHost()));
        data.put("versions", versions.stream().map(v -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("version", v.getDeployVersion());
            item.put("status", v.getStatus());
            item.put("image", v.getImage());
            item.put("containerName", v.getContainerName());
            item.put("createTime", v.getCreateTime());
            return item;
        }).toList());
        return data;
    }

    /**
     * 对账：当前 ACTIVE 部署容器的宿主端口如果变了（Docker 重启 / 手工重建），
     * 把 app_deploy.upstream 与部署路由一起刷新，避免线上域名 502。
     * <p>
     * 只处理"容器正在运行"的情况：容器没起来时不做任何改动，
     * 由 {@code --restart unless-stopped} 或人工重新部署恢复。
     */
    public void reconcileActiveDeployment(Long appId) {
        if (appId == null) {
            return;
        }
        AppDeploy active = appDeployMapper.selectOneByQuery(QueryWrapper.create()
                .eq("app_id", appId)
                .eq("status", STATUS_ACTIVE)
                .orderBy("deploy_version", false)
                .limit(1));
        if (active == null || StrUtil.isBlank(active.getContainerName())) {
            return;
        }
        String container = active.getContainerName();
        if (!deployContainerManager.containerRunning(container)) {
            log.warn("部署容器未运行，跳过对账: appId={}, container={}", appId, container);
            return;
        }
        String upstream;
        try {
            upstream = deployContainerManager.upstreamOf(container);
        } catch (Exception e) {
            log.warn("部署容器端口回读失败，跳过对账: appId={}, container={}, err={}",
                    appId, container, e.getMessage());
            return;
        }
        if (!upstream.equals(active.getUpstream())) {
            markStatus(active.getId(), STATUS_ACTIVE, upstream);
            log.info("部署上游已刷新: appId={}, version={}, upstream={}",
                    appId, active.getDeployVersion(), upstream);
        }
        appRouteService.refreshDeployUpstream(appId, upstream);
    }

    // ======================= 部署主流程 =======================

    private String doDeploy(Long appId, User loginUser) {
        ThrowUtils.throwIf(!deployProperties.isEnabled(), ErrorCode.OPERATION_ERROR, "容器化部署未启用");
        App app = requireDeployableApp(appId, loginUser);
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        ThrowUtils.throwIf(type == null || !type.isSandboxType(), ErrorCode.PARAMS_ERROR,
                "旧格式应用不支持部署，请重新生成");
        // 生成中快照可能只复制到一半的代码
        ThrowUtils.throwIf(genTaskMapper.countActiveTasksByApp(appId) > 0, ErrorCode.OPERATION_ERROR,
                "应用正在生成中，请等待生成结束再部署");

        Path projectRoot = projectPathResolver.resolveProjectRoot(appId);
        ThrowUtils.throwIf(!Files.isRegularFile(projectRoot.resolve("server.js")), ErrorCode.PARAMS_ERROR,
                "应用代码不完整（缺少 server.js），请先生成应用");

        // 1. 确保前端产物就绪：部署镜像直接带 public/dist，启动时不再跑 vite build
        ensureFrontendBuilt(appId, projectRoot);

        // 2. 判断是否需要数据库（schema.tables 为空 = 纯前端，不建库、不注入 DB_*）
        boolean hasTables = hasTables(projectRoot);

        // 3. 部署标识：首次生成后固定不变，重新部署不换域名
        String deployKey = ensureDeployKey(app);

        int version = nextVersion(appId);
        String image = deployContainerManager.imageOf(appId, version);
        String container = deployContainerManager.containerOf(appId, version);
        log.info("开始部署: appId={}, version={}, deployKey={}, hasTables={}", appId, version, deployKey, hasTables);

        // 4. 生产库（独立于预览库）：凭据跨版本稳定
        ProductionDb db = prepareProductionDb(appId, hasTables);

        // 5. 版本快照 + 镜像
        Path snapshotDir = snapshot(appId, version, projectRoot);
        deployContainerManager.buildImage(appId, version, snapshotDir);

        // 6. 启动容器（旧版本此时仍在服务，路由未切）
        AppDeploy row = insertBuildingRow(appId, version, image, container, deployKey, db);
        String upstream;
        try {
            upstream = deployContainerManager.runContainer(appId, version, buildEnv(db, hasTables));
            deployContainerManager.waitHealthy(upstream, deployProperties.getHealthTimeoutSeconds());
        } catch (Exception e) {
            String logs = deployContainerManager.containerExists(container)
                    ? deployContainerManager.logs(container, 80) : "";
            markStatus(row.getId(), STATUS_FAILED, null);
            deployContainerManager.removeContainer(container);
            deployContainerManager.removeImage(image);
            log.error("部署失败，已回滚到旧版本: appId={}, version={}, err={}", appId, version, e.getMessage());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "部署失败（旧版本未受影响）: " + e.getMessage()
                            + (StrUtil.isBlank(logs) ? "" : "\n容器日志:\n" + logs));
        }

        // 7. 切流量：写路由（DB + Redis）成功才认为新版本上线
        String url;
        try {
            url = appRouteService.ensureDeployRoute(appId, deployKey, upstream);
        } catch (Exception e) {
            markStatus(row.getId(), STATUS_FAILED, upstream);
            deployContainerManager.stopContainer(container);
            log.error("部署路由切换失败，旧版本继续服务: appId={}, version={}, err={}", appId, version, e.getMessage());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "部署路由切换失败（旧版本未受影响）: " + e.getMessage());
        }
        markStatus(row.getId(), STATUS_ACTIVE, upstream);
        markOthersInactive(appId, version);

        // 8. 更新应用部署信息
        App update = new App();
        update.setId(appId);
        update.setDeployKey(deployKey);
        update.setDeployedTime(LocalDateTime.now());
        appMapper.update(update, true);

        // 9. 旧版本停止（镜像与快照保留，供回滚），超出保留数量的清理
        stopOtherContainers(appId, version);
        pruneOldVersions(appId);

        log.info("部署成功: appId={}, version={}, url={}, upstream={}", appId, version, url, upstream);
        return url;
    }

    private String doRollback(Long appId, Integer targetVersion, User loginUser) {
        ThrowUtils.throwIf(!deployProperties.isEnabled(), ErrorCode.OPERATION_ERROR, "容器化部署未启用");
        App app = requireDeployableApp(appId, loginUser);
        ThrowUtils.throwIf(StrUtil.isBlank(app.getDeployKey()), ErrorCode.OPERATION_ERROR,
                "该应用还没有可用部署，请先部署一次");

        List<AppDeploy> versions = versionsDesc(appId);
        ThrowUtils.throwIf(versions.isEmpty(), ErrorCode.OPERATION_ERROR, "没有可回滚的历史版本");

        AppDeploy current = versions.stream()
                .filter(v -> STATUS_ACTIVE.equals(v.getStatus()))
                .findFirst()
                .orElse(null);

        AppDeploy target;
        if (targetVersion != null) {
            target = versions.stream()
                    .filter(v -> targetVersion.equals(v.getDeployVersion()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR,
                            "部署版本 v" + targetVersion + " 不存在"));
        } else {
            target = versions.stream()
                    .filter(v -> current == null || v.getDeployVersion() < current.getDeployVersion())
                    .filter(v -> !STATUS_PRUNED.equals(v.getStatus()))
                    .filter(v -> !STATUS_FAILED.equals(v.getStatus()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.OPERATION_ERROR,
                            "没有更早的可回滚版本"));
        }

        ThrowUtils.throwIf(STATUS_PRUNED.equals(target.getStatus()), ErrorCode.OPERATION_ERROR,
                "部署版本 v" + target.getDeployVersion() + " 的镜像已被清理，无法回滚");
        ThrowUtils.throwIf(!deployContainerManager.imageExists(target.getImage()), ErrorCode.OPERATION_ERROR,
                "部署镜像不存在: " + target.getImage());

        if (current != null && current.getDeployVersion().equals(target.getDeployVersion())) {
            return appRouteService.deployUrlOf(current.getHost());
        }

        log.info("开始回滚: appId={}, from=v{}, to=v{}", appId,
                current == null ? "?" : current.getDeployVersion(), target.getDeployVersion());

        String container = target.getContainerName();
        boolean startedByUs = false;
        String upstream;
        try {
            if (deployContainerManager.containerExists(container)) {
                if (!deployContainerManager.containerRunning(container)) {
                    upstream = deployContainerManager.startContainer(container);
                    startedByUs = true;
                } else {
                    upstream = deployContainerManager.upstreamOf(container);
                }
            } else {
                // 容器被手工删掉但镜像还在：按同一份 env 重建
                upstream = deployContainerManager.runContainer(appId, target.getDeployVersion(), buildEnvFromRow(target));
                startedByUs = true;
            }

            // docker start 后宿主端口可能变化，且应用可能因为进程没起来而不健康 → 重启一次再判
            if (!deployContainerManager.healthy(upstream)) {
                deployContainerManager.restartContainer(container);
                upstream = deployContainerManager.upstreamOf(container);
                startedByUs = true;
            }
            deployContainerManager.waitHealthy(upstream, deployProperties.getHealthTimeoutSeconds());
        } catch (Exception e) {
            if (startedByUs) {
                deployContainerManager.stopContainer(container);
            }
            log.error("回滚失败，当前版本继续服务: appId={}, target=v{}, err={}",
                    appId, target.getDeployVersion(), e.getMessage());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "回滚失败（当前版本未受影响）: " + e.getMessage());
        }

        String url = appRouteService.ensureDeployRoute(appId, app.getDeployKey(), upstream);

        markStatus(target.getId(), STATUS_ACTIVE, upstream);
        markOthersInactive(appId, target.getDeployVersion());
        App update = new App();
        update.setId(appId);
        update.setDeployedTime(LocalDateTime.now());
        appMapper.update(update, true);
        stopOtherContainers(appId, target.getDeployVersion());

        log.info("回滚成功: appId={}, version=v{}, url={}, upstream={}",
                appId, target.getDeployVersion(), url, upstream);
        return url;
    }

    // ======================= 前置校验 / 构建产物 =======================

    private App requireDeployableApp(Long appId, User loginUser) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 错误");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        App app = appMapper.selectOneById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限部署该应用");
        }
        return app;
    }

    /**
     * 部署镜像直接带 public/dist；缺失时先让沙箱构建一次（生成流程正常都会产出）。
     */
    private void ensureFrontendBuilt(Long appId, Path projectRoot) {
        Path index = projectRoot.resolve("public/dist/index.html");
        if (Files.isRegularFile(index)) {
            return;
        }
        if (!sandboxClient.isEnabled()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "前端构建产物缺失（public/dist/index.html），且沙箱未启用，无法构建");
        }
        log.warn("部署前缺少前端产物，触发沙箱构建: appId={}", appId);
        sandboxClient.rebuildApp(appId, true);
        if (!Files.isRegularFile(index)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "前端构建后仍未生成 public/dist/index.html");
        }
    }

    private boolean hasTables(Path projectRoot) {
        Path schemaPath = projectRoot.resolve("schema.json");
        if (!Files.isRegularFile(schemaPath)) {
            return false;
        }
        try {
            JSONObject schema = JSONUtil.readJSONObject(schemaPath.toFile(), StandardCharsets.UTF_8);
            JSONObject tables = schema.getJSONObject("tables");
            return tables != null && !tables.isEmpty();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "schema.json 解析失败: " + e.getMessage());
        }
    }

    // ======================= 部署标识 / 版本 =======================

    private String ensureDeployKey(App app) {
        if (StrUtil.isNotBlank(app.getDeployKey())) {
            return app.getDeployKey().toLowerCase(Locale.ROOT);
        }
        for (int i = 0; i < 10; i++) {
            String key = randomKey();
            long count = appMapper.selectCountByQuery(QueryWrapper.create().eq("deployKey", key));
            if (count == 0) {
                return key;
            }
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "无法分配部署标识，请稍后重试");
    }

    private String randomKey() {
        StringBuilder sb = new StringBuilder(KEY_LENGTH);
        for (int i = 0; i < KEY_LENGTH; i++) {
            sb.append(KEY_ALPHABET[RANDOM.nextInt(KEY_ALPHABET.length)]);
        }
        return sb.toString();
    }

    private int nextVersion(Long appId) {
        AppDeploy latest = appDeployMapper.selectOneByQuery(QueryWrapper.create()
                .eq("app_id", appId)
                .orderBy("deploy_version", false)
                .limit(1));
        return latest == null ? 1 : latest.getDeployVersion() + 1;
    }

    // ======================= 生产库（与预览隔离） =======================

    private ProductionDb prepareProductionDb(Long appId, boolean hasTables) {
        String dbName = "app_" + appId + "_prod";
        AppDeploy last = latestDeploy(appId);
        String jwtSecret = (last != null && StrUtil.isNotBlank(last.getJwtSecret()))
                ? last.getJwtSecret()
                : RandomUtil.randomString(32);

        if (!hasTables) {
            // 纯前端：不建库、不注入 DB_*，但 JWT 仍保持稳定，避免以后加存储时用户掉线
            return new ProductionDb(null, null, null, jwtSecret);
        }

        String dbUser = dbName;
        String dbPassword = (last != null && StrUtil.isNotBlank(last.getDbPassword())
                && dbName.equals(last.getDbName()))
                ? last.getDbPassword()
                : RandomUtil.randomString(24);
        provisionDatabase(dbName, dbUser, dbPassword);
        return new ProductionDb(dbName, dbUser, dbPassword, jwtSecret);
    }

    /**
     * 建生产库 + 独立账号（只授权本库）。账号密码跨版本复用，回滚时容器能连回同一个库。
     */
    private void provisionDatabase(String dbName, String dbUser, String dbPassword) {
        String url = "jdbc:mysql://" + sandboxProperties.getHost() + ":" + sandboxProperties.getMysqlPort()
                + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        try (Connection conn = DriverManager.getConnection(url,
                sandboxProperties.getMysqlUser(), sandboxProperties.getMysqlPassword());
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + dbName + "` DEFAULT CHARACTER SET utf8mb4");
            stmt.executeUpdate("CREATE USER IF NOT EXISTS '" + dbUser + "'@'%' IDENTIFIED BY '" + dbPassword + "'");
            stmt.executeUpdate("ALTER USER '" + dbUser + "'@'%' IDENTIFIED BY '" + dbPassword + "'");
            stmt.executeUpdate("GRANT ALL PRIVILEGES ON `" + dbName + "`.* TO '" + dbUser + "'@'%'");
            stmt.executeUpdate("FLUSH PRIVILEGES");
            log.info("生产库已就绪: {}（账号 {}，仅授权本库）", dbName, dbUser);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "创建生产数据库失败: " + e.getMessage());
        }
    }

    private AppDeploy latestDeploy(Long appId) {
        return appDeployMapper.selectOneByQuery(QueryWrapper.create()
                .eq("app_id", appId)
                .orderBy("deploy_version", false)
                .limit(1));
    }

    // ======================= 快照 / 镜像 =======================

    private Path snapshot(Long appId, int version, Path projectRoot) {
        Path snapshotDir = Paths.get(AppConstant.CODE_DEPLOY_ROOT_DIR, String.valueOf(appId), "v" + version);
        try {
            if (Files.exists(snapshotDir)) {
                FileUtil.del(snapshotDir.toFile());
            }
            Files.createDirectories(snapshotDir);
            copyProjectTree(projectRoot, snapshotDir);
            writeDockerfile(snapshotDir);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成部署快照失败: " + e.getMessage());
        }
        return snapshotDir;
    }

    /**
     * 复制项目树（含 node_modules，保证构建镜像不依赖网络）。
     * 只跳过版本控制/编辑器/缓存目录。
     */
    private void copyProjectTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(dir);
                if (shouldSkipDir(relative)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(relative.toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(file);
                if (shouldSkipFile(relative)) {
                    return FileVisitResult.CONTINUE;
                }
                Files.copy(file, target.resolve(relative.toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private boolean shouldSkipDir(Path relative) {
        String path = relative.toString().replace('\\', '/');
        if (path.isEmpty()) {
            return false;
        }
        if (".git".equals(path) || ".idea".equals(path) || ".vscode".equals(path)) {
            return true;
        }
        return path.startsWith("node_modules/.cache");
    }

    private boolean shouldSkipFile(Path relative) {
        String name = relative.getFileName().toString();
        return ".DS_Store".equals(name) || "Thumbs.db".equals(name);
    }

    private void writeDockerfile(Path snapshotDir) throws IOException {
        int appPort = sandboxProperties.getAppContainerPort();
        String dockerfile = """
                # 由平台自动生成：部署镜像（不可变，每个版本一个）
                FROM %s
                WORKDIR /app
                COPY . .
                # 快照通常已带 node_modules；缺失时兜底装运行时依赖（需要构建期能访问 npm registry）
                RUN if [ ! -d node_modules ]; then npm ci --omit=dev; fi
                ENV NODE_ENV=production
                ENV APP_PORT=%d
                EXPOSE %d
                USER node
                CMD ["node", "server.js"]
                """.formatted(deployProperties.getBaseImage(), appPort, appPort);
        Files.writeString(snapshotDir.resolve("Dockerfile"), dockerfile, StandardCharsets.UTF_8);
        Files.writeString(snapshotDir.resolve(".dockerignore"),
                ".git\n.DS_Store\nnode_modules/.cache\n", StandardCharsets.UTF_8);
    }

    // ======================= 容器环境 / 记录 =======================

    private Map<String, String> buildEnv(ProductionDb db, boolean hasTables) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("APP_PORT", String.valueOf(sandboxProperties.getAppContainerPort()));
        env.put("NODE_ENV", "production");
        env.put("JWT_SECRET", db.jwtSecret());
        if (hasTables && StrUtil.isNotBlank(db.database())) {
            env.put("APP_DB", db.database());
            env.put("DB_HOST", sandboxProperties.getMysqlHost());
            env.put("DB_PORT", String.valueOf(sandboxProperties.getMysqlPort()));
            env.put("DB_USER", db.username());
            env.put("DB_PASS", db.password());
        }
        return env;
    }

    private Map<String, String> buildEnvFromRow(AppDeploy row) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("APP_PORT", String.valueOf(sandboxProperties.getAppContainerPort()));
        env.put("NODE_ENV", "production");
        env.put("JWT_SECRET", StrUtil.blankToDefault(row.getJwtSecret(), "deploy-fallback-secret"));
        if (StrUtil.isNotBlank(row.getDbName())) {
            env.put("APP_DB", row.getDbName());
            env.put("DB_HOST", sandboxProperties.getMysqlHost());
            env.put("DB_PORT", String.valueOf(sandboxProperties.getMysqlPort()));
            env.put("DB_USER", row.getDbUser());
            env.put("DB_PASS", row.getDbPassword());
        }
        return env;
    }

    private AppDeploy insertBuildingRow(Long appId, int version, String image, String container,
                                        String deployKey, ProductionDb db) {
        LocalDateTime now = LocalDateTime.now();
        AppDeploy row = AppDeploy.builder()
                .appId(appId)
                .deployVersion(version)
                .image(image)
                .containerName(container)
                .host(appRouteService.deployHostOf(deployKey))
                .dbName(db.database())
                .dbUser(db.username())
                .dbPassword(db.password())
                .jwtSecret(db.jwtSecret())
                .status(STATUS_BUILDING)
                .createTime(now)
                .updateTime(now)
                .build();
        appDeployMapper.insert(row);
        return row;
    }

    private void markStatus(Long id, String status, String upstream) {
        AppDeploy update = new AppDeploy();
        update.setId(id);
        update.setStatus(status);
        update.setUpstream(upstream);
        update.setUpdateTime(LocalDateTime.now());
        appDeployMapper.update(update, true);
    }

    private void markOthersInactive(Long appId, int keepVersion) {
        AppDeploy update = new AppDeploy();
        update.setStatus(STATUS_INACTIVE);
        update.setUpdateTime(LocalDateTime.now());
        appDeployMapper.updateByQuery(update, true, QueryWrapper.create()
                .eq("app_id", appId)
                .ne("deploy_version", keepVersion)
                .ne("status", STATUS_PRUNED));
    }

    private List<AppDeploy> versionsDesc(Long appId) {
        return appDeployMapper.selectListByQuery(QueryWrapper.create()
                .eq("app_id", appId)
                .orderBy("deploy_version", false));
    }

    // ======================= 清理 =======================

    /**
     * 停掉除 keepVersion 以外的运行中容器。镜像保留，回滚时 docker start 即可。
     */
    private void stopOtherContainers(Long appId, int keepVersion) {
        for (AppDeploy row : versionsDesc(appId)) {
            if (row.getDeployVersion() == keepVersion || STATUS_PRUNED.equals(row.getStatus())) {
                continue;
            }
            if (deployContainerManager.containerExists(row.getContainerName())
                    && deployContainerManager.containerRunning(row.getContainerName())) {
                deployContainerManager.stopContainer(row.getContainerName());
            }
        }
    }

    /**
     * 只保留最近 N 个版本的容器/镜像/快照；DB 记录保留并标 PRUNED。
     */
    private void pruneOldVersions(Long appId) {
        List<AppDeploy> versions = versionsDesc(appId);
        int keep = Math.max(2, deployProperties.getKeepVersions());
        List<AppDeploy> stale = new ArrayList<>();
        for (int i = keep; i < versions.size(); i++) {
            stale.add(versions.get(i));
        }
        for (AppDeploy old : stale) {
            if (STATUS_PRUNED.equals(old.getStatus())) {
                continue;
            }
            deployContainerManager.removeContainer(old.getContainerName());
            deployContainerManager.removeImage(old.getImage());
            FileUtil.del(Paths.get(AppConstant.CODE_DEPLOY_ROOT_DIR, String.valueOf(appId),
                    "v" + old.getDeployVersion()).toFile());
            AppDeploy update = new AppDeploy();
            update.setId(old.getId());
            update.setStatus(STATUS_PRUNED);
            update.setUpdateTime(LocalDateTime.now());
            appDeployMapper.update(update, true);
            log.info("已清理旧部署版本: appId={}, version={}", appId, old.getDeployVersion());
        }
    }

    private ReentrantLock lockOf(Long appId) {
        return appLocks.computeIfAbsent(appId, k -> new ReentrantLock());
    }

    /**
     * 生产库凭据（static 模式 database/username/password 为 null）
     */
    private record ProductionDb(String database, String username, String password, String jwtSecret) {
    }
}
