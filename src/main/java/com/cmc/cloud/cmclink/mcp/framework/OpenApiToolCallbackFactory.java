package com.cmc.cloud.cmclink.mcp.framework;

import com.cmc.cloud.cmclink.mcp.properties.McpGatewayProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 将 OpenAPI 文档中的每个 operation（接口）转换为 MCP 工具
 */
@Component
public class OpenApiToolCallbackFactory {

    private static final Logger log = LoggerFactory.getLogger(OpenApiToolCallbackFactory.class);

    private static final String SCHEMAS_REF_PREFIX = "#/components/schemas/";
    private static final String REQUEST_BODIES_REF_PREFIX = "#/components/requestBodies/";
    private static final List<String> HTTP_METHODS =
            List.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    /** 序列化 Schema 专用：忽略 null 字段 */
    private static final ObjectMapper SCHEMA_MAPPER = JsonMapper.builder()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    private final McpGatewayProperties properties;
    private final RestClient restClient;

    public OpenApiToolCallbackFactory(McpGatewayProperties properties) {
        this.properties = properties;
        this.restClient = OpenApiToolCallback.buildRestClient(properties.getConnectTimeout(),
                properties.getReadTimeout());
    }

    /**
     * 构建单个服务的全部 MCP 工具
     *
     * @param serviceName 服务名（Nacos 注册名）
     * @param openApi     服务的 OpenAPI 文档
     * @return 工具名 -> 工具回调
     */
    public Map<String, ToolCallback> buildTools(String serviceName, DiscoveryClient discoveryClient,
            OpenAPI openApi) {
        Supplier<List<ServiceInstance>> instanceSupplier = () -> discoveryClient.getInstances(serviceName);
        Map<String, ToolCallback> tools = new LinkedHashMap<>();
        if (openApi.getPaths() == null) {
            return tools;
        }
        for (Map.Entry<String, PathItem> pathEntry : openApi.getPaths().entrySet()) {
            String path = pathEntry.getKey();
            PathItem pathItem = pathEntry.getValue();
            if (pathItem == null) {
                continue;
            }
            for (String method : HTTP_METHODS) {
                Operation operation = extractOperation(pathItem, method);
                if (operation == null) {
                    continue;
                }
                try {
                    ToolCallback tool = createTool(serviceName, method, path, pathItem, operation, openApi,
                            instanceSupplier, tools.keySet());
                    if (tool != null) {
                        tools.put(tool.getToolDefinition().name(), tool);
                    }
                }
                catch (Exception e) {
                    log.warn("服务 [{}] 接口 {} {} 转换为 MCP 工具失败: {}", serviceName, method, path, e.getMessage());
                }
            }
        }
        return tools;
    }

    // ------------------------------------------------------------------ 工具构建

    private ToolCallback createTool(String serviceName, String method, String path, PathItem pathItem,
            Operation operation, OpenAPI openApi, Supplier<List<ServiceInstance>> instanceSupplier,
            Set<String> usedNames) {

        // 1. 参数分类收集 + inputSchema 属性组装
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        Set<String> pathParams = new LinkedHashSet<>();
        Set<String> queryParams = new LinkedHashSet<>();
        Set<String> headerParams = new LinkedHashSet<>();

        for (Parameter param : mergeParameters(pathItem, operation)) {
            String in = param.getIn();
            if (in == null || "cookie".equals(in) || param.getName() == null) {
                continue;
            }
            Map<String, Object> schemaMap = schemaToMap(resolveSchema(param.getSchema(), openApi));
            if (schemaMap.isEmpty()) {
                schemaMap = new LinkedHashMap<>(Map.of("type", "string"));
            }
            if (param.getDescription() != null && !param.getDescription().isBlank()) {
                schemaMap.put("description", param.getDescription());
            }
            properties.put(param.getName(), schemaMap);
            switch (in) {
                case "path" -> {
                    pathParams.add(param.getName());
                    required.add(param.getName());
                }
                case "query" -> {
                    queryParams.add(param.getName());
                    if (Boolean.TRUE.equals(param.getRequired())) {
                        required.add(param.getName());
                    }
                }
                case "header" -> {
                    headerParams.add(param.getName());
                    if (Boolean.TRUE.equals(param.getRequired())) {
                        required.add(param.getName());
                    }
                }
                default -> {
                }
            }
        }

        // 2. JSON 请求体：字段展开合并进 inputSchema
        boolean sendJsonBody = false;
        RequestBody requestBody = resolveRequestBody(operation.getRequestBody(), openApi);
        if (requestBody != null && requestBody.getContent() != null) {
            io.swagger.v3.oas.models.media.MediaType jsonMedia =
                    requestBody.getContent().get("application/json");
            if (jsonMedia != null && jsonMedia.getSchema() != null) {
                sendJsonBody = true;
                Schema<?> bodySchema = resolveSchema(jsonMedia.getSchema(), openApi);
                Map<String, Object> bodySchemaMap = schemaToMap(bodySchema);
                Object bodyProps = bodySchemaMap.get("properties");
                if (bodyProps instanceof Map<?, ?> bodyPropMap) {
                    for (Map.Entry<?, ?> entry : bodyPropMap.entrySet()) {
                        String fieldName = String.valueOf(entry.getKey());
                        if (!properties.containsKey(fieldName)) {
                            properties.put(fieldName, entry.getValue());
                        }
                    }
                }
                Object bodyRequired = bodySchemaMap.get("required");
                if (bodyRequired instanceof List<?> requiredList) {
                    for (Object fieldName : requiredList) {
                        String name = String.valueOf(fieldName);
                        if (properties.containsKey(name) && !required.contains(name)) {
                            required.add(name);
                        }
                    }
                }
            }
        }

        // 3. 工具名（保证 MCP 合法且唯一）
        String toolName = uniqueName(usedNames, buildBaseToolName(serviceName, method, path, operation));

        // 4. 工具描述
        String description = buildDescription(serviceName, method, path, operation);

        // 5. inputSchema
        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        if (!required.isEmpty()) {
            inputSchema.put("required", required);
        }

        ToolDefinition toolDefinition = ToolDefinition.builder()
                .name(toolName)
                .description(description)
                .inputSchema(writeJson(inputSchema))
                .build();

        return new OpenApiToolCallback(toolDefinition, serviceName, HttpMethod.valueOf(method.toUpperCase()),
                path, pathParams, queryParams, headerParams, sendJsonBody, instanceSupplier, restClient,
                this.properties.getMaxResponseSize());
    }

    private String buildBaseToolName(String serviceName, String method, String path, Operation operation) {
        String operationId = operation.getOperationId();
        String raw;
        if (operationId != null && !operationId.isBlank()) {
            raw = operationId;
        }
        else {
            raw = method + path.replaceAll("[^a-zA-Z0-9]+", "_");
        }
        String name = sanitize(serviceName) + "_" + sanitize(raw);
        return name.length() > 64 ? name.substring(0, 64) : name;
    }

    /**
     * 清理为合法的 MCP 工具名字符 [a-zA-Z0-9_-]
     */
    private String sanitize(String input) {
        return input == null ? "" : input.replaceAll("[^a-zA-Z0-9_\\-]", "");
    }

    private String uniqueName(Set<String> usedNames, String baseName) {
        String candidate = baseName;
        int suffix = 2;
        while (usedNames.contains(candidate)) {
            String tail = "_" + suffix++;
            candidate = baseName.length() + tail.length() > 64
                    ? baseName.substring(0, 64 - tail.length()) + tail
                    : baseName + tail;
        }
        return candidate;
    }

    private String buildDescription(String serviceName, String method, String path, Operation operation) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(serviceName).append("] ").append(method.toUpperCase()).append(" ").append(path);
        String summary = operation.getSummary();
        if (summary != null && !summary.isBlank()) {
            sb.append(": ").append(summary.trim());
        }
        else if (operation.getDescription() != null && !operation.getDescription().isBlank()) {
            sb.append(": ").append(operation.getDescription().trim());
        }
        if (operation.getTags() != null && !operation.getTags().isEmpty()) {
            sb.append(" (tags: ").append(String.join(",", operation.getTags())).append(")");
        }
        String description = sb.toString();
        int max = properties.getMaxDescriptionLength();
        return description.length() > max ? description.substring(0, max) : description;
    }

    // ------------------------------------------------------------------ OpenAPI 辅助

    private Operation extractOperation(PathItem pathItem, String method) {
        return switch (method) {
            case "get" -> pathItem.getGet();
            case "put" -> pathItem.getPut();
            case "post" -> pathItem.getPost();
            case "delete" -> pathItem.getDelete();
            case "options" -> pathItem.getOptions();
            case "head" -> pathItem.getHead();
            case "patch" -> pathItem.getPatch();
            case "trace" -> pathItem.getTrace();
            default -> null;
        };
    }

    /**
     * 合并 path 级与 operation 级参数（operation 覆盖同名同位置的 path 级参数）
     */
    private List<Parameter> mergeParameters(PathItem pathItem, Operation operation) {
        Map<String, Parameter> merged = new LinkedHashMap<>();
        if (pathItem.getParameters() != null) {
            for (Parameter p : pathItem.getParameters()) {
                merged.put(paramKey(p), p);
            }
        }
        if (operation.getParameters() != null) {
            for (Parameter p : operation.getParameters()) {
                merged.put(paramKey(p), p);
            }
        }
        return List.copyOf(merged.values());
    }

    private String paramKey(Parameter p) {
        return (p.getName() == null ? "" : p.getName()) + ":" + (p.getIn() == null ? "" : p.getIn());
    }

    /**
     * 解析 components 中的 $ref
     */
    private Schema<?> resolveSchema(Schema<?> schema, OpenAPI openApi) {
        if (schema == null || schema.get$ref() == null) {
            return schema;
        }
        Schema<?> resolved = lookupComponent(openApi, SCHEMAS_REF_PREFIX, schema.get$ref(),
                () -> openApi.getComponents().getSchemas());
        return resolved != null ? resolved : schema;
    }

    private RequestBody resolveRequestBody(RequestBody requestBody, OpenAPI openApi) {
        if (requestBody == null || requestBody.get$ref() == null) {
            return requestBody;
        }
        RequestBody resolved = lookupComponent(openApi, REQUEST_BODIES_REF_PREFIX, requestBody.get$ref(),
                () -> openApi.getComponents().getRequestBodies());
        return resolved != null ? resolved : requestBody;
    }

    private <T> T lookupComponent(OpenAPI openApi, String prefix, String ref, Supplier<Map<String, T>> components) {
        if (openApi.getComponents() == null || !ref.startsWith(prefix)) {
            return null;
        }
        String name = ref.substring(prefix.length());
        Map<String, T> map = components.get();
        return map == null ? null : map.get(name);
    }

    /**
     * 将 OpenAPI Schema 转为 JSON Schema 属性 Map，并清理悬空的 $ref 引用
     */
    private Map<String, Object> schemaToMap(Schema<?> schema) {
        if (schema == null) {
            return new LinkedHashMap<>();
        }
        Object converted = cleanDanglingRefs(SCHEMA_MAPPER.convertValue(schema,
                new TypeReference<Map<String, Object>>() {
                }));
        if (converted instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) map;
            return result;
        }
        return new LinkedHashMap<>();
    }

    /**
     * 递归将带 $ref 的节点替换为通用 object 描述（$ref 上下文未随工具下发，悬空引用不合法）
     */
    private Object cleanDanglingRefs(Object node) {
        if (node instanceof Map<?, ?> map) {
            if (map.containsKey("$ref")) {
                Map<String, Object> fallback = new LinkedHashMap<>();
                fallback.put("type", "object");
                Object description = map.get("description");
                if (description != null) {
                    fallback.put("description", description);
                }
                return fallback;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), cleanDanglingRefs(entry.getValue()));
            }
            return copy;
        }
        if (node instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(cleanDanglingRefs(item));
            }
            return copy;
        }
        return node;
    }

    private String writeJson(Object value) {
        try {
            return SCHEMA_MAPPER.writeValueAsString(value);
        }
        catch (Exception e) {
            return "{\"type\":\"object\",\"properties\":{}}";
        }
    }
}
