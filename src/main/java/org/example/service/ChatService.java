package org.example.service;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AiModelProperties;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.example.common.exception.ErrorCode;
import org.example.common.exception.ExternalServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 聊天服务
 * 封装 ReactAgent 对话的公共逻辑，包括模型创建、系统提示词构建、Agent 配置等
 */
@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private QueryMetricsTools queryMetricsTools;

    @Autowired(required = false)  // Mock 模式下才注册，所以设置为 optional,真实环境通过mcp配置注入
    private QueryLogsTools queryLogsTools;

    @Autowired
    private ToolCallbackProvider tools;

    @Autowired
    private AiModelProperties aiModelProperties;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 应用启动时输出最终生效的模型配置，便于确认当前 Agent 使用的模型。
     * 只记录 provider、模型和地址，不记录 API Key。
     */
    @PostConstruct
    public void logModelConfiguration() {
        String provider = normalized(aiModelProperties.getProvider(), "dashscope");
        String model = resolvedModelName(provider);
        String baseUrl = resolvedBaseUrl(provider);
        Duration timeout = resolvedTimeout();
        if (timeout.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalStateException("ai.model.timeout 过小: " + timeout
                    + "。请使用 180s、3m，或将 AI_MODEL_TIMEOUT 设置为 180s；裸数字按秒解释。");
        }
        logger.info("Responses 模型初始化完成: provider={}, model={}, baseUrl={}, timeout={}",
                provider, model, baseUrl, timeout);
    }

    /** 根据统一配置创建 Responses API ChatModel。 */
    public ChatModel createChatModel(double temperature, int maxToken, double topP) {
        String provider = normalized(aiModelProperties.getProvider(), "dashscope");
        if (!List.of("dashscope", "deepseek", "openai", "openai-compatible").contains(provider)) {
            throw new IllegalArgumentException("不支持的大模型 provider: " + provider
                    + "，当前支持 dashscope、deepseek、openai-compatible");
        }
        String canonicalProvider = "openai".equals(provider) ? "openai-compatible" : provider;
        String apiKey = resolveApiKey(canonicalProvider);
        String baseUrl = resolvedBaseUrl(canonicalProvider);
        String model = resolvedModelName(canonicalProvider);
        logger.info("创建 Responses ChatModel: provider={}, baseUrl={}, model={}", canonicalProvider, baseUrl, model);
        return new ResponsesChatModel(objectMapper, canonicalProvider, baseUrl, apiKey, model,
                temperature, maxToken, topP, resolvedTimeout());
    }

    /** 使用统一配置创建普通聊天模型。 */
    public ChatModel createStandardChatModel() {
        return createChatModel(0.7, 2000, 0.9);
    }

    /** 使用统一配置创建 AIOps 模型。 */
    public ChatModel createAiOpsChatModel() {
        return createChatModel(0.3, 8000, 0.9);
    }

    public ChatModel createChatModel(AgentProfileService.SamplingConfig sampling) {
        return createChatModel(sampling.temperature(), sampling.maxTokens(), sampling.topP());
    }

    public String currentModelProvider() {
        return normalized(aiModelProperties.getProvider(), "dashscope");
    }

    private String resolvedModelName(String provider) {
        if ("dashscope".equals(provider)) {
            return firstNonBlank(aiModelProperties.getModel(),
                    System.getenv("DASHSCOPE_CHAT_MODEL"), "qwen3.8-flash");
        }
        if ("deepseek".equals(provider)) {
            return firstNonBlank(aiModelProperties.getModel(),
                    System.getenv("DEEPSEEK_MODEL"), "deepseek-flash");
        }
        return firstNonBlank(aiModelProperties.getModel(),
                System.getenv("AI_MODEL_NAME"), "gpt-4o-mini");
    }

    private String resolvedBaseUrl(String provider) {
        if ("dashscope".equals(provider)) {
            return firstNonBlank(aiModelProperties.getBaseUrl(),
                    System.getenv("DASHSCOPE_BASE_URL"),
                    "https://dashscope.aliyuncs.com/compatible-mode/v1");
        }
        if ("deepseek".equals(provider)) {
            return firstNonBlank(aiModelProperties.getBaseUrl(),
                    System.getenv("DEEPSEEK_BASE_URL"), "https://api.deepseek.com");
        }
        return firstNonBlank(aiModelProperties.getBaseUrl(),
                "https://api.openai.com/v1");
    }

    private String resolveApiKey(String provider) {
        String apiKey = firstNonBlank(aiModelProperties.getApiKey(),
                "dashscope".equals(provider) ? System.getenv("DASHSCOPE_API_KEY") : null,
                "deepseek".equals(provider) ? System.getenv("DEEPSEEK_API_KEY") : null,
                System.getenv("AI_MODEL_API_KEY"));
        if (apiKey == null) {
            throw new IllegalStateException("Responses API Key 未配置，请设置 ai.model.api-key 或对应供应商环境变量");
        }
        return apiKey;
    }

    private Duration resolvedTimeout() {
        return aiModelProperties.getTimeout() == null
                ? Duration.ofSeconds(180) : aiModelProperties.getTimeout();
    }

    /**
     * 构建系统提示词（包含历史消息）
     * @param history 历史消息列表
     * @return 完整的系统提示词
     */
    public String buildSystemPrompt(List<Map<String, String>> history) {
        String basePrompt = "你是一个专业的智能助手，可以获取当前时间、查询天气信息、搜索内部文档知识库，以及查询 Prometheus 告警信息。\n"
                + "当用户询问时间相关问题时，使用 getCurrentDateTime 工具。\n"
                + "当用户需要查询公司内部文档、流程、最佳实践或技术指南时，使用 queryInternalDocs 工具。\n"
                + "当用户需要查询 Prometheus 告警、监控指标或系统告警状态时，使用 queryPrometheusAlerts 工具。\n"
                + "当用户需要查询腾讯云日志时，请调用腾讯云mcp服务查询,默认查询地域ap-guangzhou,查询时间范围为近一个月。\n"
                + "请基于对话历史回答用户的新问题。";
        return buildSystemPrompt(history, basePrompt);
    }

    public String buildSystemPrompt(List<Map<String, String>> history, String basePrompt) {
        StringBuilder systemPromptBuilder = new StringBuilder(basePrompt == null ? "" : basePrompt);
        systemPromptBuilder.append("\n\n");
        // 添加历史消息
        if (!history.isEmpty()) {
            systemPromptBuilder.append("--- 对话历史 ---\n");
            for (Map<String, String> msg : history) {
                String role = msg.get("role");
                String content = msg.get("content");
                if ("user".equals(role)) {
                    systemPromptBuilder.append("用户: ").append(content).append("\n");
                } else if ("assistant".equals(role)) {
                    systemPromptBuilder.append("助手: ").append(content).append("\n");
                }
            }
            systemPromptBuilder.append("--- 对话历史结束 ---\n\n");
        }
        
        return systemPromptBuilder.toString();
    }

    /**
     * 动态构建方法工具数组
     * 根据 cls.mock-enabled 决定是否包含 QueryLogsTools
     */
    public Object[] buildMethodToolsArray() {
        if (queryLogsTools != null) {
            // Mock 模式：包含 QueryLogsTools
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools};
        } else {
            // 真实模式：不包含 QueryLogsTools（由 MCP 提供日志查询功能）
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools};
        }
    }

    /**
     * 获取工具回调列表，mcp服务提供的工具
     */
    public ToolCallback[] getToolCallbacks() {
        return tools.getToolCallbacks();
    }

    /**
     * 记录可用工具列表：mcp服务提供的工具
     */
    public void logAvailableTools() {
        ToolCallback[] toolCallbacks = tools.getToolCallbacks();
        logger.info("可用工具列表:");
        for (ToolCallback toolCallback : toolCallbacks) {
            logger.info(">>> {}", toolCallback.getToolDefinition().name());
        }
    }

    /**
     * 创建 ReactAgent
     * @param chatModel 聊天模型
     * @param systemPrompt 系统提示词
     * @return 配置好的 ReactAgent
     */
    public ReactAgent createReactAgent(ChatModel chatModel, String systemPrompt) {
        return ReactAgent.builder()
                .name("intelligent_assistant")
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .methodTools(buildMethodToolsArray())
                .tools(getToolCallbacks())
                .build();
    }

    private String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toLowerCase();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * 执行 ReactAgent 对话（非流式）
     * @param agent ReactAgent 实例
     * @param question 用户问题
     * @return AI 回复
     */
    public String executeChat(ReactAgent agent, String question) {
        logger.info("执行 ReactAgent.call() - 自动处理工具调用");
        try {
            var response = agent.call(question);
            String answer = response.getText();
            logger.info("ReactAgent 对话完成，答案长度: {}", answer.length());
            return answer;
        } catch (GraphRunnerException e) {
            throw new ExternalServiceException(ErrorCode.MODEL_UNAVAILABLE, e);
        }
    }
}
