package org.example.common.aiops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Cloud-OpsBench 冻结工具回放提供者。
 *
 * 该类只从 tool_cache.json 返回结果，不持有任何真实 Prometheus、日志或
 * Kubernetes 客户端，从实现上保证 REPLAY 模式不会访问生产数据源。
 */
@Component
public class ReplayAiOpsToolProvider implements AiOpsToolProvider {
    private static final List<ToolSpec> TOOL_SPECS = List.of(
            new ToolSpec("GetAlerts", "获取案例中的告警和异常指标摘要", objectSchema(Map.of(
                    "namespace", stringSchema(), "service_name", stringSchema()))),
            new ToolSpec("GetRecentLogs", "获取案例中的近期原始日志", objectSchema(Map.of(
                    "namespace", stringSchema(), "service_name", stringSchema()))),
            new ToolSpec("GetErrorLogs", "获取案例中的错误日志摘要", objectSchema(Map.of(
                    "namespace", stringSchema(), "service_name", stringSchema()))),
            new ToolSpec("GetResources", "查询案例中的 Kubernetes 资源状态", objectSchema(Map.of(
                    "namespace", stringSchema(), "resource_type", anySchema(), "name", stringSchema(),
                    "output_wide", booleanSchema(), "show_labels", booleanSchema()))),
            new ToolSpec("DescribeResource", "查看案例中的 Kubernetes 资源详情", objectSchema(Map.of(
                    "namespace", stringSchema(), "resource_type", anySchema(), "name", stringSchema()))),
            new ToolSpec("GetAppYAML", "获取案例中的应用配置 YAML", objectSchema(Map.of(
                    "app_name", stringSchema()))),
            new ToolSpec("GetServiceDependencies", "获取案例中的服务依赖关系", objectSchema(Map.of(
                    "namespace", stringSchema(), "service_name", stringSchema()))),
            new ToolSpec("CheckServiceConnectivity", "检查案例中的服务连通性", objectSchema(Map.of(
                    "namespace", stringSchema(), "service_name", stringSchema(), "port", numberSchema())))
    );

    private final CloudOpsBenchCaseRepository caseRepository;
    private final ObjectMapper objectMapper;

    public ReplayAiOpsToolProvider(CloudOpsBenchCaseRepository caseRepository, ObjectMapper objectMapper) {
        this.caseRepository = caseRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolCallback[] getToolCallbacks(AiOpsRunContext context) {
        if (context == null || context.getMode() != AiOpsRunMode.REPLAY) {
            throw new IllegalArgumentException("ReplayAiOpsToolProvider 只能用于 REPLAY 模式");
        }
        CloudOpsBenchCase benchmarkCase = caseRepository.load(context.getCaseId());
        return TOOL_SPECS.stream().map(spec -> callback(spec, benchmarkCase)).toArray(ToolCallback[]::new);
    }

    private ToolCallback callback(ToolSpec spec, CloudOpsBenchCase benchmarkCase) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(spec.inputSchema())
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return lookup(spec.name(), toolInput, benchmarkCase);
            }
        };
    }

    private String lookup(String toolName, String toolInput, CloudOpsBenchCase benchmarkCase) {
        try {
            JsonNode input = toolInput == null || toolInput.isBlank()
                    ? objectMapper.createObjectNode() : objectMapper.readTree(toolInput);
            if (input == null || !input.isObject()) {
                return error("工具参数必须是 JSON 对象");
            }
            CacheMatch match = findBestMatch(toolName, input, benchmarkCase.getToolCache());
            if (match == null) {
                return error("回放数据中没有匹配的工具调用: " + toolName + " " + input);
            }
            return match.value();
        } catch (Exception exception) {
            return error("回放工具执行失败: " + exception.getMessage());
        }
    }

    private CacheMatch findBestMatch(String toolName, JsonNode input, Map<String, String> cache) throws Exception {
        List<CacheMatch> matches = new ArrayList<>();
        for (Map.Entry<String, String> entry : cache.entrySet()) {
            int separator = entry.getKey().indexOf(':');
            if (separator <= 0 || !toolName.equals(entry.getKey().substring(0, separator))) {
                continue;
            }
            JsonNode cachedInput = objectMapper.readTree(entry.getKey().substring(separator + 1));
            int score = matchScore(input, cachedInput);
            if (score >= 0) {
                matches.add(new CacheMatch(entry.getValue(), score, cachedInput.size()));
            }
        }
        return matches.stream()
                .max(Comparator.comparingInt(CacheMatch::score)
                        .thenComparingInt(match -> -match.cachedFieldCount()))
                .orElse(null);
    }

    /**
     * 只比较调用方实际传入的字段，允许 benchmark 为同一工具保存的缓存项
     * 带有 name=""、show_labels=false 等默认字段。
     */
    private int matchScore(JsonNode requested, JsonNode cached) {
        if (!requested.isObject() || !cached.isObject()) {
            return requested.equals(cached) ? 1 : -1;
        }
        int score = 0;
        Iterator<Map.Entry<String, JsonNode>> fields = requested.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode cachedValue = cached.get(field.getKey());
            if (cachedValue == null || !valuesEquivalent(field.getKey(), field.getValue(), cachedValue)) {
                return -1;
            }
            score++;
        }
        return score;
    }

    private boolean valuesEquivalent(String fieldName, JsonNode left, JsonNode right) {
        if ("resource_type".equals(fieldName)) {
            return normalizedResourceTypes(left).equals(normalizedResourceTypes(right));
        }
        if (left.isNumber() && right.isNumber()) {
            return left.decimalValue().compareTo(right.decimalValue()) == 0;
        }
        if (left.isArray() || right.isArray()) {
            return normalizedValues(left).equals(normalizedValues(right));
        }
        return left.asText().equals(right.asText());
    }

    private Set<String> normalizedResourceTypes(JsonNode node) {
        Set<String> values = normalizedValues(node);
        Set<String> normalized = new TreeSet<>();
        for (String value : values) {
            normalized.add(value);
            if (value.endsWith("s")) {
                normalized.add(value.substring(0, value.length() - 1));
            } else {
                normalized.add(value + "s");
            }
        }
        return normalized;
    }

    private Set<String> normalizedValues(JsonNode node) {
        Set<String> values = new TreeSet<>();
        if (node == null || node.isNull()) {
            return values;
        }
        if (node.isArray()) {
            node.forEach(item -> values.add(item.asText().trim().toLowerCase()));
        } else {
            values.add(node.asText().trim().toLowerCase());
        }
        return values;
    }

    private String error(String message) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("success", false);
        result.put("message", message);
        return result.toString();
    }

    private static String objectSchema(Map<String, String> properties) {
        StringBuilder builder = new StringBuilder("{\"type\":\"object\",\"properties\":{");
        boolean first = true;
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
        }
        return builder.append("}}").toString();
    }

    private static String stringSchema() {
        return "{\"type\":\"string\"}";
    }

    private static String booleanSchema() {
        return "{\"type\":\"boolean\"}";
    }

    private static String numberSchema() {
        return "{\"type\":\"integer\"}";
    }

    private static String anySchema() {
        return "{\"oneOf\":[{\"type\":\"string\"},{\"type\":\"array\",\"items\":{\"type\":\"string\"}}]}";
    }

    private record ToolSpec(String name, String description, String inputSchema) {
    }

    private record CacheMatch(String value, int score, int cachedFieldCount) {
    }
}
