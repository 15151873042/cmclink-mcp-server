package com.cmc.cloud.cmclink.mcp.framework;

import com.cmc.cloud.cmclink.mcp.properties.McpGatewayProperties;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 扫描 Nacos 注册中心中的所有服务，并拉取各服务的 OpenAPI(Swagger) 文档
 */
@Component
public class OpenApiScanner {

    private static final Logger log = LoggerFactory.getLogger(OpenApiScanner.class);

    private final DiscoveryClient discoveryClient;
    private final McpGatewayProperties properties;
    private final RestClient restClient;

    public OpenApiScanner(DiscoveryClient discoveryClient, McpGatewayProperties properties) {
        this.discoveryClient = discoveryClient;
        this.properties = properties;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /**
     * 单个服务的 OpenAPI 扫描结果
     *
     * @param serviceName 服务名
     * @param instances   服务实例列表（调用时还可实时刷新）
     * @param openApi     解析后的 OpenAPI 文档
     */
    public record ServiceDoc(String serviceName, List<ServiceInstance> instances, OpenAPI openApi) {
    }

    /**
     * 扫描所有注册服务，返回 服务名 -> OpenAPI 文档 的映射（拉取失败的服务会被跳过）
     */
    public Map<String, ServiceDoc> scan() {
        Map<String, ServiceDoc> result = new LinkedHashMap<>();
        List<String> services;
        try {
            services = discoveryClient.getServices();
        }
        catch (Exception e) {
            log.warn("获取 Nacos 服务列表失败: {}", e.getMessage());
            return result;
        }
        long scanStart = System.currentTimeMillis();
        int total = services.size();
        int processed = 0;
        log.info("开始扫描 Nacos 服务 OpenAPI 文档, 共 {} 个候选服务", total);
        for (String serviceName : services) {
            if (!properties.getExcludedServices().contains(serviceName)) {
                continue;
            }
            processed++;
            long serviceStart = System.currentTimeMillis();
            try {
                List<ServiceInstance> instances = discoveryClient.getInstances(serviceName);
                if (instances == null || instances.isEmpty()) {
                    log.debug("服务 [{}] 无可用实例, 跳过", serviceName);
                    continue;
                }
                // 逐个实例尝试拉取文档，成功即止
                OpenAPI openApi = null;
                for (ServiceInstance instance : instances) {
                    openApi = fetchOpenApi(instance);
                    if (openApi != null) {
                        break;
                    }
                }
                if (openApi != null) {
                    result.put(serviceName, new ServiceDoc(serviceName, instances, openApi));
                    log.info("({}/{}) 服务 [{}] OpenAPI 获取成功: {} 个路径, 耗时 {} ms",
                            processed, total, serviceName, openApi.getPaths().size(),
                            System.currentTimeMillis() - serviceStart);
                }
                else {
                    log.warn("({}/{}) 服务 [{}] OpenAPI 获取失败: {} 个实例均无可用文档, 耗时 {} ms",
                            processed, total, serviceName, instances.size(),
                            System.currentTimeMillis() - serviceStart);
                }
            }
            catch (Exception e) {
                log.warn("({}/{}) 扫描服务 [{}] 失败: {}", processed, total, serviceName, e.getMessage());
            }
        }
        log.info("OpenAPI 扫描完成: 候选服务 {} 个, 成功获取文档 {} 个, 总耗时 {} ms",
                total, result.size(), System.currentTimeMillis() - scanStart);
        return result;
    }

    /**
     * 拉取并解析单个服务实例的 OpenAPI 文档
     */
    private OpenAPI fetchOpenApi(ServiceInstance instance) {
        String url = instance.getUri() + properties.getOpenApiPath();
        try {
            String json = restClient.get().uri(URI.create(url)).retrieve().body(String.class);
            if (json == null || json.isBlank()) {
                log.debug("服务 [{}] 实例 {} 返回空文档", instance.getServiceId(), url);
                return null;
            }
            SwaggerParseResult parseResult = new OpenAPIV3Parser().readContents(json);
            OpenAPI openApi = parseResult.getOpenAPI();
            if (openApi == null || openApi.getPaths() == null || openApi.getPaths().isEmpty()) {
                log.debug("服务 [{}] 文档解析失败 {}: {}", instance.getServiceId(), url, parseResult.getMessages());
                return null;
            }
            return openApi;
        }
        catch (Exception e) {
            // 该实例可能未接入 springdoc 或暂时不可用
            log.debug("拉取服务 [{}] 文档失败 {}: {}", instance.getServiceId(), url, e.getMessage());
            return null;
        }
    }
}
