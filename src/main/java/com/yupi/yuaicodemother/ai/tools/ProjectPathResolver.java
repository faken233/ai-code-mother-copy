package com.yupi.yuaicodemother.ai.tools;

import com.yupi.yuaicodemother.constant.AppConstant;
import com.yupi.yuaicodemother.model.entity.App;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import com.yupi.yuaicodemother.service.AppService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 项目路径解析器
 * 根据 appId 查询 app 表取 codeGenType，映射到项目目录名。
 * 解决文件工具写死 "vue_project_" + appId 的问题，使其支持 FULLSTACK 等其他类型。
 *
 * <p>这里对 AppService 使用 @Lazy 注入，打断 Bean 依赖环：
 * 工具链路（fileTool/buildTool → toolManager → aiCodeGeneratorServiceFactory）与
 * appServiceImpl → aiCodeGeneratorFacade → factory 相互依赖，@Lazy 使解析延后到首次调用。
 */
@Slf4j
@Component
public class ProjectPathResolver {

    @Resource
    @Lazy
    private AppService appService;

    /**
     * 解析项目目录名（如 fullstack_123 / vue_project_123）
     *
     * @param appId 应用 ID
     * @return 项目目录名
     */
    public String resolveProjectDirName(Long appId) {
        App app = appId != null ? appService.getById(appId) : null;
        String codeGenType = app != null && app.getCodeGenType() != null
                ? app.getCodeGenType()
                : CodeGenTypeEnum.VUE_PROJECT.getValue();
        return codeGenType + "_" + appId;
    }

    /**
     * 解析项目根目录绝对路径
     *
     * @param appId 应用 ID
     * @return 项目根目录路径
     */
    public Path resolveProjectRoot(Long appId) {
        return Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, resolveProjectDirName(appId));
    }
}
