package org.example.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Responses API compatible ChatModel.
 *
 * <p>The Responses API is intentionally handled here instead of using the provider
 * specific Spring AI ChatModel implementations. This keeps ordinary chat and AIOps
 * on one wire format while preserving the ChatModel contract required by ReactAgent.</p>
 */
public final class ResponsesChatModel implements ChatModel {

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final String provider;
    private final String baseUrl;
    private final String apiKey;
    private final String defaultModel;
    private final double defaultTemperature;
    private final int defaultMaxOutputTokens;
    private final double defaultTopP;
    private final Duration timeout;

    public ResponsesChatModel(ObjectMapper objectMapper,
            String provider,
            String baseUrl,
            String apiKey,
            String model,
            double temperature,
            int maxOutputTokens,
            double topP,
            Duration timeout) {
        this.objectMapper = objectMapper;
        this.provider = provider;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiKey = apiKey;
        this.defaultModel = model;
        this.defaultTemperature = temperature;
        this.defaultMaxOutputTokens = maxOutputTokens;
        this.defaultTopP = topP;
        this.timeout = timeout;
        this.webClient = WebClient.builder()
                .baseUrl(responseEndpoint(this.baseUrl))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ObjectNode request = buildRequest(prompt, false);
        JsonNode response = webClient.post()
                .bodyValue(request)
                .retrieve()
                .onStatus(status -> status.isError(), clientResponse ->
                        clientResponse.bodyToMono(String.class)
                                .defaultIfEmpty("响应 API 返回 HTTP " + clientResponse.statusCode().value())
                                .map(body -> new ResponsesApiException(provider, clientResponse.statusCode().value(), body)))
                .bodyToMono(JsonNode.class)
                .timeout(timeout)
                .block();
        if (response == null) {
            throw new ResponsesApiException(provider, 200, "响应 API 返回空结果");
        }
        return toChatResponse(response, true);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            ObjectNode request = buildRequest(prompt, true);
            StreamState state = new StreamState();
            return webClient.post()
                    .bodyValue(request)
                    .retrieve()
                    .onStatus(status -> status.isError(), clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .defaultIfEmpty("响应 API 返回 HTTP " + clientResponse.statusCode().value())
                                    .map(body -> new ResponsesApiException(provider, clientResponse.statusCode().value(), body)))
                    .bodyToFlux(SSE_TYPE)
                    .timeout(timeout)
                    .handle((event, sink) -> handleEvent(event, state, sink));
        });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return DefaultToolCallingChatOptions.builder()
                .model(defaultModel)
                .temperature(defaultTemperature)
                .maxTokens(defaultMaxOutputTokens)
                .topP(defaultTopP)
                .internalToolExecutionEnabled(false)
                .build();
    }

    private ObjectNode buildRequest(Prompt prompt, boolean stream) {
        ObjectNode request = objectMapper.createObjectNode();
        ChatOptions options = prompt.getOptions();
        request.put("model", resolveModel(options));
        request.put("stream", stream);
        request.put("temperature", resolveDouble(options == null ? null : options.getTemperature(), defaultTemperature));
        request.put("top_p", resolveDouble(options == null ? null : options.getTopP(), defaultTopP));
        request.put("max_output_tokens", resolveInteger(options == null ? null : options.getMaxTokens(), defaultMaxOutputTokens));

        ArrayNode input = request.putArray("input");
        for (Message message : prompt.getInstructions()) {
            appendMessage(input, message);
        }

        if (options instanceof ToolCallingChatOptions toolOptions) {
            appendTools(request, toolOptions.getToolCallbacks());
        }
        return request;
    }

    private void appendMessage(ArrayNode input, Message message) {
        if (message instanceof ToolResponseMessage toolResponseMessage) {
            for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                ObjectNode item = input.addObject();
                item.put("type", "function_call_output");
                item.put("call_id", response.id());
                item.put("output", response.responseData() == null ? "" : response.responseData());
            }
            return;
        }

        if (message instanceof AssistantMessage assistantMessage) {
            if (assistantMessage.getText() != null && !assistantMessage.getText().isBlank()) {
                appendTextMessage(input, "assistant", assistantMessage.getText(), "output_text");
            }
            for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                ObjectNode item = input.addObject();
                item.put("type", "function_call");
                item.put("call_id", toolCall.id());
                item.put("name", toolCall.name());
                item.put("arguments", toolCall.arguments());
            }
            return;
        }

        String role = switch (message.getMessageType()) {
            case SYSTEM -> "system";
            case USER -> "user";
            default -> throw new IllegalArgumentException("Responses API 不支持的消息类型: " + message.getMessageType());
        };
        appendTextMessage(input, role, message.getText() == null ? "" : message.getText(), "input_text");
    }

    private void appendTextMessage(ArrayNode input, String role, String text, String contentType) {
        ObjectNode item = input.addObject();
        item.put("type", "message");
        item.put("role", role);
        ArrayNode content = item.putArray("content");
        ObjectNode part = content.addObject();
        part.put("type", contentType);
        part.put("text", text);
    }

    private void appendTools(ObjectNode request, List<ToolCallback> callbacks) {
        if (callbacks == null || callbacks.isEmpty()) {
            return;
        }
        ArrayNode tools = request.putArray("tools");
        for (ToolCallback callback : callbacks) {
            ToolDefinition definition = callback.getToolDefinition();
            ObjectNode tool = tools.addObject();
            tool.put("type", "function");
            tool.put("name", definition.name());
            if (definition.description() != null) {
                tool.put("description", definition.description());
            }
            try {
                tool.set("parameters", objectMapper.readTree(definition.inputSchema()));
            }
            catch (JsonProcessingException exception) {
                throw new IllegalArgumentException("工具参数 Schema 不是合法 JSON: " + definition.name(), exception);
            }
        }
    }

    private ChatResponse toChatResponse(JsonNode response, boolean includeText) {
        StringBuilder text = new StringBuilder();
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        JsonNode output = response.path("output");
        if (output.isArray()) {
            for (JsonNode item : output) {
                if ("message".equals(item.path("type").asText()) && includeText) {
                    for (JsonNode content : item.path("content")) {
                        if ("output_text".equals(content.path("type").asText())) {
                            text.append(content.path("text").asText(""));
                        }
                    }
                }
                if ("function_call".equals(item.path("type").asText())) {
                    toolCalls.add(new AssistantMessage.ToolCall(
                            item.path("call_id").asText(item.path("id").asText()),
                            "function",
                            item.path("name").asText(),
                            item.path("arguments").asText("{}")));
                }
            }
        }

        AssistantMessage assistantMessage = AssistantMessage.builder()
                .content(text.toString())
                .toolCalls(toolCalls)
                .build();
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder()
                .id(response.path("id").asText(""))
                .model(response.path("model").asText(defaultModel))
                .keyValue("provider", provider)
                .keyValue("status", response.path("status").asText(""));
        JsonNode usage = response.path("usage");
        if (usage.isObject()) {
            metadata.usage(new DefaultUsage(
                    nullableInt(usage, "input_tokens"),
                    nullableInt(usage, "output_tokens"),
                    nullableInt(usage, "total_tokens"),
                    usage));
        }
        return new ChatResponse(List.of(new Generation(assistantMessage)), metadata.build());
    }

    private void handleEvent(ServerSentEvent<String> event, StreamState state,
            reactor.core.publisher.SynchronousSink<ChatResponse> sink) {
        String eventType = event.event();
        String data = event.data();
        if (data == null || data.isBlank()) {
            return;
        }
        try {
            JsonNode payload = objectMapper.readTree(data);
            if ("response.output_text.delta".equals(eventType)) {
                String delta = payload.path("delta").asText("");
                if (!delta.isEmpty()) {
                    state.textEmitted = true;
                    sink.next(toDeltaResponse(delta));
                }
            }
            else if ("response.completed".equals(eventType) || "response.incomplete".equals(eventType)) {
                JsonNode response = payload.path("response");
                if (response.isObject()) {
                    ChatResponse finalResponse = toChatResponse(response, !state.textEmitted);
                    if (finalResponse.hasToolCalls() || !state.textEmitted) {
                        sink.next(finalResponse);
                    }
                }
            }
            else if ("response.failed".equals(eventType)) {
                JsonNode response = payload.path("response");
                sink.error(new ResponsesApiException(provider, 502,
                        response.path("error").path("message").asText("Responses API 调用失败")));
            }
        }
        catch (JsonProcessingException exception) {
            sink.error(new ResponsesApiException(provider, 502, "无法解析 Responses SSE 数据: " + data, exception));
        }
    }

    private ChatResponse toDeltaResponse(String delta) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(delta))),
                ChatResponseMetadata.builder().model(defaultModel).keyValue("provider", provider).build());
    }

    private String resolveModel(ChatOptions options) {
        return options != null && options.getModel() != null && !options.getModel().isBlank()
                ? options.getModel() : defaultModel;
    }

    private static double resolveDouble(Double value, double fallback) {
        return value == null ? fallback : value;
    }

    private static int resolveInteger(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static Integer nullableInt(JsonNode node, String field) {
        return node.has(field) && node.get(field).isNumber() ? node.get(field).asInt() : null;
    }

    private static String trimTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String responseEndpoint(String baseUrl) {
        return baseUrl.endsWith("/responses") ? baseUrl : baseUrl + "/responses";
    }

    private static final class StreamState {
        private boolean textEmitted;
    }

    public static final class ResponsesApiException extends RuntimeException {
        public ResponsesApiException(String provider, int status, String body) {
            super("Responses API 调用失败: provider=" + provider + ", status=" + status + ", body=" + body);
        }

        public ResponsesApiException(String provider, int status, String body, Throwable cause) {
            super("Responses API 调用失败: provider=" + provider + ", status=" + status + ", body=" + body, cause);
        }
    }
}
