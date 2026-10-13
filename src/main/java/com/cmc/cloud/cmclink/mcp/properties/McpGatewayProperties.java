package com.cmc.cloud.cmclink.mcp.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAPI 转 MCP 工具网关配置
 */
@Component
@ConfigurationProperties(prefix = "cmclink.mcp.gateway")
public class McpGatewayProperties {

    /** 各微服务暴露 OpenAPI(Swagger) 文档的路径 */
    private String openApiPath = "/v3/api-docs";

    /** 排除的服务名（不注册为 MCP 工具），默认排除自身 */
    private List<String> excludedServices = new ArrayList<>(List.of("cmclink-mcp-server"));

    /** 定时重新扫描并同步工具的时间间隔 */
    private Duration refreshInterval = Duration.ofSeconds(60);

    /** 首次定时刷新前的等待时间（留时间给各服务完成注册） */
    private Duration initialDelay = Duration.ofSeconds(60);

    /** 调用微服务接口的连接超时 */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /** 调用微服务接口的读取超时 */
    private Duration readTimeout = Duration.ofSeconds(30);

    /** 工具返回内容的最大字节数，超出部分截断 */
    private int maxResponseSize = 64 * 1024;

    /** 工具描述的最大长度 */
    private int maxDescriptionLength = 1000;

    public String getOpenApiPath() {
        return openApiPath;
    }

    public void setOpenApiPath(String openApiPath) {
        this.openApiPath = openApiPath;
    }

    public List<String> getExcludedServices() {
        return excludedServices;
    }

    public void setExcludedServices(List<String> excludedServices) {
        this.excludedServices = excludedServices;
    }

    public Duration getRefreshInterval() {
        return refreshInterval;
    }

    public void setRefreshInterval(Duration refreshInterval) {
        this.refreshInterval = refreshInterval;
    }

    public Duration getInitialDelay() {
        return initialDelay;
    }

    public void setInitialDelay(Duration initialDelay) {
        this.initialDelay = initialDelay;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public int getMaxResponseSize() {
        return maxResponseSize;
    }

    public void setMaxResponseSize(int maxResponseSize) {
        this.maxResponseSize = maxResponseSize;
    }

    public int getMaxDescriptionLength() {
        return maxDescriptionLength;
    }

    public void setMaxDescriptionLength(int maxDescriptionLength) {
        this.maxDescriptionLength = maxDescriptionLength;
    }
}
