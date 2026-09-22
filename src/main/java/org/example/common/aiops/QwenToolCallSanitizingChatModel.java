package org.example.common.aiops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 修复 Qwen 在工具调用历史中将 arguments 按非 mapping 解析，导致 DashScope
 * 返回 "Can only get item pairs from a mapping" 的兼容包装器。
 *
 * 工具本身仍由 Agent 正常执行；仅在下一轮模型请求中把历史工具调用和结果
 * 转成文本证据，避免把不兼容的 tool_calls 结构重新发给模型。
 */
public final class QwenToolCallSanitizingChatModel implements ChatModel {
    private final ChatModel delegate;
    private final ObjectMapper objectMapper;
    private final AtomicInteger supervisorCalls = new AtomicInteger();

    public QwenToolCallSanitizingChatModel(ChatModel delegate, ObjectMapper objectMapper) {
        this.delegate = delegate;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Prompt sanitized = sanitize(prompt);
        if (isSupervisorPrompt(sanitized) && supervisorCalls.get() >= 5) {
            return forcedFinish();
        }
        return delegate.call(sanitized);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        Prompt sanitized = sanitize(prompt);
        if (isSupervisorPrompt(sanitized) && supervisorCalls.get() >= 5) {
            return Flux.just(forcedFinish());
        }
        return delegate.stream(sanitized);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private Prompt sanitize(Prompt prompt) {
        if (prompt == null || prompt.getInstructions() == null) {
            return prompt;
        }
        List<Message> messages = new ArrayList<>(prompt.getInstructions().size());
        boolean changed = false;
        for (Message message : prompt.getInstructions()) {
            if (message instanceof SystemMessage systemMessage) {
                String content = systemMessage.getText();
                String normalized = content == null ? "" : content.toLowerCase(Locale.ROOT);
                if (normalized.contains("supervisor") && normalized.contains("planner_agent")) {
                    int callNumber = supervisorCalls.incrementAndGet();
                    if (callNumber >= 3) {
                        content = content + "\n\n【路由熔断】Supervisor 已连续多次重规划。禁止再次选择 planner_agent；" 
                                + "若已有任意工具结果，必须选择 FINISH；只有在必须执行已有计划时才允许选择 executor_agent。";
                        changed = true;
                    }
                }
                messages.add(new SystemMessage(content));
            }
            else if (message instanceof AssistantMessage assistantMessage
                    && assistantMessage.getToolCalls() != null
                    && !assistantMessage.getToolCalls().isEmpty()) {
                StringBuilder content = new StringBuilder(assistantMessage.getText() == null
                        ? "" : assistantMessage.getText());
                for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                    String arguments = normalizeArguments(toolCall.arguments());
                    content.append("\n[历史工具调用] ")
                            .append(toolCall.name())
                            .append(" 参数=")
                            .append(arguments);
                }
                messages.add(AssistantMessage.builder()
                        .content(content.toString())
                        .properties(assistantMessage.getMetadata())
                        .media(assistantMessage.getMedia())
                        .build());
                changed = true;
            }
            else if (message instanceof ToolResponseMessage toolResponseMessage) {
                StringBuilder content = new StringBuilder("[历史工具结果]");
                for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                    content.append("\n工具=").append(response.name())
                            .append("，调用ID=").append(response.id())
                            .append("，结果=").append(response.responseData());
                }
                messages.add(new UserMessage(content.toString()));
                changed = true;
            }
            else {
                messages.add(message);
            }
        }
        return changed ? new Prompt(messages, prompt.getOptions()) : prompt;
    }

    private String normalizeArguments(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) {
            return "{}";
        }
        String candidate = rawArguments.trim();
        try {
            JsonNode parsed = objectMapper.readTree(candidate);
            // 某些 Qwen 版本会将 JSON object 再包一层 JSON string。
            if (parsed != null && parsed.isTextual()) {
                JsonNode nested = objectMapper.readTree(parsed.asText());
                if (nested != null) {
                    parsed = nested;
                }
            }
            return parsed != null && parsed.isObject()
                    ? objectMapper.writeValueAsString(parsed) : "{}";
        }
        catch (Exception ignored) {
            return "{}";
        }
    }

    private boolean isSupervisorPrompt(Prompt prompt) {
        if (prompt == null || prompt.getInstructions() == null) {
            return false;
        }
        return prompt.getInstructions().stream()
                .filter(SystemMessage.class::isInstance)
                .map(Message::getText)
                .anyMatch(text -> text != null
                        && text.toLowerCase(Locale.ROOT).contains("supervisor")
                        && text.toLowerCase(Locale.ROOT).contains("planner_agent"));
    }

    private ChatResponse forcedFinish() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("{\"agent\":\"FINISH\"}"))));
    }
}
