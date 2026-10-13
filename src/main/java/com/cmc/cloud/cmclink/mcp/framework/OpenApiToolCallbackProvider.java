package com.cmc.cloud.cmclink.mcp.framework;

import io.modelcontextprotocol.server.McpSyncServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAPI -> MCP 工具注册中心：
 * <ul>
 *   <li>实现 {@link ToolCallbackProvider}：启动阶段返回当前缓存（初始为空），
 *       避免全量扫描阻塞 MCP Server / 应用启动</li>
 *   <li>应用就绪后由后台线程完成首次扫描注册，之后定时重新扫描，
 *       通过 McpSyncServer 的 addTool/removeTool 增量同步（含 listChanged 通知），
 *       服务的接口上下线后自动生效</li>
 * </ul>
 */
@Component
public class OpenApiToolCallbackProvider implements ToolCallbackProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenApiToolCallbackProvider.class);

    private final OpenApiScanner scanner;
    private final OpenApiToolCallbackFactory toolFactory;
    private final DiscoveryClient discoveryClient;
    private final ObjectProvider<McpSyncServer> mcpSyncServerProvider;

    /** 当前生效的工具集: 工具名 -> 工具回调 */
    private volatile Map<String, ToolCallback> currentTools = Map.of();

    public OpenApiToolCallbackProvider(OpenApiScanner scanner, OpenApiToolCallbackFactory toolFactory,
                                       DiscoveryClient discoveryClient, ObjectProvider<McpSyncServer> mcpSyncServerProvider) {
        this.scanner = scanner;
        this.toolFactory = toolFactory;
        this.discoveryClient = discoveryClient;
        this.mcpSyncServerProvider = mcpSyncServerProvider;
    }

    /**
     * 启动阶段被 Spring AI MCP Server 自动装配调用。
     * 此时 McpSyncServer 尚未创建完成，且全量扫描可能耗时较长（需逐服务 HTTP 拉取文档），
     * 故此处不做扫描直接返回缓存，首次注册由 {@link #onApplicationReady} 后台触发。
     */
    @Override
    public ToolCallback[] getToolCallbacks() {
        return currentTools.values().toArray(ToolCallback[]::new);
    }

    /**
     * 应用就绪后, 由后台线程完成首次扫描与注册, 不阻塞启动
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        Thread initialSync = new Thread(this::scheduledRefresh, "mcp-tool-initial-sync");
        initialSync.setDaemon(true);
        initialSync.start();
    }

    /**
     * 定时重新扫描 Nacos 并增量同步工具到 MCP Server（服务/接口上下线自动生效）
     */
//    @Scheduled(fixedDelayString = "${cmclink.mcp.gateway.refresh-interval:60s}",
//            initialDelayString = "${cmclink.mcp.gateway.initial-delay:60s}")
    public synchronized void scheduledRefresh() {
        Map<String, ToolCallback> fresh;
        try {
            fresh = scanAndBuild();
        }
        catch (Exception e) {
            log.warn("定时扫描 MCP 工具失败: {}", e.getMessage());
            return;
        }
        int added = 0, removed = 0, updated = 0;
        McpSyncServer server = mcpSyncServerProvider.getIfAvailable();
        if (server != null) {
            // 1. 移除已不存在的工具
            for (String name : currentTools.keySet()) {
                if (!fresh.containsKey(name)) {
                    try {
                        server.removeTool(name);
                        removed++;
                    }
                    catch (Exception e) {
                        log.warn("移除 MCP 工具 [{}] 失败: {}", name, e.getMessage());
                    }
                }
            }
            // 2. 新增或更新（定义变化时先移除再注册）
            for (Map.Entry<String, ToolCallback> entry : fresh.entrySet()) {
                String name = entry.getKey();
                ToolCallback newTool = entry.getValue();
                ToolCallback oldTool = currentTools.get(name);
                try {
                    if (oldTool == null) {
                        server.addTool(McpToolUtils.toSyncToolSpecification(newTool));
                        added++;
                    }
                    else if (toolChanged(oldTool, newTool)) {
                        server.removeTool(name);
                        server.addTool(McpToolUtils.toSyncToolSpecification(newTool));
                        updated++;
                    }
                }
                catch (Exception e) {
                    log.warn("同步 MCP 工具 [{}] 失败: {}", name, e.getMessage());
                }
            }
        }
        currentTools = fresh;
        if (added + removed + updated > 0) {
            log.info("MCP 工具同步完成: 新增 {}, 移除 {}, 更新 {}, 当前共 {} 个", added, removed, updated, fresh.size());
        }
        else {
            log.debug("MCP 工具无变化, 当前共 {} 个", fresh.size());
        }
    }

    private Map<String, ToolCallback> scanAndBuild() {
        Map<String, ToolCallback> tools = new LinkedHashMap<>();
        Map<String, OpenApiScanner.ServiceDoc> serviceDocs = scanner.scan();
        for (OpenApiScanner.ServiceDoc serviceDoc : serviceDocs.values()) {
            try {
                tools.putAll(toolFactory.buildTools(serviceDoc.serviceName(), discoveryClient,
                        serviceDoc.openApi()));
            }
            catch (Exception e) {
                log.warn("服务 [{}] 构建 MCP 工具失败: {}", serviceDoc.serviceName(), e.getMessage());
            }
        }
        return tools;
    }

    private boolean toolChanged(ToolCallback oldTool, ToolCallback newTool) {
        var oldDef = oldTool.getToolDefinition();
        var newDef = newTool.getToolDefinition();
        return !java.util.Objects.equals(oldDef.description(), newDef.description())
                || !java.util.Objects.equals(oldDef.inputSchema(), newDef.inputSchema());
    }
}
