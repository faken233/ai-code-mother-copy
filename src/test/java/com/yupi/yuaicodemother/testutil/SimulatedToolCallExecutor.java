package com.yupi.yuaicodemother.testutil;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.yupi.yuaicodemother.ai.tools.BaseTool;
import com.yupi.yuaicodemother.ai.tools.ToolManager;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 模拟 AI 工具调用执行器。
 *
 * <p>真实链路中，langchain4j 框架流式接收 LLM 返回的 tool call 增量分片，
 * 聚合后由框架自动调用 @Tool 方法，并把执行结果回传 LLM。
 *
 * <p>本执行器等价于"一次性拿到完整 tool call 序列"的情况：读取工作序列文件，
 * 按顺序反射调用真实的 @Tool 方法，实现「模拟 AI 输出」驱动核心链路
 * （沙箱写文件 → 编译 → 构建 → 下载）的测试目标。
 *
 * <p>差异说明：真实 AI 的 tool call 参数是流式增量到达、且工具结果会回传 LLM 影响后续决策
 * （自我修正闭环）。固定序列文件没有反馈闭环，但对确定性 todo 应用生成是合理等价。
 */
public class SimulatedToolCallExecutor {

    private final ToolManager toolManager;
    private final long appId;

    public SimulatedToolCallExecutor(ToolManager toolManager, long appId) {
        this.toolManager = toolManager;
        this.appId = appId;
    }

    /**
     * 执行工作序列文件（JSON Lines 格式，每行一个工具调用）。
     *
     * <pre>
     * {"tool":"writeFile","arguments":{"relativeFilePath":"backend/pom.xml","content":"..."}}
     * {"tool":"buildProject","arguments":{"scope":"backend","skipInstall":true}}
     * </pre>
     *
     * @param sequenceFile 序列文件路径
     * @return 每个工具调用的执行结果（工具方法返回的字符串）
     */
    public List<String> execute(Path sequenceFile) throws Exception {
        List<String> results = new ArrayList<>();
        for (String line : Files.readAllLines(sequenceFile)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#")) {
                continue;
            }
            JSONObject call = JSONUtil.parseObj(trimmed);
            String toolName = call.getStr("tool");
            JSONObject args = call.getJSONObject("arguments");
            results.add(executeCall(toolName, args));
        }
        return results;
    }

    /**
     * 执行单个工具调用：根据 toolName 找到 @Tool 方法，按参数名填充参数。
     */
    public String executeCall(String toolName, JSONObject args) throws Exception {
        BaseTool tool = toolManager.getTool(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("未知工具: " + toolName);
        }

        Method toolMethod = findToolMethod(tool, toolName);
        Parameter[] params = toolMethod.getParameters();
        Object[] methodArgs = new Object[params.length];

        for (int i = 0; i < params.length; i++) {
            Parameter p = params[i];
            // @ToolMemoryId 参数由执行器注入 appId
            if (p.isAnnotationPresent(ToolMemoryId.class)) {
                methodArgs[i] = appId;
                continue;
            }
            String paramName = p.getName();
            if (!args.containsKey(paramName)) {
                throw new IllegalArgumentException("工具 " + toolName + " 缺少参数: " + paramName);
            }
            methodArgs[i] = convert(args.get(paramName), p.getType());
        }

        return (String) toolMethod.invoke(tool, methodArgs);
    }

    /**
     * 在工具类中找到标注 @Tool 且方法名匹配的方法。
     */
    private Method findToolMethod(BaseTool tool, String toolName) {
        for (Method m : tool.getClass().getMethods()) {
            if (m.isAnnotationPresent(Tool.class) && m.getName().equals(toolName)) {
                return m;
            }
        }
        throw new IllegalArgumentException("工具 " + toolName + " 没有对应的 @Tool 方法");
    }

    /**
     * 将 JSON 值转换为方法参数类型。
     */
    private Object convert(Object value, Class<?> targetType) {
        if (value == null) {
            return null;
        }
        if (targetType == String.class) {
            return value.toString();
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return Boolean.parseBoolean(value.toString());
        }
        if (targetType == int.class || targetType == Integer.class) {
            return Integer.parseInt(value.toString());
        }
        if (targetType == long.class || targetType == Long.class) {
            return Long.parseLong(value.toString());
        }
        return value;
    }
}
