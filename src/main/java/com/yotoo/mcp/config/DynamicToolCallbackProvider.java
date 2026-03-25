package com.yotoo.mcp.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yotoo.mcp.bean.ApiBean;
import com.yotoo.mcp.cache.ApiBeanCache;
import com.yotoo.mcp.service.ApiInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 动态 MCP 工具提供者。
 * 基于 ApiBean 列表动态生成 MCP 工具
 */
@Component
public class DynamicToolCallbackProvider implements ToolCallbackProvider {

    Logger logger = LoggerFactory.getLogger(DynamicToolCallbackProvider.class);

    private final ApiInvoker apiInvoker;
    private final ObjectMapper objectMapper;

    private volatile ToolCallback[] customTools;

    private final ApiBeanCache apiBeanCache;

    /**
     * 构造函数，注入所需依赖。
     */
    public DynamicToolCallbackProvider(ApiInvoker apiInvoker,
                                       ObjectMapper objectMapper, ApiBeanCache apiBeanCache) {
        this.apiInvoker = apiInvoker;
        this.objectMapper = objectMapper;
        this.apiBeanCache = apiBeanCache;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        List<ToolCallback> list = new ArrayList<>();
        buildCustomTools();
        if (customTools != null && customTools.length > 0) {
            list.addAll(List.of(customTools));
        }
        return list.toArray(new ToolCallback[0]);
    }

    /**
     * 构建所有工具的回调数组。
     */
    private void buildCustomTools() {
        if (!CollectionUtils.isEmpty(apiBeanCache.apiBeanList)) {
            this.customTools = apiBeanCache.apiBeanList.stream()
                    .map(this::buildCustomTool)
                    .toArray(ToolCallback[]::new);
        }
    }

    /**
     * 为单个 ApiBean 构建工具回调。
     */
    private ToolCallback buildCustomTool(ApiBean api) {
        // 1. 构建输入参数的 JSON Schema
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        // 收集必填参数名称
        List<String> required = api.getParameters().stream()
                .filter(ApiBean.Parameter::isRequired)
                .map(ApiBean.Parameter::getName)
                .toList();

        // 填充 properties
        for (ApiBean.Parameter param : api.getParameters()) {
            ObjectNode prop = properties.putObject(param.getName());
            prop.put("type", param.getType());
            if (param.getDescription() != null) {
                prop.put("description", param.getDescription());
            }
        }

        // 设置 required 数组（如果有必填参数）
        if (!required.isEmpty()) {
            ArrayNode requiredArray = schema.putArray("required");
            required.forEach(requiredArray::add); // 逐个添加字符串
        }

        // 将 ObjectNode 转换为 JSON 字符串
        String schemaJson;
        try {
            schemaJson = objectMapper.writeValueAsString(schema);
            logger.info("API解析成功！{}：{}", api.getName(), schemaJson);
        } catch (JsonProcessingException e) {
            logger.error("API解析失败！{}", api.getName(), e);
            throw new RuntimeException("API解析失败！" + api.getName(), e);
        }

        // 2. 定义工具执行函数（调用 ApiInvoker）
        Function<Map<String, Object>, String> executeFunc = (args) -> apiInvoker.invoke(api, args);

        // 3. 使用 FunctionToolCallback 创建工具
        return FunctionToolCallback.builder(api.getName(), executeFunc)
                .description(api.getDescription())
                .inputSchema(schemaJson)          // 传入 JSON 字符串
                .inputType(Map.class)             // 输入类型为 Map
                .build();
    }
}
