package com.yotoo.mcp.config;

import com.yotoo.mcp.service.McpService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolCallbackProviderConfig {

    // DIFY平台需要通过修改数据库来更新工具列表，所以这种方式无法被DIFY平台使用
    // @Bean
    // public ToolCallbackProvider mcpServiceProvider(McpService mcpService) {
    // return MethodToolCallbackProvider.builder().toolObjects(mcpService).build();
    // }
    
}
