package com.yotoo.mcp.cache;

import com.yotoo.mcp.bean.ApiDef;
import com.yotoo.mcp.bean.ApiParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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

@Component
public class ApiBeanCache {
    private static final Logger logger = LoggerFactory.getLogger(ApiBeanCache.class);
    private static final String TABLE_API_DEF = "skill_api_def";
    private static final String TABLE_API_PARAM = "skill_api_param";

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

    public volatile List<ApiDef> apiDefList;

    private final String dbUrl;
    private final String dbUsername;
    private final String dbPassword;

    public ApiBeanCache(@Value("${api.cache.db.url:}") String dbUrl,
                        @Value("${api.cache.db.username:}") String dbUsername,
                        @Value("${api.cache.db.password:}") String dbPassword) {
        this.dbUrl = dbUrl;
        this.dbUsername = dbUsername;
        this.dbPassword = dbPassword;
        refreshCache();
    }

    public synchronized boolean refreshCache() {
        try {
            CacheData dbData = loadFromDatabase();
            if (dbData == null || dbData.apiDefs().isEmpty()) {
                throw new IllegalStateException("数据库返回的 API 定义为空");
            }
            this.apiDefList = dbData.apiDefs();
            logger.info("缓存刷新成功，来源=MySQL，apiDef数量={}", this.apiDefList.size());
            return true;
        } catch (Exception e) {
            this.apiDefList = initMockApiDefs();
            logger.warn("读取MySQL失败，已回退到模拟数据，原因={}", e.getMessage());
            return false;
        }
    }

    private List<ApiDef> initMockApiDefs() {
        ApiDef getWeather = new ApiDef();
        getWeather.setApiId(1);
        getWeather.setOperationId("getWeather");
        getWeather.setSummary("根据城市名称获取天气信息");
        getWeather.setRequestWay("GET");
        getWeather.setApiPath("/get/weather/{city}");
        getWeather.setApiParams(List.of(
                apiParam(1, "city", "string", "true", "城市名称，例如：北京")
        ));

        ApiDef calculate = new ApiDef();
        calculate.setApiId(2);
        calculate.setOperationId("calculate");
        calculate.setSummary("执行两个数的加减乘除运算");
        calculate.setRequestWay("POST");
        calculate.setApiPath("/calculate");
        calculate.setApiParams(List.of(
                apiParam(2, "a", "number", "true", "第一个数字"),
                apiParam(2, "b", "number", "true", "第二个数字"),
                apiParam(2, "op", "string", "true", "操作符：add/sub/mul/div")
        ));

        ApiDef getId = new ApiDef();
        getId.setApiId(3);
        getId.setOperationId("getId");
        getId.setSummary("获取用户的ID");
        getId.setRequestWay("GET");
        getId.setApiPath("/get/id");
        getId.setApiParams(Collections.emptyList());

        return List.of(getWeather, calculate, getId);
    }

    private ApiParam apiParam(Integer apiId, String name, String type, String required, String description) {
        ApiParam param = new ApiParam();
        param.setApiId(apiId);
        param.setParamName(name);
        param.setParamDataType(type);
        param.setRequired(required);
        param.setParamDescription(description);
        return param;
    }

    private CacheData loadFromDatabase() throws SQLException {
        if (dbUrl == null || dbUrl.isBlank()) {
            throw new IllegalStateException("未配置api.cache.db.url");
        }

        List<ApiDef> apiDefs = new ArrayList<>();
        List<ApiParam> apiParams = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(dbUrl, dbUsername, dbPassword)) {
            String defSql = """
                    SELECT api_id, api_name, operation_id, tag_ids, open_flag, api_type,
                           config_open_flag, classified_flag, summary, api_path, request_way,
                           auth_type, auth_value, reply_prompt_word, api_response, model_response,
                           create_user_id, create_time, update_time, college_id, major_id, class_id,
                           role_ids, release_status, used_count
                    FROM %s
                    """.formatted(TABLE_API_DEF);
            try (PreparedStatement statement = connection.prepareStatement(defSql);
                 ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    apiDefs.add(mapApiDef(rs));
                }
            }

            String paramSql = """
                    SELECT param_id, api_edition, api_id, param_name, param_data_type, required,
                           param_description, param_enum, multiple_flag, system_field, test_value,
                           confirmation_required
                    FROM %s
                    WHERE api_edition = 2
                    """.formatted(TABLE_API_PARAM);
            try (PreparedStatement statement = connection.prepareStatement(paramSql);
                 ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    apiParams.add(mapApiParam(rs));
                }
            }
        }

        Map<Integer, List<ApiParam>> groupedParams = apiParams.stream()
                .collect(Collectors.groupingBy(ApiParam::getApiId));

        for (ApiDef apiDef : apiDefs) {
            apiDef.setApiParams(groupedParams.getOrDefault(apiDef.getApiId(), Collections.emptyList()));
        }
        return new CacheData(apiDefs);
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
        return param;
    }

    private record CacheData(List<ApiDef> apiDefs) {
    }
}
