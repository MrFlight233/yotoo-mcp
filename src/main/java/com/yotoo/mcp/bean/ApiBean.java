package com.yotoo.mcp.bean;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ApiBean {
    private String name;               // 工具名称（唯一）
    private String description;        // 工具描述
    private String method;              // HTTP 方法 (GET, POST)
    private String url;                 // 请求 URL（可包含占位符，如 /user/{id}）
    private List<Parameter> parameters; // 参数列表

    // 嵌套参数定义
    @Data
    public static class Parameter {
        private String name;
        private String type;            // "string", "integer", "boolean" 等
        private boolean required;
        private String description;

        public Parameter() {
        }

        public Parameter(String name, String type, boolean required, String description) {
            this.name = name;
            this.type = type;
            this.required = required;
            this.description = description;
        }
    }

    public ApiBean() {
    }

    public ApiBean(String name, String description, String method, String url) {
        this.name = name;
        this.description = description;
        this.method = method;
        this.url = url;
        this.parameters = new ArrayList<>(0);
    }

    public ApiBean(String name, String description, String method, String url, List<Parameter> parameters) {
        this.name = name;
        this.description = description;
        this.method = method;
        this.url = url;
        this.parameters = parameters;
    }

}