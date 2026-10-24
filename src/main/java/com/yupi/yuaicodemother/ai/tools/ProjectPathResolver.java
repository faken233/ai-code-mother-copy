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
 *
 * <p>目录名规则：{@code {codeGenType}_{appId}}，例如 {@code static_123} / {@code fullstack_123}。
 * 沙箱容器的 workspace 挂载、模板安装、下载打包全部走这里，保证"应用类型"与"磁盘目录"永远一致
 * （历史上两者不一致导致过产物写进别的类型目录、预览 404）。
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
     * 解析应用的代码生成类型；应用不存在时返回 null
     */
    public CodeGenTypeEnum resolveCodeGenType(Long appId) {
        App app = appId != null ? appService.getById(appId) : null;
        return app != null ? CodeGenTypeEnum.getEnumByValue(app.getCodeGenType()) : null;
    }

    /**
     * 解析项目目录名（如 static_123 / fullstack_123）
     *
     * @param appId 应用 ID
     * @return 项目目录名
     */
    public String resolveProjectDirName(Long appId) {
        CodeGenTypeEnum codeGenType = resolveCodeGenType(appId);
        String dirType = codeGenType != null ? codeGenType.getValue() : CodeGenTypeEnum.STATIC.getValue();
        return dirType + "_" + appId;
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
