package org.example.common.aiops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
            // Cloud-OpsBench 官方 GetAlerts 无参数，回放时固定命中 GetAlerts:{} 缓存键。
            new ToolSpec("GetAlerts", "获取案例中的告警和异常指标摘要。该工具不需要参数。", emptyObjectSchema()),
            // 官方签名只有 namespace 和 service_name；日志行数由快照工具内部处理。
            new ToolSpec("GetRecentLogs", "获取指定 Kubernetes 服务的近期原始日志。", objectSchema(
                    Map.of("namespace", stringSchema("必填。Kubernetes namespace。"),
                            "service_name", stringSchema("必填。微服务名称，不是 Pod 完整名称。")),
                    "namespace", "service_name")),
            new ToolSpec("GetErrorLogs", "获取指定 Kubernetes 服务的错误日志统计摘要。", objectSchema(
                    Map.of("namespace", stringSchema("必填。Kubernetes namespace。"),
                            "service_name", stringSchema("必填。微服务名称。")),
                    "namespace", "service_name")),
            new ToolSpec("GetResources", "查询案例中的 Kubernetes 资源状态", ""),
            new ToolSpec("DescribeResource", "查看指定 Kubernetes 资源的详细状态、事件和条件。", objectSchema(
                    Map.of("namespace", stringSchema("可选。namespaced 资源应提供 namespace。"),
                            "resource_type", stringSchema("必填。资源类型，例如 pod、service、deployment。"),
                            "name", stringSchema("必填。要查看的资源精确名称。")),
                    "resource_type", "name")),
            new ToolSpec("GetAppYAML", "获取指定微服务的应用配置 YAML。", objectSchema(
                    Map.of("app_name", stringSchema("必填。要查看配置的微服务名称。")), "app_name")),
            new ToolSpec("GetServiceDependencies", "获取指定服务的上下游服务依赖关系。", objectSchema(
                    Map.of("service_name", stringSchema("必填。服务名称。")), "service_name")),
            new ToolSpec("CheckServiceConnectivity", "检查指定服务端口的集群内 TCP 连通性。", objectSchema(
                    Map.of("namespace", stringSchema("必填。Kubernetes namespace。"),
                            "service_name", stringSchema("必填。目标 Service DNS 名称。"),
                            "port", numberSchema("必填。目标 TCP 端口。")),
                    "service_name", "port", "namespace")),
            new ToolSpec("GetClusterConfiguration", "获取案例中的集群节点状态和配置", emptyObjectSchema()),
            new ToolSpec("CheckNodeServiceStatus", "查询指定节点上的系统组件状态。", objectSchema(
                    Map.of("node_name", enumSchema("节点名称。", "master", "worker-01", "worker-02", "worker-03"),
                            "service_name", enumSchema("系统组件名称。", "kube-scheduler", "kubelet", "kube-proxy", "containerd")),
                    "node_name", "service_name")),
            new ToolSpec("ListCodeFiles", "列出 Boutique 微服务的源码文件。", objectSchema(
                    Map.of("app_name", stringSchema("必填。Boutique 微服务名称。")), "app_name")),
            new ToolSpec("GetSourceCode", "读取已通过 ListCodeFiles 列出的源码文件。", objectSchema(
                    Map.of("app_name", stringSchema("必填。Boutique 微服务名称。"),
                            "file_path", stringSchema("必填。必须是 ListCodeFiles 返回的相对路径。")),
                    "app_name", "file_path"))
    );

    private static final Set<String> BOUTIQUE_CODE_SERVICES = Set.of(
            "adservice", "cartservice", "checkoutservice", "currencyservice", "emailservice",
            "frontend", "paymentservice", "productcatalogservice", "recommendationservice", "shippingservice");
    private static final Set<String> NODE_NAMES = Set.of("master", "worker-01", "worker-02", "worker-03");
    private static final Set<String> SYSTEM_SERVICES = Set.of("kube-scheduler", "kubelet", "kube-proxy", "containerd");
    private static final Set<String> BOUTIQUE_ONLY_TOOLS = Set.of("ListCodeFiles", "GetSourceCode");

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
        boolean boutique = context.getCaseId().startsWith("boutique/");
        boolean getResourcesV2 = context.getCaseId().startsWith("trainticket/")
                || context.getCaseId().startsWith("boutique/codedefect/");
        return TOOL_SPECS.stream()
                .filter(spec -> boutique || !BOUTIQUE_ONLY_TOOLS.contains(spec.name()))
                // Replay 模式只向模型暴露当前快照真正支持的工具，避免模型调用
                // 没有对应 tool_cache 的工具（例如某些案例不存在 GetClusterConfiguration）。
                .filter(spec -> isReplayToolAvailable(spec.name(), benchmarkCase))
                .map(spec -> "GetResources".equals(spec.name())
                        ? new ToolSpec(spec.name(), spec.description(), getResourcesSchema(getResourcesV2))
                        : spec)
                .map(spec -> callback(spec, benchmarkCase))
                .toArray(ToolCallback[]::new);
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
            if ("ListCodeFiles".equals(toolName)) {
                return listCodeFiles(input, benchmarkCase);
            }
            if ("GetSourceCode".equals(toolName)) {
                return getSourceCode(input, benchmarkCase);
            }
            if ("CheckNodeServiceStatus".equals(toolName)) {
                String nodeName = text(input, "node_name");
                String serviceName = text(input, "service_name");
                if (!NODE_NAMES.contains(nodeName)) {
                    return error("node_name must be one of " + NODE_NAMES);
                }
                if (!SYSTEM_SERVICES.contains(serviceName)) {
                    return error("service_name must be one of " + SYSTEM_SERVICES);
                }
                if ("kube-scheduler".equals(serviceName) && !"master".equals(nodeName)) {
                    return "Error: 'kube-scheduler' is not expected to run on node '" + nodeName
                            + "' in this benchmark setup. Query the 'master' node instead.";
                }
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
            case "ListCodeFiles" -> requireFields(input, "app_name");
            case "GetSourceCode" -> requireFields(input, "app_name", "file_path");
            case "GetServiceDependencies" -> requireFields(input, "service_name");
            case "CheckServiceConnectivity" -> requireFields(input, "namespace", "service_name", "port");
            case "CheckNodeServiceStatus" -> requireFields(input, "node_name", "service_name");
            default -> null;
        };
    }

    private String listCodeFiles(JsonNode input, CloudOpsBenchCase benchmarkCase) {
        String appName = text(input, "app_name");
        if (!benchmarkCase.getCaseId().startsWith("boutique/")) {
            return error("ListCodeFiles is only available for boutique.");
        }
        if (!BOUTIQUE_CODE_SERVICES.contains(appName)) {
            return error("ListCodeFiles does not support service '" + appName + "' for boutique.");
        }
        try {
            CacheMatch match = findBestMatch("ListCodeFiles", objectNode("app_name", appName), benchmarkCase.getToolCache());
            if (match == null) {
                return "Error: Code file list for '" + appName + "' is not recorded.";
            }
            JsonNode listing = objectMapper.readTree(match.value());
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(listing);
        } catch (Exception exception) {
            return error("Invalid code file listing for '" + appName + "': " + exception.getMessage());
        }
    }

    private String getSourceCode(JsonNode input, CloudOpsBenchCase benchmarkCase) {
        String appName = text(input, "app_name");
        String filePath = text(input, "file_path");
        if (!benchmarkCase.getCaseId().startsWith("boutique/")) {
            return error("GetSourceCode is only available for boutique.");
        }
        if (!BOUTIQUE_CODE_SERVICES.contains(appName)) {
            return error("GetSourceCode does not support service '" + appName + "' for boutique.");
        }

        try {
            CacheMatch match = findBestMatch("ListCodeFiles", objectNode("app_name", appName), benchmarkCase.getToolCache());
            if (match == null) {
                return "Error: Code file list for '" + appName + "' is not recorded.";
            }
            JsonNode listing = objectMapper.readTree(match.value());
            JsonNode files = listing.path("files");
            boolean listed = false;
            if (files.isArray()) {
                for (JsonNode file : files) {
                    if (filePath.equals(text(file, "path"))) {
                        listed = true;
                        break;
                    }
                }
            }
            if (!listed) {
                return "Error: file_path must exactly match a path returned by ListCodeFiles(app_name='" + appName + "').";
            }

            Path relativePath = Path.of(filePath);
            if (relativePath.isAbsolute() || relativePath.normalize().startsWith("..")) {
                return "Error: absolute paths and parent-directory traversal are not accepted.";
            }
            Path serviceRoot = benchmarkCase.getDirectory().resolve("code").resolve(appName).normalize();
            Path sourcePath = serviceRoot.resolve(relativePath).normalize();
            if (!sourcePath.startsWith(serviceRoot)) {
                return "Error: file_path resolves outside the selected service code directory.";
            }
            if (!Files.isRegularFile(sourcePath)) {
                return "Error: Source file '" + filePath + "' for '" + appName + "' is not found in this case.";
            }
            return Files.readString(sourcePath, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return error("Unable to read source file '" + filePath + "': " + exception.getMessage());
        } catch (Exception exception) {
            return error("Unable to load code file listing for '" + appName + "': " + exception.getMessage());
        }
    }

    private ObjectNode objectNode(String key, String value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put(key, value);
        return node;
    }

    private static String getResourcesSchema(boolean v2) {
        Map<String, String> properties = new java.util.LinkedHashMap<>();
        properties.put("resource_type", stringSchema("必填。资源类型，例如 pods、services、deployments、endpoints。"));
        properties.put("namespace", stringSchema("可选。namespaced 资源建议提供 namespace；集群级资源可省略。"));
        properties.put("name", stringSchema("可选。资源名称；省略时返回列表。"));
        properties.put("show_labels", booleanSchema("可选。是否显示 labels，不能与 output_wide 同时为 true。"));
        properties.put("output_wide", booleanSchema("可选。是否输出 wide 信息，不能与 show_labels 同时为 true。"));
        if (v2) {
            properties.put("output_yaml", booleanSchema("可选。是否返回 YAML；仅支持官方允许的资源类型。"));
        } else {
            properties.put("label_selector", stringSchema("可选。简单 key=value 标签选择器。"));
        }
        return objectSchema(properties, "resource_type");
    }

    private boolean isReplayToolAvailable(String toolName, CloudOpsBenchCase benchmarkCase) {
        if ("GetAlerts".equals(toolName)) {
            return benchmarkCase.getToolCache().keySet().stream()
                    .anyMatch(key -> key.startsWith("GetAlerts:"));
        }
        if ("GetRecentLogs".equals(toolName)) {
            return benchmarkCase.getRawLogs() != null && !benchmarkCase.getRawLogs().isEmpty();
        }
        if ("ListCodeFiles".equals(toolName) || "GetSourceCode".equals(toolName)) {
            return true;
        }
        return benchmarkCase.getToolCache().keySet().stream()
                .anyMatch(key -> key.startsWith(toolName + ":"));
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
        List<JsonNode> requestVariants = new ArrayList<>();
        requestVariants.add(input);
        if ("GetResources".equals(toolName) && input.isObject()) {
            JsonNode outputYaml = input.get("output_yaml");
            JsonNode output = input.get("output");
            if (outputYaml != null && outputYaml.asBoolean(false)) {
                ObjectNode legacy = ((ObjectNode) input).deepCopy();
                legacy.remove("output_yaml");
                legacy.put("output", "yaml");
                requestVariants.add(legacy);
            }
            if (output != null && "yaml".equalsIgnoreCase(output.asText())) {
                ObjectNode current = ((ObjectNode) input).deepCopy();
                current.remove("output");
                current.put("output_yaml", true);
                requestVariants.add(current);
            }
        }
        for (Map.Entry<String, String> entry : cache.entrySet()) {
            int separator = entry.getKey().indexOf(':');
            if (separator <= 0 || !toolName.equals(entry.getKey().substring(0, separator))) {
                continue;
            }
            JsonNode cachedInput = objectMapper.readTree(entry.getKey().substring(separator + 1));
            int score = requestVariants.stream().mapToInt(request -> matchScore(request, cachedInput)).max().orElse(-1);
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

    private static String objectSchema(Map<String, String> properties, String... required) {
        StringBuilder builder = new StringBuilder("{\"type\":\"object\",\"properties\":{");
        boolean first = true;
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
        }
        builder.append('}');
        if (required.length > 0) {
            builder.append(",\"required\":[");
            for (int index = 0; index < required.length; index++) {
                if (index > 0) {
                    builder.append(',');
                }
                builder.append('"').append(required[index]).append('"');
            }
            builder.append(']');
        }
        return builder.append(",\"additionalProperties\":false}").toString();
    }

    private static String stringSchema() {
        return "{\"type\":\"string\"}";
    }

    private static String stringSchema(String description) {
        return "{\"type\":\"string\",\"description\":\"" + escapeJson(description) + "\"}";
    }

    private static String enumSchema(String description, String... values) {
        StringBuilder builder = new StringBuilder("{\"type\":\"string\",\"enum\":[");
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append('"').append(values[index]).append('"');
        }
        return builder.append("],\"description\":\"")
                .append(escapeJson(description)).append("\"}").toString();
    }

    private static String booleanSchema() {
        return "{\"type\":\"boolean\"}";
    }

    private static String booleanSchema(String description) {
        return "{\"type\":\"boolean\",\"description\":\"" + escapeJson(description) + "\"}";
    }

    private static String numberSchema() {
        return "{\"type\":\"integer\"}";
    }

    private static String numberSchema(String description) {
        return "{\"type\":\"integer\",\"description\":\"" + escapeJson(description) + "\"}";
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String emptyObjectSchema() {
        return "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
    }

    private record ToolSpec(String name, String description, String inputSchema) {
    }

    private record CacheMatch(String value, int score, int cachedFieldCount) {
    }
}
