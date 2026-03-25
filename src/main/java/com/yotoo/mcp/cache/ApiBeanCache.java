package com.yotoo.mcp.cache;

import com.yotoo.mcp.bean.ApiBean;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class ApiBeanCache {
    public volatile List<ApiBean> apiBeanList;

    public ApiBeanCache() {
        apiBeanList = initApiBeanList();
    }

    private List<ApiBean> initApiBeanList() {
        return Arrays.asList(
                new ApiBean(
                        "getWeather",
                        "根据城市名称获取天气信息",
                        "GET",
                        "/get/weather/{city}",
                        List.of(
                                new ApiBean.Parameter("city", "string", true, "城市名称，例如：北京")
                        )
                ),
                new ApiBean(
                        "calculate",
                        "执行两个数的加减乘除运算",
                        "POST",
                        "/calculate",
                        List.of(
                                new ApiBean.Parameter("a", "number", true, "第一个数字"),
                                new ApiBean.Parameter("b", "number", true, "第二个数字"),
                                new ApiBean.Parameter("op", "string", true, "操作符：add/sub/mul/div")
                        )
                ),
                new ApiBean(
                        "getId",
                        "获取用户的ID",
                        "GET",
                        "/get/id"
                )
        );
    }

}
