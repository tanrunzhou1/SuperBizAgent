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
            // Qwen3.7-flash 对完全空参数 schema 偶尔会生成空字符串 arguments，
            // DashScope 服务端随后无法按 mapping 解析历史 tool_call。保留可选字段
            // 让模型稳定生成 JSON object；回放时仍固定命中官方 GetAlerts:{} 缓存键。
            new ToolSpec("GetAlerts", "获取案例中的告警和异常指标摘要（可选 namespace/service_name）", objectSchema(Map.of(
                    "namespace", stringSchema(), "service_name", stringSchema()))),
            new ToolSpec("GetRecentLogs", "获取案例中的近期原始日志", objectSchema(Map.of(
                    "namespace", stringSchema(), "service_name", stringSchema(), "lines", numberSchema()))),
            new ToolSpec("GetErrorLogs", "获取案例中的错误日志摘要", objectSchema(Map.of(
                    "namespace", stringSchema(), "service_name", stringSchema()))),
            new ToolSpec("GetResources", "查询案例中的 Kubernetes 资源状态", objectSchema(Map.of(
                    "namespace", stringSchema(), "resource_type", stringSchema(), "name", stringSchema(),
                    "output_wide", booleanSchema(), "show_labels", booleanSchema(),
                    "label_selector", stringSchema()))),
            new ToolSpec("DescribeResource", "查看案例中的 Kubernetes 资源详情", objectSchema(Map.of(
                    "namespace", stringSchema(), "resource_type", stringSchema(), "name", stringSchema()))),
            new ToolSpec("GetAppYAML", "获取案例中的应用配置 YAML", objectSchema(Map.of(
                    "app_name", stringSchema()))),
            new ToolSpec("GetServiceDependencies", "获取案例中的服务依赖关系", objectSchema(Map.of(
                    "service_name", stringSchema()))),
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
        JsonNode metadata = benchmarkCase.getMetadata();
        String namespace = text(metadata, "namespace");
        String query = text(metadata, "query");
        if (!namespace.isBlank()) {
            context.setNamespace(namespace);
        }
        if (!query.isBlank()) {
            // 测评输入以案例 metadata 为准，避免请求体中的简写/改写导致不可复现。
            context.setIncidentPrompt(query);
        }
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
            String validationError = validateRequiredParameters(toolName, input);
            if (validationError != null) {
                return error(validationError);
            }
            if ("GetRecentLogs".equals(toolName)) {
                return recentLogs(input, benchmarkCase);
            }
            // 官方 Cloud-OpsBench 工具的 GetAlerts 签名是无参，缓存键固定为 {}。
            if ("GetAlerts".equals(toolName)) {
                input = objectMapper.createObjectNode();
            }
            input = normalizeRequest(toolName, input);
            CacheMatch match = findBestMatch(toolName, input, benchmarkCase.getToolCache());
            if (match == null) {
                return error("回放数据中没有匹配的工具调用: " + toolName + " " + input);
            }
            if ("GetAlerts".equals(toolName) && (match.value() == null || match.value().isBlank())) {
                return "No active metric anomalies detected at this time.";
            }
            return match.value();
        } catch (Exception exception) {
            return error("回放工具执行失败: " + exception.getMessage());
        }
    }

    private String validateRequiredParameters(String toolName, JsonNode input) {
        return switch (toolName) {
            case "GetErrorLogs" -> requireFields(input, "namespace", "service_name");
            case "GetResources" -> requireFields(input, "resource_type");
            case "DescribeResource" -> requireFields(input, "resource_type", "name");
            case "GetAppYAML" -> requireFields(input, "app_name");
            case "GetServiceDependencies" -> requireFields(input, "service_name");
            case "CheckServiceConnectivity" -> requireFields(input, "namespace", "service_name", "port");
            default -> null;
        };
    }

    private String requireFields(JsonNode input, String... fields) {
        for (String field : fields) {
            JsonNode value = input.get(field);
            if (value == null || value.isNull() || value.isTextual() && value.asText().isBlank()) {
                return "工具参数缺少必填字段: " + field;
            }
        }
        return null;
    }

    private JsonNode normalizeRequest(String toolName, JsonNode input) {
        ObjectNode normalized = input.deepCopy();
        if ("GetServiceDependencies".equals(toolName)) {
            // 官方签名只有 service_name；兼容模型偶尔带上的 namespace，但不让它影响缓存键匹配。
            normalized.remove("namespace");
        }
        List<String> emptyFields = new ArrayList<>();
        normalized.fields().forEachRemaining(field -> {
            JsonNode value = field.getValue();
            if (value == null || value.isNull() || value.isTextual() && value.asText().isBlank()
                    || value.isBoolean() && !value.asBoolean()
                    || value.isArray() && value.isEmpty()) {
                emptyFields.add(field.getKey());
            }
        });
        normalized.remove(emptyFields);
        return normalized;
    }

    private String recentLogs(JsonNode input, CloudOpsBenchCase benchmarkCase) {
        String namespace = text(input, "namespace");
        String serviceName = text(input, "service_name");
        int lines = input.has("lines") && input.get("lines").canConvertToInt()
                ? Math.max(1, input.get("lines").asInt()) : 50;
        String expectedNamespace = text(benchmarkCase.getMetadata(), "namespace");
        if (namespace.isBlank() || serviceName.isBlank()) {
            return error("GetRecentLogs requires namespace and service_name");
        }
        if (!expectedNamespace.isBlank() && !expectedNamespace.equals(namespace)) {
            return error("GetRecentLogs expected namespace '" + expectedNamespace + "', got '" + namespace + "'");
        }

        List<String> collected = new ArrayList<>();
        appendLogSection(collected, benchmarkCase.getRawLogs().get(serviceName), "From " + serviceName + " logs:", lines);
        appendLogSection(collected, benchmarkCase.getRawLogs().get(serviceName + ".previous"),
                "From previous container logs:", lines);
        appendLogSection(collected, benchmarkCase.getRawLogs().get(serviceName + ".istio-proxy"),
                "From istio-proxy logs:", lines);
        if (collected.isEmpty()) {
            return "No recent logs found for " + serviceName + " in " + namespace + " namespace.";
        }
        return String.join("\n", collected);
    }

    private void appendLogSection(List<String> collected, JsonNode rawValue, String header, int lines) {
        if (rawValue == null || rawValue.isNull()) {
            return;
        }
        List<String> entries = new ArrayList<>();
        if (rawValue.isArray()) {
            rawValue.forEach(item -> {
                if (!item.asText().isBlank()) {
                    entries.add(item.asText());
                }
            });
        } else {
            String text = rawValue.asText();
            for (String line : text.split("\\R")) {
                if (!line.isBlank()) {
                    entries.add(line);
                }
            }
        }
        if (entries.isEmpty()) {
            return;
        }
        if (!collected.isEmpty()) {
            collected.add("");
        }
        collected.add(header);
        collected.addAll(entries.subList(Math.max(0, entries.size() - lines), entries.size()));
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
            String text = node.asText().trim();
            // Qwen 等模型有时会把官方的 string[] 参数编码成 JSON 字符串。
            if (text.startsWith("[") && text.endsWith("]")) {
                try {
                    JsonNode parsed = objectMapper.readTree(text);
                    if (parsed.isArray()) {
                        parsed.forEach(item -> values.add(item.asText().trim().toLowerCase()));
                        return values;
                    }
                } catch (Exception ignored) {
                    // 保持为普通字符串，由上层返回未命中，避免猜测参数含义。
                }
            }
            values.add(text.toLowerCase());
        }
        return values;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
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

    private record ToolSpec(String name, String description, String inputSchema) {
    }

    private record CacheMatch(String value, int score, int cachedFieldCount) {
    }
}
