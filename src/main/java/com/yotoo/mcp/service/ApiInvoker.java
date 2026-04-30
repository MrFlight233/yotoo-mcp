package com.yotoo.mcp.service;

import com.yotoo.mcp.bean.ApiDef;
import com.yotoo.mcp.bean.ApiParam;
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
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 实际调用外部 API 的逻辑。
 * 使用 RestTemplate 实现 HTTP 请求。
 */
@Service
public class ApiInvoker {
    private static final Logger logger = LoggerFactory.getLogger(ApiInvoker.class);

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

    public ApiInvoker(ApiDatabaseService apiDatabaseService, ObjectMapper objectMapper) {
        this.apiDatabaseService = apiDatabaseService;
        this.objectMapper = objectMapper;
        this.restTemplate = new RestTemplate();
    }

    public String invoke(ApiDef apiDef, List<ApiParam> apiParams, Map<String, Object> args) {
        List<ApiParam> safeParams = apiParams == null ? Collections.emptyList() : apiParams;
        String operationId = Objects.requireNonNullElse(apiDef.getOperationId(), "unknown_operation");
        String apiPath = Objects.requireNonNullElse(apiDef.getApiPath(), "");
        String requestWay = Objects.requireNonNullElse(apiDef.getRequestWay(), "GET");
        if (SIMULATE_INVOKE) {
            // 这里仅模拟返回结果，实际应构造 HTTP 请求
            System.out.println("调用 API: " + operationId);
            System.out.println("参数: " + args);
            System.out.println("URL: " + apiPath + ", 方法: " + requestWay);
            // 模拟响应
            return "模拟响应: 成功执行 " + operationId + ", 参数=" + args;
        }

        // 实际调用API
        try {
            configureRestTemplateTimeout();

            //更新api_def表的调用次数
            updateApiDefUsedCount(apiDef.getApiId(), apiDef.getOperationId());

            String method = requestWay.toUpperCase();
            String apiGateway = Objects.requireNonNullElse(API_GATEWAY, "");
            String url = "http://" + apiGateway + apiPath;
            if (apiPath.contains("http://") || apiPath.contains("https://")) {
                url = apiPath;
            }
            String token = null;

            // 处理URL中的路径参数（如 /user/{id}）
            for (Map.Entry<String, Object> entry : args.entrySet()) {
                String placeholder = "{" + entry.getKey() + "}";
                if (url.contains(placeholder)) {
                    url = url.replace(placeholder, String.valueOf(entry.getValue()));
                }
                if (entry.getKey().equals("token")) {
                    token = entry.getValue().toString();
                }
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (token != null)
                headers.setBearerAuth(token);

            HttpEntity<?> requestEntity;
            ResponseEntity<String> response;

            // 参数表默认按 query 参数处理（路径参数除外）
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(Objects.requireNonNull(url));
            for (ApiParam apiParam : safeParams) {
                String name = apiParam.getParamName();
                if (name == null || !args.containsKey(name)) {
                    continue;
                }
                String placeholder = "{" + name + "}";
                if (!apiPath.contains(placeholder)) {
                    builder.queryParam(name, args.get(name));
                }
            }
            String finalUrl = builder.build().toUriString();
            requestEntity = new HttpEntity<>(headers);
            HttpMethod httpMethod = HttpMethod.valueOf(Objects.requireNonNull(method));
            response = restTemplate.exchange(finalUrl, httpMethod, requestEntity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                return buildResponse(true, null, "调用成功", false, response.getStatusCode().value(), operationId, finalUrl, response.getBody());
            }
            return buildResponse(false, "UPSTREAM_HTTP_" + response.getStatusCode().value(),
                    "下游服务返回非成功状态码", isRetryable(response.getStatusCode().value()),
                    response.getStatusCode().value(), operationId, finalUrl, response.getBody());
        } catch (ResourceAccessException e) {
            Throwable root = rootCause(e);
            if (root instanceof ConnectException) {
                logger.warn("API连接失败，operationId={}, apiPath={}, error={}", operationId, apiPath, root.getMessage());
                return buildResponse(false, "UPSTREAM_CONNECT_ERROR", "下游服务连接失败，请确认目标服务是否可用",
                        true, 0, operationId, apiPath, null);
            }
            if (root instanceof SocketTimeoutException) {
                logger.warn("API调用超时，operationId={}, apiPath={}, error={}", operationId, apiPath, root.getMessage());
                return buildResponse(false, "UPSTREAM_TIMEOUT", "下游服务响应超时，请稍后重试",
                        true, 0, operationId, apiPath, null);
            }
            logger.error("API资源访问异常，operationId={}, apiPath={}", operationId, apiPath, e);
            return buildResponse(false, "UPSTREAM_ACCESS_ERROR", "下游服务访问异常",
                    true, 0, operationId, apiPath, e.getMessage());
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            String code = status >= 500 ? "UPSTREAM_HTTP_5XX" : "UPSTREAM_HTTP_4XX";
            String message = status >= 500 ? "下游服务异常，请稍后重试" : "下游服务请求失败，请检查参数或权限";
            logger.warn("API返回错误状态码，operationId={}, status={}", operationId, status);
            return buildResponse(false, code, message, isRetryable(status), status, operationId, apiPath, e.getResponseBodyAsString());
        } catch (Exception e) {
            logger.error("API调用异常，operationId={}, apiPath={}", operationId, apiPath, e);
            return buildResponse(false, "INTERNAL_ERROR", "服务内部异常，请稍后重试",
                    false, 500, operationId, apiPath, null);
        }
    }

    // 更新api_def表的调用次数
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

    private void configureRestTemplateTimeout() {
        if (!(restTemplate.getRequestFactory() instanceof SimpleClientHttpRequestFactory factory)) {
            return;
        }
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
    }

    private boolean isRetryable(int statusCode) {
        return statusCode == 408 || statusCode == 429 || statusCode >= 500;
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private String buildResponse(boolean success,
                                 String errorCode,
                                 String message,
                                 boolean retryable,
                                 int status,
                                 String operationId,
                                 String url,
                                 Object data) {
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
        result.put("data", data);
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"errorCode\":\"SERIALIZE_ERROR\",\"message\":\"返回结果序列化失败\"}";
        }
    }
}