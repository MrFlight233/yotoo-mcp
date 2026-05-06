package com.yotoo.mcp.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yotoo.mcp.bean.ApiDef;
import com.yotoo.mcp.bean.ApiParam;
import com.yotoo.mcp.cache.ApiBeanCache;
import com.yotoo.mcp.service.ApiInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 动态 MCP 工具提供者。
 * 基于 ApiDef + ApiParam 列表动态生成 MCP 工具
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
    @SuppressWarnings("null")
    public @NonNull ToolCallback[] getToolCallbacks() {
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
        if (!CollectionUtils.isEmpty(apiBeanCache.apiDefList)) {
            this.customTools = apiBeanCache.apiDefList.stream()
                    .map(this::buildCustomTool)
                    .toArray(ToolCallback[]::new);
        }
    }

    /**
     * 为单个 ApiDef 构建工具回调。
     */
    private ToolCallback buildCustomTool(ApiDef apiDef) {
        List<ApiParam> apiParams = Objects.requireNonNullElse(apiDef.getApiParams(), Collections.emptyList());
        String operationId = Objects.requireNonNullElse(apiDef.getOperationId(), "unknown_operation");
        String summary = buildToolDescription(apiDef);
        // 1. 构建输入参数的 JSON Schema
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("title", Objects.requireNonNullElse(apiDef.getApiName(), operationId));
        if (apiDef.getSummary() != null) {
            schema.put("description", apiDef.getSummary());
        }
        ObjectNode properties = schema.putObject("properties");

        // 收集必填参数名称
        Set<String> required = apiParams.stream()
                .filter(param -> isRequired(param.getRequired()))
                .map(ApiParam::getParamName)
                .filter(Objects::nonNull)
                .filter(name -> !name.isBlank())
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);

        // 填充 properties
        apiParams.stream()
                .filter(param -> param.getParamName() != null && !param.getParamName().isBlank())
                .forEach(param -> {
                    ObjectNode prop = properties.putObject(param.getParamName());
                    prop.put("type", normalizeJsonType(param.getParamDataType()));
                    if (param.getParamDescription() != null) {
                        prop.put("description", param.getParamDescription());
                    }
                    if (param.getParamEnum() != null && !param.getParamEnum().isBlank()) {
                        ArrayNode enumArray = prop.putArray("enum");
                        Arrays.stream(param.getParamEnum().split(","))
                                .map(String::trim)
                                .filter(value -> !value.isEmpty())
                                .forEach(enumArray::add);
                    }
                });

        // 设置 required 数组（保持与落库结构一致，即使为空也输出）
        ArrayNode requiredArray = schema.putArray("required");
        required.forEach(requiredArray::add);

        // 将 ObjectNode 转换为 JSON 字符串
        String schemaJson = "{}";
        try {
            schemaJson = objectMapper.writeValueAsString(schema);
            logger.info("API解析成功！{}：{}", operationId, schemaJson);
        } catch (JsonProcessingException e) {
            logger.error("API解析失败！{}", operationId, e);
            throw new RuntimeException("API解析失败！" + operationId, e);
        }

        // 2. 定义工具执行函数（调用 ApiInvoker）
        Function<Map<String, Object>, String> executeFunc = (args) -> apiInvoker.invoke(apiDef, apiParams, args);

        // 3. 使用 FunctionToolCallback 创建工具
        return FunctionToolCallback.builder(Objects.requireNonNull(operationId), executeFunc)
                .description(Objects.requireNonNull(summary))
                .inputSchema(Objects.requireNonNull(schemaJson))          // 传入 JSON 字符串
                .inputType(Map.class)             // 输入类型为 Map
                .build();
    }

    private boolean isRequired(String required) {
        if (required == null) {
            return false;
        }
        String normalized = required.trim().toLowerCase();
        return "true".equals(normalized)
                || "1".equals(normalized)
                || "yes".equals(normalized)
                || "y".equals(normalized);
    }

    private String normalizeJsonType(String type) {
        if (type == null || type.isBlank()) {
            return "string";
        }
        return switch (type.toLowerCase()) {
            case "string", "number", "integer", "boolean", "array", "object" -> type.toLowerCase();
            default -> "string";
        };
    }

    private String buildToolDescription(ApiDef apiDef) {
        String summary = Objects.requireNonNullElse(apiDef.getSummary(), "");
        String apiPath = Objects.requireNonNullElse(apiDef.getApiPath(), "");
        String requestWay = Objects.requireNonNullElse(apiDef.getRequestWay(), "");
        List<ApiParam> apiParams = Objects.requireNonNullElse(apiDef.getApiParams(), Collections.emptyList());
        Integer authType = apiDef.getAuthType();
        Integer apiType = apiDef.getApiType();
        String apiName = Objects.requireNonNullElse(apiDef.getApiName(), "");

        StringBuilder builder = new StringBuilder(summary);
        if (!apiName.isBlank()) {
            builder.append("\n名称: ").append(apiName);
        }
        if (!requestWay.isBlank() || !apiPath.isBlank()) {
            builder.append("\n请求: ").append(requestWay).append(" ").append(apiPath);
        }
        if (authType != null) {
            builder.append("\n鉴权类型: ").append(authType);
        }
        if (apiType != null) {
            builder.append("\n接口类型: ").append(apiType);
        }
        if (!apiParams.isEmpty()) {
            builder.append("\n参数:");
            for (ApiParam param : apiParams) {
                String paramName = Objects.requireNonNullElse(param.getParamName(), "");
                if (paramName.isBlank()) {
                    continue;
                }
                String paramDesc = Objects.requireNonNullElse(param.getParamDescription(), "");
                String testValue = Objects.requireNonNullElse(param.getTestValue(), "");
                String dataType = normalizeJsonType(param.getParamDataType());
                boolean required = isRequired(param.getRequired());

                builder.append("\n- ")
                        .append(paramName)
                        .append(" | 类型: ").append(dataType)
                        .append(" | 必填: ").append(required ? "是" : "否");
                if (!paramDesc.isBlank()) {
                    builder.append(" | 描述: ").append(paramDesc);
                }
                if (!testValue.isBlank()) {
                    builder.append(" | 测试值: ").append(testValue);
                }
            }
        }
        if (!"GET".equalsIgnoreCase(requestWay)) {
            builder.append("\n\n【行为说明】本工具不会发起下游 HTTP，仅返回 JSON Schema（含当前参数 value）供界面渲染表单；实际提交由页面/网关完成。");
            builder.append("\n【输出约定】若返回 JSON 的 uiKind 为 \"json_schema_form\"：请勿向用户宣称业务已最终完成或已提交，可一句提示用户在界面中继续，或保持极简、不重复朗读工具 JSON。");
        }
        return builder.toString();
    }
}
