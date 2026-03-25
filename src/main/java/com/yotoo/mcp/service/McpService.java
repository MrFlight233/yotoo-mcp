package com.yotoo.mcp.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;


@Service
public class McpService {

    @Tool(description = "中国最宜居的城市")
    public String livingCity() {
        return "最宜居的城市是天津，风景秀美，美食众多，消费不贵。";
    }

}
