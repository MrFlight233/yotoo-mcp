package com.yotoo.mcp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yotoo.mcp.bean.ApiDef;
import com.yotoo.mcp.bean.ApiParam;
import com.yotoo.mcp.cache.ApiBeanCache;
import io.modelcontextprotocol.server.McpSyncServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class McpToolService {
    private static final Logger logger = LoggerFactory.getLogger(McpToolService.class);

    /**
     * 用于把 Dify tools 字段反序列化为 List<Map<...>> 的类型引用。
     */
    private static final TypeReference<List<Map<String, Object>>> TOOL_LIST_TYPE = new TypeReference<>() {
    };

    private final McpSyncServer mcpServer;
    private final ApiBeanCache apiBeanCache;
    private final ObjectMapper objectMapper;
    private final String difyDbUrl;
    private final String difyDbUsername;
    private final String difyDbPassword;
    private final String difyToolsTable;
    private final String difyProviderId;

    /**
     * 构造函数：
     * 1) 注入 MCP 服务端对象（用于发送 tools/list_changed 通知）
     * 2) 注入 API 缓存与 JSON 工具
     * 3) 读取 Dify Postgres 连接和定位目标 provider 的配置
     */
    public McpToolService(McpSyncServer mcpServer,
            ApiBeanCache apiBeanCache,
            ObjectMapper objectMapper,
            @Value("${dify.mcp.db.url:}") String difyDbUrl,
            @Value("${dify.mcp.db.username:}") String difyDbUsername,
            @Value("${dify.mcp.db.password:}") String difyDbPassword,
            @Value("${dify.mcp.db.table:tool_mcp_providers}") String difyToolsTable,
            @Value("${dify.mcp.provider-id:}") String difyProviderId) {
        this.mcpServer = mcpServer;
        this.apiBeanCache = apiBeanCache;
        this.objectMapper = objectMapper;
        this.difyDbUrl = difyDbUrl;
        this.difyDbUsername = difyDbUsername;
        this.difyDbPassword = difyDbPassword;
        this.difyToolsTable = difyToolsTable;
        this.difyProviderId = difyProviderId;
    }

    /**
     * 刷新工具列表：清除缓存并通知所有已连接的 MCP 客户端
     */
    public void refreshTools() {
        // 发送工具列表变更通知
        if (mcpServer != null) {
            mcpServer.notifyToolsListChanged();
        }
    }

    /**
     * 直接操作 Dify 的 Postgres，将本项目当前缓存中的工具定义写入 tool_mcp_providers.tools。
     *
     * 执行流程：
     * 1. 校验必要配置（db url、provider id）以及本地 apiDef 缓存是否可用
     * 2. 查询目标 provider 当前 tools JSON
     * 3. 从旧 JSON 中推断 schema 字段命名（input_schema / inputSchema），保持兼容
     * 4. 基于 apiDefList 重新构建 tools JSON 列表
     * 5. 更新回数据库（jsonb）
     */
    public void updateDifyMcpTools() {
        if (difyDbUrl == null || difyDbUrl.isBlank()) {
            logger.warn("跳过Dify工具同步：未配置 dify.mcp.db.url");
            return;
        }
        if (difyProviderId == null || difyProviderId.isBlank()) {
            logger.warn("跳过Dify工具同步：未配置 dify.mcp.provider-id");
            return;
        }
        if (apiBeanCache.apiDefList == null || apiBeanCache.apiDefList.isEmpty()) {
            logger.warn("跳过Dify工具同步：apiDefList为空，请先刷新缓存");
            return;
        }

        // Dify 里该主键通常为 uuid，按 text 比较可避免 uuid=varchar 类型不匹配
        String selectSql = "SELECT tools FROM " + difyToolsTable + " WHERE id::text = ?";
        String updateSql = "UPDATE " + difyToolsTable + " SET tools = ?::jsonb, updated_at = ? WHERE id::text = ?";
        try (Connection connection = DriverManager.getConnection(difyDbUrl, difyDbUsername, difyDbPassword);
                PreparedStatement selectStmt = connection.prepareStatement(selectSql)) {
            // 按 provider_id 精确定位要更新的 MCP Provider 记录
            selectStmt.setString(1, difyProviderId);
            String existingToolsJson = null;
            try (ResultSet rs = selectStmt.executeQuery()) {
                if (rs.next()) {
                    existingToolsJson = rs.getString("tools");
                } else {
                    logger.warn("未找到id={} 对应的Dify MCP记录", difyProviderId);
                    return;
                }
            }

            // 读取旧结构并推断字段命名，避免新写入格式与 Dify 既有数据风格不一致
            List<Map<String, Object>> existingTools = parseExistingTools(existingToolsJson);
            String schemaFieldName = detectSchemaFieldName(existingTools);
            List<Map<String, Object>> newTools = buildDifyTools(schemaFieldName);
            if (logger.isDebugEnabled() && !newTools.isEmpty()) {
                try {
                    String firstToolJson = objectMapper.writeValueAsString(newTools.get(0));
                    logger.debug("写库前首个tool JSON id={}: {}", difyProviderId, firstToolJson);
                } catch (Exception e) {
                    logger.debug("写库前首个tool JSON序列化失败 id={}, 原因={}", difyProviderId, e.getMessage());
                }
            }
            String newToolsJson = objectMapper.writeValueAsString(newTools);

            try (PreparedStatement updateStmt = connection.prepareStatement(updateSql)) {
                // tools 字段是 jsonb，使用 ?::jsonb 强制转为 jsonb 写入
                updateStmt.setString(1, newToolsJson);
                updateStmt.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
                updateStmt.setString(3, difyProviderId);
                int affectedRows = updateStmt.executeUpdate();
                logger.info("Dify MCP工具列表同步完成 id={}, 更新行数={}, 工具数量={}",
                        difyProviderId, affectedRows, newTools.size());
            }
        } catch (Exception e) {
            logger.error("同步Dify MCP工具列表失败 id={}", difyProviderId, e);
        }
    }

    /**
     * 解析数据库里现有的 tools JSON。
     * 解析失败时返回空列表，并由后续逻辑使用默认字段名继续生成。
     */
    private List<Map<String, Object>> parseExistingTools(String existingToolsJson) {
        if (existingToolsJson == null || existingToolsJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(existingToolsJson, TOOL_LIST_TYPE);
        } catch (Exception e) {
            logger.warn("解析现有Dify tools失败，回退到默认格式。原因={}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 探测 Dify 当前 tools 中 schema 字段命名：
     * - 旧/蛇形风格：input_schema
     * - 驼峰风格：inputSchema
     * 若无法判断，默认使用 inputSchema。
     */
    private String detectSchemaFieldName(List<Map<String, Object>> existingTools) {
        if (!existingTools.isEmpty()) {
            Map<String, Object> first = existingTools.get(0);
            if (first.containsKey("input_schema")) {
                return "input_schema";
            }
            if (first.containsKey("inputSchema")) {
                return "inputSchema";
            }
        }
        return "inputSchema";
    }

    /**
     * 将当前 apiDefList 转换为 Dify tools 列表。
     * 每个 tool 最少包含：name / description / inputSchema(或input_schema)。
     */
    private List<Map<String, Object>> buildDifyTools(String schemaFieldName) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ApiDef apiDef : apiBeanCache.apiDefList) {
            String toolName = Objects.requireNonNullElse(apiDef.getOperationId(), "unknown_operation");
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", toolName);
            // Dify 期望 title 在 tool 顶层，位置在 name 与 description 之间
            tool.put("title", apiDef.getApiName());
            tool.put("description", buildToolDescription(apiDef));
            tool.put(schemaFieldName, buildInputSchema(apiDef));
            tools.add(tool);
        }
        return tools;
    }

    /**
     * 根据 ApiDef + ApiParam 构建 JSON Schema：
     * - type 固定 object
     * - properties 按参数展开
     * - required 按 required 标识收集
     */
    private Map<String, Object> buildInputSchema(ApiDef apiDef) {
        List<ApiParam> apiParams = Objects.requireNonNullElse(apiDef.getApiParams(), Collections.emptyList());
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        Set<String> requiredFields = new LinkedHashSet<>();

        for (ApiParam param : apiParams) {
            String paramName = param.getParamName();
            if (paramName == null || paramName.isBlank()) {
                continue;
            }
            if (isRequired(param.getRequired())) {
                requiredFields.add(paramName);
            }

            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", normalizeJsonType(param.getParamDataType()));
            if (param.getParamDescription() != null && !param.getParamDescription().isBlank()) {
                property.put("description", param.getParamDescription());
            }
            if (param.getParamEnum() != null && !param.getParamEnum().isBlank()) {
                List<String> enumValues = Arrays.stream(param.getParamEnum().split(","))
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .toList();
                if (!enumValues.isEmpty()) {
                    property.put("enum", enumValues);
                }
            }
            properties.put(paramName, property);
        }

        schema.put("properties", properties);
        if (!requiredFields.isEmpty()) {
            schema.put("required", new ArrayList<>(requiredFields));
        } else {
            schema.put("required", new ArrayList<>(0));
        }
        return schema;
    }

    /**
     * 组合工具描述：摘要 + 请求方式/路径，便于客户端展示和模型理解用途。
     */
    private String buildToolDescription(ApiDef apiDef) {
        String summary = Objects.requireNonNullElse(apiDef.getSummary(), "");
        String apiPath = Objects.requireNonNullElse(apiDef.getApiPath(), "");
        String requestWay = Objects.requireNonNullElse(apiDef.getRequestWay(), "");
        String apiName = Objects.requireNonNullElse(apiDef.getApiName(), "");
        StringBuilder builder = new StringBuilder(summary);
        if (!apiName.isBlank()) {
            builder.append("\n名称: ").append(apiName);
        }
        if (!requestWay.isBlank() || !apiPath.isBlank()) {
            builder.append("\n请求: ").append(requestWay).append(" ").append(apiPath);
        }
        List<ApiParam> apiParams = Objects.requireNonNullElse(apiDef.getApiParams(), Collections.emptyList());
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

    /**
     * 把多种“必填”表达统一归一化为布尔判断。
     */
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

    /**
     * 将参数类型归一化到 JSON Schema 支持的基础类型集合。
     */
    private String normalizeJsonType(String type) {
        if (type == null || type.isBlank()) {
            return "string";
        }
        return switch (type.toLowerCase()) {
            case "string", "number", "integer", "boolean", "array", "object" -> type.toLowerCase();
            default -> "string";
        };
    }
}
