package com.yotoo.mcp.service;

import io.modelcontextprotocol.server.McpSyncServer;
import org.springframework.stereotype.Component;

@Component
public class McpToolService {
    private final McpSyncServer mcpServer;

    public McpToolService(McpSyncServer mcpServer) {
        this.mcpServer = mcpServer;
    }

    /**
     * 刷新工具列表：清除缓存并通知所有已连接的 MCP 客户端
     */
    public void refreshTools() {
        // 发送工具列表变更通知
        if (mcpServer != null) {
            mcpServer.notifyToolsListChanged();
        }
    }

}
