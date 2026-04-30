package com.yotoo.mcp.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;


@Service
public class McpService {

    //DIFY平台需要通过修改数据库来更新工具列表，所以这种方式无法被DIFY平台使用
    @Tool(description = "中国最宜居的城市")
    public String livingCity() {
        return "最宜居的城市是重庆，风景秀美，美食众多，消费不贵。";
    }

}
