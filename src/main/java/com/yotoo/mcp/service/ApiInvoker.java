package com.yotoo.mcp.service;

import com.yotoo.mcp.bean.ApiDef;
import com.yotoo.mcp.bean.ApiParam;
import com.yotoo.mcp.constant.ToolResponseUiKind;
import com.yotoo.mcp.util.ParamDataTypeSchema;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 调用外部 API：仅 {@code GET} 使用 RestTemplate 真实请求下游；
 * 非 {@code GET} 不发起 HTTP，只返回带 {@code value} 的 JSON Schema 供对话页渲染表单。
 */
@Service
public class ApiInvoker {
    private static final Logger logger = LoggerFactory.getLogger(ApiInvoker.class);

    /** 非 GET 仅返回表单结构、未调用下游时：避免模型误报「已提交成功」 */
    private static final String MESSAGE_FORM_AWAIT_USER =
            "未调用下游 HTTP，仅返回可编辑参数结构。请勿向用户宣称业务已提交完成；请提示用户在页面表单中确认或修改后提交，或保持极简、不重复朗读工具返回的 JSON。";

    @Value("${spring.ai.mcp.simulate-invoke}")
    private boolean SIMULATE_INVOKE;

    @Value("${api.gateway}")
    private String API_GATEWAY;

    @Value("${api.invoke.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${api.invoke.read-timeout-ms:10000}")
    private int readTimeoutMs;

    private final RestTemplate restTemplate;
    private final ApiDatabaseService apiDatabaseService;
    private final ObjectMapper objectMapper;

    /**
     * 依赖注入；{@link RestTemplate} 使用默认实现，连接/读取超时在每次真实 GET 前通过
     * {@link #configureRestTemplateTimeout()} 写入底层 {@link SimpleClientHttpRequestFactory}。
     */
    public ApiInvoker(ApiDatabaseService apiDatabaseService, ObjectMapper objectMapper) {
        this.apiDatabaseService = apiDatabaseService;
        this.objectMapper = objectMapper;
        this.restTemplate = new RestTemplate();
    }

    /**
     * 执行一次工具/API 调用并返回 JSON 字符串（信封含 success、message、uiKind、data 等）。
     * <p>分支概览：{@code spring.ai.mcp.simulate-invoke=true} 时只打日志且 GET 返回模拟文案；
     * 否则非 {@code GET} 不发起 HTTP，仅返回 {@link ToolResponseUiKind#JSON_SCHEMA_FORM}；
     * {@code GET} 则组 URL、带可选 Bearer，经 {@link RestTemplate} 请求下游并封装结果或错误。
     */
    public String invoke(ApiDef apiDef, List<ApiParam> apiParams, Map<String, Object> args) {
        List<ApiParam> safeParams = apiParams == null ? Collections.emptyList() : apiParams;
        Map<String, Object> safeArgs = args == null ? Collections.emptyMap() : args;
        String operationId = Objects.requireNonNullElse(apiDef.getOperationId(), "unknown_operation");
        String apiPath = Objects.requireNonNullElse(apiDef.getApiPath(), "");
        String requestWay = Objects.requireNonNullElse(apiDef.getRequestWay(), "GET");
        // 模拟模式：全程不发起真实 HTTP，仅日志 + 非 GET 表单结构 / GET 固定文案
        if (SIMULATE_INVOKE) {
            System.out.println("调用 API: " + operationId);
            System.out.println("参数: " + safeArgs);
            System.out.println("URL: " + apiPath + ", 方法: " + requestWay);
            if (!"GET".equalsIgnoreCase(requestWay)) {
                return buildFormSchemaOnlyResponse(apiDef, safeParams, safeArgs, operationId, resolveDisplayUrl(apiPath, safeArgs));
            }
            return "模拟响应: 成功执行 " + operationId + ", 参数=" + safeArgs;
        }

        try {
            updateApiDefUsedCount(apiDef.getApiId(), apiDef.getOperationId());

            String method = requestWay.toUpperCase();
            // 非 GET：由 MCP 客户端用表单承接，此处只下发带 value 的 JSON Schema
            if (!"GET".equals(method)) {
                return buildFormSchemaOnlyResponse(apiDef, safeParams, safeArgs, operationId, resolveDisplayUrl(apiPath, safeArgs));
            }

            configureRestTemplateTimeout();

            // 默认拼接网关前缀；apiPath 若已是绝对 URL 则直接使用
            String apiGateway = Objects.requireNonNullElse(API_GATEWAY, "");
            String url = "http://" + apiGateway + apiPath;
            if (apiPath.contains("http://") || apiPath.contains("https://")) {
                url = apiPath;
            }
            String token = null;

            // 路径模板 {param} 替换；参数名 token 单独抽出作为 Authorization Bearer
            for (Map.Entry<String, Object> entry : safeArgs.entrySet()) {
                String placeholder = "{" + entry.getKey() + "}";
                if (url.contains(placeholder)) {
                    url = url.replace(placeholder, String.valueOf(entry.getValue()));
                }
                if (entry.getKey().equals("token") && entry.getValue() != null) {
                    token = entry.getValue().toString();
                }
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (token != null) {
                headers.setBearerAuth(token);
            }

            // 未写进路径模板的参数一律作为 query 追加（与路径占位符互斥，避免重复）
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(Objects.requireNonNull(url));
            for (ApiParam apiParam : safeParams) {
                String name = apiParam.getParamName();
                if (name == null || !safeArgs.containsKey(name)) {
                    continue;
                }
                String placeholder = "{" + name + "}";
                if (!apiPath.contains(placeholder)) {
                    builder.queryParam(name, safeArgs.get(name));
                }
            }
            String finalUrl = builder.build().toUriString();

            HttpEntity<?> requestEntity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(finalUrl, HttpMethod.GET, requestEntity, String.class);

            // 统一走 buildResponse，便于前端按 uiKind 与 retryable 处理
            if (response.getStatusCode().is2xxSuccessful()) {
                return buildResponse(true, null, "调用成功", false, response.getStatusCode().value(), operationId, finalUrl,
                        response.getBody(), ToolResponseUiKind.STANDARD);
            }
            return buildResponse(false, "UPSTREAM_HTTP_" + response.getStatusCode().value(),
                    "下游服务返回非成功状态码", isRetryable(response.getStatusCode().value()),
                    response.getStatusCode().value(), operationId, finalUrl, response.getBody(), ToolResponseUiKind.STANDARD);
        } catch (ResourceAccessException e) {
            // 连接失败、读超时等 IO 问题；与 HttpStatusCodeException（已收到 HTTP 状态）区分
            Throwable root = rootCause(e);
            if (root instanceof ConnectException) {
                logger.warn("API连接失败，operationId={}, apiPath={}, error={}", operationId, apiPath, root.getMessage());
                return buildResponse(false, "UPSTREAM_CONNECT_ERROR", "下游服务连接失败，请确认目标服务是否可用",
                        true, 0, operationId, apiPath, null, ToolResponseUiKind.STANDARD);
            }
            if (root instanceof SocketTimeoutException) {
                logger.warn("API调用超时，operationId={}, apiPath={}, error={}", operationId, apiPath, root.getMessage());
                return buildResponse(false, "UPSTREAM_TIMEOUT", "下游服务响应超时，请稍后重试",
                        true, 0, operationId, apiPath, null, ToolResponseUiKind.STANDARD);
            }
            logger.error("API资源访问异常，operationId={}, apiPath={}", operationId, apiPath, e);
            return buildResponse(false, "UPSTREAM_ACCESS_ERROR", "下游服务访问异常",
                    true, 0, operationId, apiPath, e.getMessage(), ToolResponseUiKind.STANDARD);
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            String code = status >= 500 ? "UPSTREAM_HTTP_5XX" : "UPSTREAM_HTTP_4XX";
            String message = status >= 500 ? "下游服务异常，请稍后重试" : "下游服务请求失败，请检查参数或权限";
            logger.warn("API返回错误状态码，operationId={}, status={}", operationId, status);
            return buildResponse(false, code, message, isRetryable(status), status, operationId, apiPath, e.getResponseBodyAsString(), ToolResponseUiKind.STANDARD);
        } catch (Exception e) {
            logger.error("API调用异常，operationId={}, apiPath={}", operationId, apiPath, e);
            return buildResponse(false, "INTERNAL_ERROR", "服务内部异常，请稍后重试",
                    false, 500, operationId, apiPath, null, ToolResponseUiKind.STANDARD);
        }
    }

    /** 异步统计用：递增 api_def.used_count，失败只打日志不影响主调用。 */
    private void updateApiDefUsedCount(Integer apiId, String operationId) {
        if (apiId == null) {
            logger.warn("更新调用次数失败：apiId为空，operationId={}", operationId);
            return;
        }
        try {
            int updatedRows = apiDatabaseService.incrementApiDefUsedCount(apiId);
            if (updatedRows <= 0) {
                logger.warn("更新调用次数失败：未匹配到API定义，apiId={}, operationId={}", apiId, operationId);
            }
        } catch (Exception e) {
            logger.error("更新调用次数异常，apiId={}, operationId={}", apiId, operationId, e);
        }
    }

    /** 与 {@code api.invoke.connect-timeout-ms} / {@code read-timeout-ms} 对齐；仅当工厂类型匹配时生效。 */
    private void configureRestTemplateTimeout() {
        if (!(restTemplate.getRequestFactory() instanceof SimpleClientHttpRequestFactory factory)) {
            return;
        }
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
    }

    /** 408/429/5xx 视为可重试，供调用方展示或策略使用。 */
    private boolean isRetryable(int statusCode) {
        return statusCode == 408 || statusCode == 429 || statusCode >= 500;
    }

    /** 展开 cause 链末端，用于从 Spring 包装异常中识别底层 Socket 异常。 */
    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /** 仅用于响应中展示目标地址（路径占位符已替换），非 GET 不会据此发起请求。 */
    private String resolveDisplayUrl(String apiPath, Map<String, Object> safeArgs) {
        String path = Objects.requireNonNullElse(apiPath, "");
        String url = "http://" + Objects.requireNonNullElse(API_GATEWAY, "") + path;
        if (path.contains("http://") || path.contains("https://")) {
            url = path;
        }
        for (Map.Entry<String, Object> entry : safeArgs.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            if (url.contains(placeholder)) {
                url = url.replace(placeholder, String.valueOf(entry.getValue()));
            }
        }
        return url;
    }

    /**
     * 非 GET：不调用下游；{@code data} 仅为两层：{@code jsonSchema}（含各字段 {@code value}）与 {@code aiNotice}。
     * 是否发过 HTTP 由信封上 {@link ToolResponseUiKind#JSON_SCHEMA_FORM} 即可判断，无需再在 {@code data} 里冗余字段。
     */
    private String buildFormSchemaOnlyResponse(ApiDef apiDef, List<ApiParam> safeParams, Map<String, Object> safeArgs,
                                               String operationId, String displayUrl) {
        String paramSchemaForAi = buildParamJsonSchemaWithValuesForAi(apiDef, safeParams, safeArgs);
        Object dataPayload;
        try {
            // 解析为 Map 以便 buildResponse 将 data 作为对象嵌入总 JSON
            dataPayload = objectMapper.readValue(paramSchemaForAi, Map.class);
        } catch (JsonProcessingException e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("jsonSchema", Map.of());
            fallback.put("aiNotice", paramSchemaForAi);
            dataPayload = fallback;
        }
        return buildResponse(true, null, MESSAGE_FORM_AWAIT_USER, false, 200, operationId, displayUrl, dataPayload,
                ToolResponseUiKind.JSON_SCHEMA_FORM);
    }

    /**
     * @param uiKind 见 {@link ToolResponseUiKind}：前端分支渲染；模型话术配合 {@code message} 与工具说明即可，无需第二套枚举。
     */
    private String buildResponse(boolean success,
                                 String errorCode,
                                 String message,
                                 boolean retryable,
                                 int status,
                                 String operationId,
                                 String url,
                                 Object data,
                                 String uiKind) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("errorCode", errorCode);
        result.put("message", message);
        result.put("retryable", retryable);
        result.put("status", status);
        result.put("operationId", operationId);
        result.put("url", url);
        result.put("requestId", UUID.randomUUID().toString());
        result.put("timestamp", OffsetDateTime.now().toString());
        result.put("uiKind", uiKind != null ? uiKind : ToolResponseUiKind.STANDARD);
        result.put("uiKindVersion", 1);
        result.put("data", data);
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"errorCode\":\"SERIALIZE_ERROR\",\"message\":\"返回结果序列化失败\",\"uiKind\":\""
                    + ToolResponseUiKind.STANDARD + "\",\"uiKindVersion\":1}";
        }
    }

    /**
     * 将参数按 API 定义转为附带 value 的 JSON Schema，并附带给模型的说明。
     * 模型侧应将 jsonSchema 作为结构化快照原样保留或按约定回传，勿随意改写字段名或类型语义。
     */
    private String buildParamJsonSchemaWithValuesForAi(ApiDef apiDef, List<ApiParam> apiParams, Map<String, Object> args) {
        Map<String, Object> jsonSchema = new LinkedHashMap<>();
        jsonSchema.put("type", "object");
        if (apiDef.getOperationId() != null && !apiDef.getOperationId().isBlank()) {
            jsonSchema.put("title", apiDef.getOperationId());
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> requiredFields = new ArrayList<>();

        for (ApiParam param : apiParams) {
            String paramName = param.getParamName();
            if (paramName == null || paramName.isBlank()) {
                continue;
            }
            if (isRequired(param.getRequired())) {
                requiredFields.add(paramName);
            }
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", ParamDataTypeSchema.jsonSchemaType(param.getParamDataType()));
            String schemaFormat = ParamDataTypeSchema.jsonSchemaFormat(param.getParamDataType());
            if (schemaFormat != null) {
                property.put("format", schemaFormat);
            }
            String schemaPattern = ParamDataTypeSchema.jsonSchemaPattern(param.getParamDataType());
            if (schemaPattern != null) {
                property.put("pattern", schemaPattern);
            }
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
            property.put("value", args.get(paramName));
            properties.put(paramName, property);
        }

        jsonSchema.put("properties", properties);
        jsonSchema.put("required", requiredFields.isEmpty() ? new ArrayList<String>() : new ArrayList<>(requiredFields));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("jsonSchema", jsonSchema);
        root.put("aiNotice", "上述 jsonSchema 为本次调用参数的结构化快照（含 value）。用户需要后续在表单中继续操作二次确认。不要将此结果返回给用户。");
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            logger.warn("序列化 paramJsonSchema 失败，operationId={}", apiDef.getOperationId(), e);
            return "{\"jsonSchema\":{},\"aiNotice\":\"参数结构序列化失败，请检查参数定义。\"}";
        }
    }

    /** 兼容库表/配置里多种「必填」写法。 */
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

}