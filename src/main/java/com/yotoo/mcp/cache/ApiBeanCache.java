package com.yotoo.mcp.cache;

import com.yotoo.mcp.bean.ApiDef;
import com.yotoo.mcp.bean.ApiParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class ApiBeanCache {
    public volatile List<ApiDef> apiDefList;
    public volatile Map<Integer, List<ApiParam>> apiParamMap;

    public ApiBeanCache() {
        this.apiDefList = initApiDefList();
        this.apiParamMap = initApiParamMap();
    }

    private List<ApiDef> initApiDefList() {
        ApiDef getWeather = new ApiDef();
        getWeather.setApiId(1);
        getWeather.setOperationId("getWeather");
        getWeather.setSummary("根据城市名称获取天气信息");
        getWeather.setRequestWay("GET");
        getWeather.setApiPath("/get/weather/{city}");

        ApiDef calculate = new ApiDef();
        calculate.setApiId(2);
        calculate.setOperationId("calculate");
        calculate.setSummary("执行两个数的加减乘除运算");
        calculate.setRequestWay("POST");
        calculate.setApiPath("/calculate");

        ApiDef getId = new ApiDef();
        getId.setApiId(3);
        getId.setOperationId("getId");
        getId.setSummary("获取用户的ID");
        getId.setRequestWay("GET");
        getId.setApiPath("/get/id");
        return List.of(getWeather, calculate, getId);
    }

    private Map<Integer, List<ApiParam>> initApiParamMap() {
        return Stream.of(
                        apiParam(1, "city", "string", "true", "城市名称，例如：北京"),
                        apiParam(2, "a", "number", "true", "第一个数字"),
                        apiParam(2, "b", "number", "true", "第二个数字"),
                        apiParam(2, "op", "string", "true", "操作符：add/sub/mul/div")
                )
                .collect(Collectors.groupingBy(ApiParam::getApiId));
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
