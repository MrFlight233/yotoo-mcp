package com.yotoo.mcp.service;

import com.yotoo.mcp.bean.ApiDef;
import com.yotoo.mcp.bean.ApiParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 实际调用外部 API 的逻辑。
 * 使用 RestTemplate 实现 HTTP 请求。
 */
@Service
public class ApiInvoker {

    @Value("${spring.ai.mcp.simulate-invoke}")
    private boolean SIMULATE_INVOKE;

    @Value("${api.gateway}")
    private String API_GATEWAY;

    private final RestTemplate restTemplate = new RestTemplate();

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
            String method = requestWay.toUpperCase();
            String apiGateway = Objects.requireNonNullElse(API_GATEWAY, "");
            String url = "http://" + apiGateway + apiPath;
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
                return response.getBody();
            } else {
                return "API调用失败，状态码: " + response.getStatusCode();
            }
        } catch (Exception e) {
            System.err.println("API调用异常: " + e.getMessage());
            e.printStackTrace();
            return "API调用异常: " + e.getMessage();
        }
    }
}