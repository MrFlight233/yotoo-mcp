package com.yotoo.mcp.cache;

import com.yotoo.mcp.bean.ApiDef;
import com.yotoo.mcp.bean.ApiParam;
import com.yotoo.mcp.service.ApiDatabaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class ApiBeanCache {
    private static final Logger logger = LoggerFactory.getLogger(ApiBeanCache.class);

    public volatile List<ApiDef> apiDefList;

    private final ApiDatabaseService apiDatabaseService;

    public ApiBeanCache(ApiDatabaseService apiDatabaseService) {
        this.apiDatabaseService = apiDatabaseService;
        refreshCache();
    }

    public synchronized boolean refreshCache() {
        try {
            List<ApiDef> dbData = apiDatabaseService.loadApiDefsWithParams();
            if (dbData.isEmpty()) {
                throw new IllegalStateException("数据库返回的 API 定义为空");
            }
            this.apiDefList = dbData;
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
}
