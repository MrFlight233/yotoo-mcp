package com.yotoo.mcp.service;

import com.yotoo.mcp.cache.ApiBeanCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ApiCacheRefreshService {
    private static final Logger logger = LoggerFactory.getLogger(ApiCacheRefreshService.class);

    private final ApiBeanCache apiBeanCache;
    private final McpToolService mcpToolService;
    private final boolean scheduleEnabled;

    public ApiCacheRefreshService(ApiBeanCache apiBeanCache,
                                  McpToolService mcpToolService,
                                  @Value("${api.cache.refresh.enabled:true}") boolean scheduleEnabled) {
        this.apiBeanCache = apiBeanCache;
        this.mcpToolService = mcpToolService;
        this.scheduleEnabled = scheduleEnabled;
    }

    @Scheduled(cron = "${api.cache.refresh.cron:0 */5 * * * *}")
    public void scheduledRefresh() {
        if (!scheduleEnabled) {
            return;
        }
        refreshAndNotify("scheduled");
    }

    public String manualRefresh() {
        return refreshAndNotify("manual");
    }

    private String refreshAndNotify(String source) {
        boolean loadedFromDb = apiBeanCache.refreshCache();
        // mcpToolService.refreshTools();
        mcpToolService.updateDifyMcpTools();
        String result = loadedFromDb ? "mysql" : "mock";
        logger.info("缓存刷新完成 source={}, dataSource={}", source, result);
        return "refresh success, dataSource=" + result;
    }
}
