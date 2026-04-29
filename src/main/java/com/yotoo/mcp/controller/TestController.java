package com.yotoo.mcp.controller;

import com.yotoo.mcp.service.ApiCacheRefreshService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

@RestController
public class TestController {
    Logger logger = LoggerFactory.getLogger(TestController.class);

    private final ApiCacheRefreshService apiCacheRefreshService;

    public TestController(ApiCacheRefreshService apiCacheRefreshService) {
        this.apiCacheRefreshService = apiCacheRefreshService;
    }

    /**
     * 刷新工具列表
     *
     * @return
     */
    @GetMapping("/refresh/tools")
    public String refreshTools() {
        return apiCacheRefreshService.manualRefresh();
    }

    /**
     * 获取天气
     *
     * @param city 城市
     * @return
     */
    @GetMapping("/get/weather/{city}")
    public String getWeather(@PathVariable String city) {
        return city + "：晴天";
    }

    /**
     * 获取用户id
     *
     * @return
     */
    @GetMapping("/get/id")
    public String getId(HttpServletRequest request) {
        //获取请求头中的token值
        String token = request.getHeader("Authorization");
        logger.info("token：{}", token);
        return "id：" + 1;
    }

    /**
     * 执行两个数的加减乘除运算
     *
     * @param request 计算请求对象
     * @return 计算结果
     */
    @PostMapping("/calculate")
    public String calculate(@RequestBody CalculateRequest request) {
        try {
            double result;
            switch (request.getOp().toLowerCase()) {
                case "add":
                    result = request.getA() + request.getB();
                    break;
                case "sub":
                    result = request.getA() - request.getB();
                    break;
                case "mul":
                    result = request.getA() * request.getB();
                    break;
                case "div":
                    if (request.getB() == 0) {
                        return "错误：除数不能为0";
                    }
                    result = request.getA() / request.getB();
                    break;
                default:
                    return "错误：不支持的操作符，请使用add/sub/mul/div";
            }
            return String.format("%.2f %s %.2f = %.2f", request.getA(), request.getOp(), request.getB(), result);
        } catch (Exception e) {
            logger.error("计算出错：{}", e.getMessage());
            return "计算出错：" + e.getMessage();
        }
    }

    /**
     * 计算请求对象
     */
    @Data
    public static class CalculateRequest {
        private Double a;
        private Double b;
        private String op;
    }


}