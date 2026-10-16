package com.cmc.cloud.cmclink.mcp.framework;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 将单个 OpenAPI operation 包装为 MCP 工具回调：
 * MCP 工具被调用时，将参数还原为对应的 HTTP 请求（path / query / header / json body）转发到微服务
 */
public class OpenApiToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(OpenApiToolCallback.class);

    /** 参数解析与请求体序列化（ObjectMapper 线程安全，无需容器注入） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ToolDefinition toolDefinition;
    private final String serviceName;
    private final HttpMethod httpMethod;
    private final String pathTemplate;
    private final Set<String> pathParamNames;
    private final Set<String> queryParamNames;
    private final Set<String> headerParamNames;
    private final boolean sendJsonBody;
    private final Supplier<List<ServiceInstance>> instanceSupplier;
    private final RestClient restClient;
    private final int maxResponseSize;
    private final AtomicInteger roundRobin = new AtomicInteger();

    OpenApiToolCallback(ToolDefinition toolDefinition, String serviceName, HttpMethod httpMethod, String pathTemplate,
                        Set<String> pathParamNames, Set<String> queryParamNames, Set<String> headerParamNames,
                        boolean sendJsonBody, Supplier<List<ServiceInstance>> instanceSupplier, RestClient restClient,
                        int maxResponseSize) {
        this.toolDefinition = toolDefinition;
        this.serviceName = serviceName;
        this.httpMethod = httpMethod;
        this.pathTemplate = pathTemplate;
        this.pathParamNames = pathParamNames;
        this.queryParamNames = queryParamNames;
        this.headerParamNames = headerParamNames;
        this.sendJsonBody = sendJsonBody;
        this.instanceSupplier = instanceSupplier;
        this.restClient = restClient;
        this.maxResponseSize = maxResponseSize;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        Map<String, Object> args = parseArguments(toolInput);

        ServiceInstance instance = pickInstance();
        if (instance == null) {
            return "Error: 服务 [" + serviceName + "] 当前没有可用实例";
        }

        URI uri = buildUri(instance.getUri().toString(), args);
        HttpHeaders headers = buildHeaders(args);
        String body = buildBody(args);

        try {
            RestClient.RequestBodySpec request = restClient.method(httpMethod).uri(uri);
            if (!headers.isEmpty()) {
                request.headers(h -> h.addAll(headers));
            }
            if (body != null) {
                request.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            String response = request.retrieve().body(String.class);
            return truncate(response == null ? "" : response);
        }
        catch (RestClientResponseException e) {
            String responseBody = e.getResponseBodyAsString();
            log.info("工具 [{}] 调用返回 HTTP {}: {}", toolDefinition.name(), e.getStatusCode().value(), responseBody);
            return truncate("HTTP " + e.getStatusCode().value() + " " + e.getStatusText() + "\n" + responseBody);
        }
        catch (Exception e) {
            log.warn("工具 [{}] 调用失败: {}", toolDefinition.name(), e.getMessage());
            return "Error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------ 请求构造

    private Map<String, Object> parseArguments(String toolInput) {
        if (toolInput == null || toolInput.isBlank() || "null".equals(toolInput)) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(toolInput, new TypeReference<Map<String, Object>>() {
            });
        }
        catch (Exception e) {
            throw new IllegalArgumentException("工具参数解析失败, 期望 JSON 对象: " + e.getMessage(), e);
        }
    }

    private URI buildUri(String baseUri, Map<String, Object> args) {
        String path = pathTemplate;
        for (String name : pathParamNames) {
            Object value = args.get(name);
            String encoded = value == null ? "" : URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8);
            path = path.replace("{" + name + "}", encoded);
        }
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUri + path);
        for (String name : queryParamNames) {
            Object value = args.get(name);
            if (value != null) {
                builder.queryParam(name, String.valueOf(value));
            }
        }
        return builder.encode().build().toUri();
    }

    private HttpHeaders buildHeaders(Map<String, Object> args) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("tenant-id", "172");
        headers.set("authorization", "Bearer 5a443d5bdf144399a1124c22c0543917");
        for (String name : headerParamNames) {
            Object value = args.get(name);
            if (value != null) {
                headers.set(name, String.valueOf(value));
            }
        }
        return headers;
    }

    /**
     * JSON body：入参中未被 path/query/header 消费的字段全部放入请求体
     */
    private String buildBody(Map<String, Object> args) {
        if (!sendJsonBody) {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            if (entry.getValue() != null && !pathParamNames.contains(entry.getKey())
                    && !queryParamNames.contains(entry.getKey()) && !headerParamNames.contains(entry.getKey())) {
                body.put(entry.getKey(), entry.getValue());
            }
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(body);
        }
        catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 实时获取实例并轮询选择，实例上下线自动生效
     */
    private ServiceInstance pickInstance() {
        List<ServiceInstance> instances;
        try {
            instances = instanceSupplier.get();
        }
        catch (Exception e) {
            log.warn("获取服务 [{}] 实例列表失败: {}", serviceName, e.getMessage());
            return null;
        }
        if (instances == null || instances.isEmpty()) {
            return null;
        }
        List<ServiceInstance> snapshot = new ArrayList<>(instances);
        int index = Math.abs(roundRobin.getAndIncrement()) % snapshot.size();
        return snapshot.get(index);
    }

    private String truncate(String text) {
        if (text.length() <= maxResponseSize) {
            return text;
        }
        return text.substring(0, maxResponseSize) + "\n...[响应过长已截断, 共 " + text.length() + " 字符]";
    }

    /**
     * 供工厂构建共享的 RestClient
     */
    static RestClient buildRestClient(Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        var requestFactory = new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
