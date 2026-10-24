package com.yupi.yuaicodemother.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.yupi.yuaicodemother.constant.AppConstant;
import com.yupi.yuaicodemother.deploy.DeployContainerManager;
import com.yupi.yuaicodemother.mapper.AppDeployMapper;
import com.yupi.yuaicodemother.model.entity.AppDeploy;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import com.yupi.yuaicodemother.sandbox.PreviewLifecycleService;
import com.yupi.yuaicodemother.sandbox.SandboxContainerManager;
import com.yupi.yuaicodemother.sandbox.SandboxProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

/**
 * 应用删除后的资源回收（P6）。
 * <p>
 * 删除应用不只是删数据库行：预览容器、部署容器/镜像、代码目录、预览库、生产库、
 * 路由（DB + Redis）都必须一起清理，否则会出现"应用已删但域名还能访问"的残留。
 * <p>
 * 每一步独立 try/catch：任何一步失败都只告警，不阻断应用删除（应用行是权威数据，
 * 残留资源可以人工清理或由后续脚本兜底）。
 */
@Slf4j
@Service
public class AppCleanupService {

    @Resource
    private AppRouteService appRouteService;

    @Resource
    private AppDeployMapper appDeployMapper;

    @Resource
    private SandboxContainerManager sandboxContainerManager;

    @Resource
    private DeployContainerManager deployContainerManager;

    @Resource
    private SandboxProperties sandboxProperties;

    @Resource
    private PreviewLifecycleService previewLifecycleService;

    /**
     * 回收一个应用的全部外部资源。
     *
     * @param appId       应用 ID
     * @param codeGenType 应用类型快照（应用行删掉后就查不到了，必须由调用方传入）
     * @param deployKey   部署标识快照；用于兜底删除"只剩 Redis、没有 DB 行"的部署路由
     */
    public void cleanupAfterDelete(Long appId, String codeGenType, String deployKey) {
        if (appId == null || appId <= 0) {
            return;
        }
        log.info("开始回收应用资源: appId={}, type={}", appId, codeGenType);
        step("访问路由", () -> {
            appRouteService.removeRoutesOfApp(appId);
            if (StrUtil.isNotBlank(deployKey)) {
                appRouteService.removeRouteByHost(appRouteService.deployHostOf(deployKey));
            }
        });
        step("预览容器", () -> sandboxContainerManager.stopContainer(appId));
        step("部署容器与镜像", () -> cleanupDeployments(appId));
        step("代码目录", () -> removeProjectDirs(appId));
        step("应用数据库", () -> dropDatabases(appId));
        step("预览生命周期状态", () -> previewLifecycleService.forget(appId));
        log.info("应用资源回收结束: appId={}", appId);
    }

    /**
     * 停/删该应用所有部署版本的容器与镜像，删快照目录，最后删 app_deploy 行。
     * 生产库不在这里删（由 {@link #dropDatabases} 统一处理）。
     */
    private void cleanupDeployments(Long appId) {
        List<AppDeploy> rows = appDeployMapper.selectListByQuery(
                QueryWrapper.create().eq("app_id", appId));
        for (AppDeploy row : rows) {
            if (StrUtil.isNotBlank(row.getContainerName())
                    && deployContainerManager.containerExists(row.getContainerName())) {
                deployContainerManager.removeContainer(row.getContainerName());
            }
            if (StrUtil.isNotBlank(row.getImage())) {
                deployContainerManager.removeImage(row.getImage());
            }
        }
        if (!rows.isEmpty()) {
            appDeployMapper.deleteByQuery(QueryWrapper.create().eq("app_id", appId));
        }
        Path deployRoot = Paths.get(AppConstant.CODE_DEPLOY_ROOT_DIR, String.valueOf(appId));
        if (FileUtil.exist(deployRoot.toFile())) {
            FileUtil.del(deployRoot.toFile());
        }
    }

    /**
     * 删除所有可能的历史目录：{type}_{appId}。
     * <p>
     * 不能只按当前 codeGenType 删：应用改过类型（或历史 bug 写错目录）时会留下别的类型目录。
     * 目录名必须精确等于 {@code value_appId}，避免 appId 是另一个 ID 的前缀时误删。
     */
    private void removeProjectDirs(Long appId) {
        for (CodeGenTypeEnum type : CodeGenTypeEnum.values()) {
            String dirName = type.getValue() + "_" + appId;
            deleteDirIfExists(Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, dirName), dirName);
            if (!StrUtil.equals(AppConstant.CODE_OUTPUT_ROOT_DIR, sandboxProperties.getWorkspaceHostDir())) {
                deleteDirIfExists(Paths.get(sandboxProperties.getWorkspaceHostDir(), dirName), dirName);
            }
        }
    }

    private void deleteDirIfExists(Path dir, String expectedName) {
        if (!dir.getFileName().toString().equals(expectedName)) {
            log.error("拒绝删除非法目录（名称不匹配）: {} != {}", dir, expectedName);
            return;
        }
        if (FileUtil.exist(dir.toFile())) {
            FileUtil.del(dir.toFile());
            log.info("已删除应用目录: {}", dir);
        }
    }

    /**
     * 删预览库 app_{appId} 与生产库 app_{appId}_prod，并删除对应的独立账号。
     * <p>
     * 只接受纯数字 appId 拼出的库名，杜绝注入；任何一步失败只告警。
     */
    private void dropDatabases(Long appId) {
        if (appId <= 0) {
            return;
        }
        String previewDb = "app_" + appId;
        String prodDb = "app_" + appId + "_prod";
        String url = "jdbc:mysql://" + sandboxProperties.getHost() + ":" + sandboxProperties.getMysqlPort()
                + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        try (Connection conn = DriverManager.getConnection(url,
                sandboxProperties.getMysqlUser(), sandboxProperties.getMysqlPassword());
             Statement stmt = conn.createStatement()) {
            for (String db : List.of(previewDb, prodDb)) {
                stmt.executeUpdate("DROP DATABASE IF EXISTS `" + db + "`");
                stmt.executeUpdate("DROP USER IF EXISTS '" + db + "'@'%'");
                log.info("已删除应用数据库与账号: {}", db);
            }
        } catch (Exception e) {
            log.warn("删除应用数据库失败（可人工清理）: appId={}, err={}", appId, e.getMessage());
        }
    }

    private void step(String name, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("回收{}失败: {}", name, e.getMessage());
        }
    }
}
