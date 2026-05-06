package com.yotoo.mcp.constant;

/**
 * 工具调用返回信封上的 {@code uiKind} 取值：唯一用于「何种 UI/交互」的机器标识。
 * 模型侧是否少说/勿报成功，由同级的 {@code message}、{@code data.aiNotice} 与工具 description 约束即可，无需再设平行字段。
 * <p>
 * 约定：与 {@code success}、{@code data} 同级出现在 JSON 最外层。
 */
public final class ToolResponseUiKind {

    private ToolResponseUiKind() {
    }

    /**
     * 默认：GET 成功、错误或普通 JSON 数据，前端按常规展示即可。
     */
    public static final String STANDARD = "standard";

    /**
     * 非 GET：未发起下游 HTTP；{@code data} 为 {@code { "jsonSchema": {...}, "aiNotice": "..." }}，
     * 前端用 {@code data.jsonSchema} 渲染表单即可。
     */
    public static final String JSON_SCHEMA_FORM = "json_schema_form";
}
