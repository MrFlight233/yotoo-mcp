package com.yotoo.mcp.util;

/**
 * 将业务侧 {@code ApiParam.paramDataType} 映射为 JSON Schema 的 {@code type} / {@code format} / {@code pattern}。
 * <p>
 * 日期时间与业务侧 Jackson 约定一致：{@code yyyy-MM-dd}、{@code yyyy-MM-dd HH:mm:ss}，时区 {@link #TEMPORAL_TIMEZONE}。
 */
public final class ParamDataTypeSchema {

    /** 与 {@code @JsonFormat(pattern = "yyyy-MM-dd", timezone = "GMT+8")} 对齐 */
    public static final String JACKSON_DATE_PATTERN = "yyyy-MM-dd";

    /** 与 {@code @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")} 对齐 */
    public static final String JACKSON_DATETIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /** 与 {@link #JACKSON_DATETIME_PATTERN} 中时间部分一致 */
    public static final String JACKSON_TIME_PATTERN = "HH:mm:ss";

    public static final String TEMPORAL_TIMEZONE = "GMT+8";

    private ParamDataTypeSchema() {
    }

    /**
     * JSON Schema 的 {@code type} 字段；日期时间类在 Schema 中均为 string。
     */
    public static String jsonSchemaType(String paramDataType) {
        if (paramDataType == null || paramDataType.isBlank()) {
            return "string";
        }
        String t = paramDataType.trim().toLowerCase();
        return switch (t) {
            case "string", "number", "integer", "boolean", "array", "object" -> t;
            case "date", "time", "datetime" -> "string";
            default -> "string";
        };
    }

    /**
     * JSON Schema 的 {@code format}；{@code datetime} 使用标准 {@code date-time}。
     * 具体字面值仍以 {@link #JACKSON_DATETIME_PATTERN} 与 {@link #jsonSchemaPattern} 为准。
     */
    public static String jsonSchemaFormat(String paramDataType) {
        if (paramDataType == null || paramDataType.isBlank()) {
            return null;
        }
        return switch (paramDataType.trim().toLowerCase()) {
            case "date" -> "date";
            case "datetime" -> "date-time";
            default -> null;
        };
    }

    /**
     * JSON Schema {@code pattern}（与上述 Jackson pattern 一一对应）；非上述时间类型返回 {@code null}。
     */
    public static String jsonSchemaPattern(String paramDataType) {
        if (paramDataType == null || paramDataType.isBlank()) {
            return null;
        }
        return switch (paramDataType.trim().toLowerCase()) {
            case "date" -> "^\\d{4}-\\d{2}-\\d{2}$";
            case "time" -> "^\\d{2}:\\d{2}:\\d{2}$";
            case "datetime" -> "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$";
            default -> null;
        };
    }

    /**
     * 工具说明等展示用：保留业务类型名，并附带与 Jackson 一致的格式说明。
     */
    public static String displayTypeLabel(String paramDataType) {
        if (paramDataType == null || paramDataType.isBlank()) {
            return "string";
        }
        String t = paramDataType.trim().toLowerCase();
        return switch (t) {
            case "string", "number", "integer", "boolean", "array", "object" -> t;
            case "date" -> "date（" + JACKSON_DATE_PATTERN + "，" + TEMPORAL_TIMEZONE + "）";
            case "time" -> "time（" + JACKSON_TIME_PATTERN + "，" + TEMPORAL_TIMEZONE + "）";
            case "datetime" -> "datetime（" + JACKSON_DATETIME_PATTERN + "，" + TEMPORAL_TIMEZONE + "）";
            default -> "string";
        };
    }
}
