package com.yotoo.mcp.service;

import com.yotoo.mcp.bean.ApiDef;
import com.yotoo.mcp.bean.ApiParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ApiDatabaseService {
    private static final String TABLE_API_DEF = "skill_api_def";
    private static final String TABLE_API_PARAM = "skill_api_param";
    /** MCP 工具加载与调用统计仅针对该版本 */
    private static final String VERSION_TYPE_LATEST = "latest";

    private static final String COL_API_ID = "api_id";
    private static final String COL_API_NAME = "api_name";
    private static final String COL_OPERATION_ID = "operation_id";
    private static final String COL_TAG_IDS = "tag_ids";
    private static final String COL_OPEN_FLAG = "open_flag";
    private static final String COL_API_TYPE = "api_type";
    private static final String COL_CONFIG_OPEN_FLAG = "config_open_flag";
    private static final String COL_CLASSIFIED_FLAG = "classified_flag";
    private static final String COL_SUMMARY = "summary";
    private static final String COL_API_PATH = "api_path";
    private static final String COL_REQUEST_WAY = "request_way";
    private static final String COL_AUTH_TYPE = "auth_type";
    private static final String COL_AUTH_VALUE = "auth_value";
    private static final String COL_REPLY_PROMPT_WORD = "reply_prompt_word";
    private static final String COL_API_RESPONSE = "api_response";
    private static final String COL_MODEL_RESPONSE = "model_response";
    private static final String COL_CREATE_USER_ID = "create_user_id";
    private static final String COL_CREATE_TIME = "create_time";
    private static final String COL_UPDATE_TIME = "update_time";
    private static final String COL_COLLEGE_ID = "college_id";
    private static final String COL_MAJOR_ID = "major_id";
    private static final String COL_CLASS_ID = "class_id";
    private static final String COL_ROLE_IDS = "role_ids";
    private static final String COL_RELEASE_STATUS = "release_status";
    private static final String COL_USED_COUNT = "used_count";
    private static final String COL_VERSION_TYPE = "version_type";

    private static final String COL_PARAM_ID = "param_id";
    private static final String COL_API_EDITION = "api_edition";
    private static final String COL_PARAM_NAME = "param_name";
    private static final String COL_PARAM_DATA_TYPE = "param_data_type";
    private static final String COL_REQUIRED = "required";
    private static final String COL_PARAM_DESCRIPTION = "param_description";
    private static final String COL_PARAM_ENUM = "param_enum";
    private static final String COL_MULTIPLE_FLAG = "multiple_flag";
    private static final String COL_SYSTEM_FIELD = "system_field";
    private static final String COL_TEST_VALUE = "test_value";
    private static final String COL_CONFIRMATION_REQUIRED = "confirmation_required";

    private final String dbUrl;
    private final String dbUsername;
    private final String dbPassword;

    public ApiDatabaseService(@Value("${api.cache.db.url:}") String dbUrl,
                              @Value("${api.cache.db.username:}") String dbUsername,
                              @Value("${api.cache.db.password:}") String dbPassword) {
        this.dbUrl = dbUrl;
        this.dbUsername = dbUsername;
        this.dbPassword = dbPassword;
    }

    public List<ApiDef> loadApiDefsWithParams() throws SQLException {
        ensureDbConfigured();

        List<ApiDef> apiDefs = new ArrayList<>();
        List<ApiParam> apiParams = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(dbUrl, dbUsername, dbPassword)) {
            String defSql = """
                    SELECT api_id, api_name, operation_id, tag_ids, open_flag, api_type,
                           config_open_flag, classified_flag, summary, api_path, request_way,
                           auth_type, auth_value, reply_prompt_word, api_response, model_response,
                           create_user_id, create_time, update_time, college_id, major_id, class_id,
                           role_ids, release_status, used_count, version_type
                    FROM %s
                    WHERE version_type = ?
                    """.formatted(TABLE_API_DEF);
            try (PreparedStatement statement = connection.prepareStatement(defSql)) {
                statement.setString(1, VERSION_TYPE_LATEST);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        apiDefs.add(mapApiDef(rs));
                    }
                }
            }

            String paramSql = """
                    SELECT param_id, api_edition, api_id, param_name, param_data_type, required,
                           param_description, param_enum, multiple_flag, system_field, test_value,
                           confirmation_required, version_type
                    FROM %s
                    WHERE api_edition = 2 AND version_type = ?
                    """.formatted(TABLE_API_PARAM);
            try (PreparedStatement statement = connection.prepareStatement(paramSql)) {
                statement.setString(1, VERSION_TYPE_LATEST);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        apiParams.add(mapApiParam(rs));
                    }
                }
            }
        }

        Map<Integer, List<ApiParam>> groupedParams = apiParams.stream()
                .collect(Collectors.groupingBy(ApiParam::getApiId));

        for (ApiDef apiDef : apiDefs) {
            apiDef.setApiParams(groupedParams.getOrDefault(apiDef.getApiId(), Collections.emptyList()));
        }
        return apiDefs;
    }

    public int incrementApiDefUsedCount(Integer apiId) throws SQLException {
        ensureDbConfigured();
        if (apiId == null) {
            return 0;
        }

        String sql = """
                UPDATE skill_api_def SET used_count = COALESCE(used_count, 0) + 1
                WHERE api_id = ? AND version_type = ?
                """;
        try (Connection connection = DriverManager.getConnection(dbUrl, dbUsername, dbPassword);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, apiId);
            statement.setString(2, VERSION_TYPE_LATEST);
            return statement.executeUpdate();
        }
    }

    private void ensureDbConfigured() {
        if (dbUrl == null || dbUrl.isBlank()) {
            throw new IllegalStateException("未配置api.cache.db.url");
        }
    }

    private ApiDef mapApiDef(ResultSet rs) throws SQLException {
        ApiDef def = new ApiDef();
        def.setApiId(rs.getObject(COL_API_ID, Integer.class));
        def.setApiName(rs.getString(COL_API_NAME));
        def.setOperationId(rs.getString(COL_OPERATION_ID));
        def.setTagIds(rs.getString(COL_TAG_IDS));
        def.setOpenFlag(rs.getObject(COL_OPEN_FLAG, Integer.class));
        def.setApiType(rs.getObject(COL_API_TYPE, Integer.class));
        def.setConfigOpenFlag(rs.getObject(COL_CONFIG_OPEN_FLAG, Integer.class));
        def.setClassifiedFlag(rs.getObject(COL_CLASSIFIED_FLAG, Integer.class));
        def.setSummary(rs.getString(COL_SUMMARY));
        def.setApiPath(rs.getString(COL_API_PATH));
        def.setRequestWay(rs.getString(COL_REQUEST_WAY));
        def.setAuthType(rs.getObject(COL_AUTH_TYPE, Integer.class));
        def.setAuthValue(rs.getString(COL_AUTH_VALUE));
        def.setReplyPromptWord(rs.getString(COL_REPLY_PROMPT_WORD));
        def.setApiResponse(rs.getString(COL_API_RESPONSE));
        def.setModelResponse(rs.getString(COL_MODEL_RESPONSE));
        def.setCreateUserId(rs.getObject(COL_CREATE_USER_ID, Integer.class));
        def.setCreateTime(rs.getObject(COL_CREATE_TIME, OffsetDateTime.class));
        def.setUpdateTime(rs.getObject(COL_UPDATE_TIME, OffsetDateTime.class));
        def.setCollegeId(rs.getObject(COL_COLLEGE_ID, Integer.class));
        def.setMajorId(rs.getObject(COL_MAJOR_ID, Integer.class));
        def.setClassId(rs.getObject(COL_CLASS_ID, Integer.class));
        def.setRoleIds(rs.getString(COL_ROLE_IDS));
        def.setReleaseStatus(rs.getObject(COL_RELEASE_STATUS, Integer.class));
        def.setUsedCount(rs.getObject(COL_USED_COUNT, Long.class));
        def.setVersionType(rs.getString(COL_VERSION_TYPE));
        return def;
    }

    private ApiParam mapApiParam(ResultSet rs) throws SQLException {
        ApiParam param = new ApiParam();
        param.setParamId(rs.getObject(COL_PARAM_ID, Integer.class));
        param.setApiEdition(rs.getObject(COL_API_EDITION, Integer.class));
        param.setApiId(rs.getObject(COL_API_ID, Integer.class));
        param.setParamName(rs.getString(COL_PARAM_NAME));
        param.setParamDataType(rs.getString(COL_PARAM_DATA_TYPE));
        param.setRequired(rs.getString(COL_REQUIRED));
        param.setParamDescription(rs.getString(COL_PARAM_DESCRIPTION));
        param.setParamEnum(rs.getString(COL_PARAM_ENUM));
        param.setMultipleFlag(rs.getObject(COL_MULTIPLE_FLAG, Integer.class));
        param.setSystemField(rs.getString(COL_SYSTEM_FIELD));
        param.setTestValue(rs.getString(COL_TEST_VALUE));
        param.setConfirmationRequired(rs.getObject(COL_CONFIRMATION_REQUIRED, Integer.class));
        param.setVersionType(rs.getString(COL_VERSION_TYPE));
        return param;
    }
}
