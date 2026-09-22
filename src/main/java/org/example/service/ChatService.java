package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
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
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import static com.alibaba.cloud.ai.dashscope.common.DashScopeApiConstants.MULTIMODAL_GENERATION_RESTFUL_URL;

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

    @Value("${spring.ai.dashscope.api-key:}")
    private String dashScopeApiKey;

    @Value("${spring.ai.dashscope.chat.options.model:qwen-plus}")
    private String chatModelName;

    @Value("${spring.ai.dashscope.chat.options.multi-model:false}")
    private boolean multiModel;

    /**
     * 应用启动时输出最终生效的模型配置，便于确认当前 Agent 使用的模型。
     * 只记录 provider、模型和地址，不记录 API Key。
     */
    @PostConstruct
    public void logModelConfiguration() {
        String provider = normalized(aiModelProperties.getProvider(), "dashscope");
        String model = resolvedModelName(provider);
        String baseUrl = resolvedBaseUrl(provider);
        logger.info("AI 模型初始化完成: provider={}, model={}, baseUrl={}", provider, model, baseUrl);
    }

    /**
     * 创建 DashScope API 实例
     */
    public DashScopeApi createDashScopeApi() {
        DashScopeApi.Builder builder = DashScopeApi.builder()
                .apiKey(dashScopeApiKey);

        // ReactAgent 注册工具后，当前 Spring AI Alibaba 版本可能在选项合并时
        // 将 multiModel=true 覆盖为 false。显式设置端点以保证多模态模型走正确路径。
        if (multiModel) {
            builder.completionsPath(MULTIMODAL_GENERATION_RESTFUL_URL);
        }

        return builder.build();
    }

    /**
     * 创建 ChatModel
     * @param temperature 控制随机性 (0.0-1.0)
     * @param maxToken 最大输出长度
     * @param topP 核采样参数
     */
    public DashScopeChatModel createChatModel(DashScopeApi dashScopeApi, double temperature, int maxToken, double topP) {
        logger.info("创建 DashScope ChatModel: model={}, multiModel={}", chatModelName, multiModel);
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(chatModelName)
                        .multiModel(multiModel)
                        .withTemperature(temperature)
                        .withMaxToken(maxToken)
                        .withTopP(topP)
                        .build())
                .build();
    }

    /**
     * 创建标准对话 ChatModel（默认参数）
     */
    public DashScopeChatModel createStandardChatModel(DashScopeApi dashScopeApi) {
        return createChatModel(dashScopeApi, 0.7, 2000, 0.9);
    }

    /**
     * 根据 ai.model.provider 创建统一 ChatModel。
     * AIOps、Replay 和普通聊天都通过该入口获取模型，避免在 Controller 中绑定具体厂商类型。
     */
    public ChatModel createChatModel(double temperature, int maxToken, double topP) {
        String provider = normalized(aiModelProperties.getProvider(), "dashscope");
        if ("dashscope".equals(provider)) {
            DashScopeApi dashScopeApi = createDashScopeApi();
            return createChatModel(dashScopeApi, temperature, maxToken, topP);
        }
        if ("deepseek".equals(provider)) {
            return createDeepSeekChatModel(temperature, maxToken, topP);
        }
        if ("openai".equals(provider) || "openai-compatible".equals(provider)) {
            return createOpenAiCompatibleChatModel(temperature, maxToken, topP);
        }
        throw new IllegalArgumentException("不支持的大模型 provider: " + provider
                + "，当前支持 dashscope、deepseek、openai-compatible");
    }

    /** 使用统一配置创建普通聊天模型。 */
    public ChatModel createStandardChatModel() {
        return createChatModel(0.7, 2000, 0.9);
    }

    /** 使用统一配置创建 AIOps 模型。 */
    public ChatModel createAiOpsChatModel() {
        return createChatModel(0.3, 8000, 0.9);
    }

    public String currentModelProvider() {
        return normalized(aiModelProperties.getProvider(), "dashscope");
    }

    private String resolvedModelName(String provider) {
        if ("dashscope".equals(provider)) {
            return firstNonBlank(aiModelProperties.getModel(), chatModelName);
        }
        if ("deepseek".equals(provider)) {
            return firstNonBlank(aiModelProperties.getModel(), "deepseek-chat");
        }
        return firstNonBlank(aiModelProperties.getModel(), "gpt-4o-mini");
    }

    private String resolvedBaseUrl(String provider) {
        if ("dashscope".equals(provider)) {
            return multiModel
                    ? MULTIMODAL_GENERATION_RESTFUL_URL
                    : "https://dashscope.aliyuncs.com/api/v1";
        }
        if ("deepseek".equals(provider)) {
            return firstNonBlank(aiModelProperties.getBaseUrl(), "https://api.deepseek.com");
        }
        return firstNonBlank(aiModelProperties.getBaseUrl(), "https://api.openai.com");
    }

    private DeepSeekChatModel createDeepSeekChatModel(double temperature, int maxToken, double topP) {
        String apiKey = firstNonBlank(aiModelProperties.getApiKey(),
                System.getenv("DEEPSEEK_API_KEY"));
        if (apiKey == null) {
            throw new IllegalStateException("DeepSeek API Key 未配置，请设置 ai.model.api-key 或 DEEPSEEK_API_KEY");
        }
        String baseUrl = firstNonBlank(aiModelProperties.getBaseUrl(), "https://api.deepseek.com");
        String model = firstNonBlank(aiModelProperties.getModel(), "deepseek-chat");
        logger.info("创建 DeepSeek ChatModel: baseUrl={}, model={}", baseUrl, model);
        DeepSeekApi api = DeepSeekApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();
        return DeepSeekChatModel.builder()
                .deepSeekApi(api)
                .defaultOptions(DeepSeekChatOptions.builder()
                        .model(model)
                        .temperature(temperature)
                        .maxTokens(maxToken)
                        .topP(topP)
                        .build())
                .build();
    }

    private OpenAiChatModel createOpenAiCompatibleChatModel(double temperature, int maxToken, double topP) {
        String apiKey = firstNonBlank(aiModelProperties.getApiKey(),
                System.getenv("AI_MODEL_API_KEY"));
        if (apiKey == null) {
            throw new IllegalStateException("OpenAI-compatible API Key 未配置，请设置 ai.model.api-key 或 AI_MODEL_API_KEY");
        }
        String baseUrl = firstNonBlank(aiModelProperties.getBaseUrl(), "https://api.openai.com");
        String model = firstNonBlank(aiModelProperties.getModel(), "gpt-4o-mini");
        logger.info("创建 OpenAI-compatible ChatModel: baseUrl={}, model={}", baseUrl, model);
        OpenAiApi api = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(temperature)
                        .maxTokens(maxToken)
                        .topP(topP)
                        .build())
                .build();
    }

    /**
     * 构建系统提示词（包含历史消息）
     * @param history 历史消息列表
     * @return 完整的系统提示词
     */
    public String buildSystemPrompt(List<Map<String, String>> history) {
        StringBuilder systemPromptBuilder = new StringBuilder();
        
        // 基础系统提示
        systemPromptBuilder.append("你是一个专业的智能助手，可以获取当前时间、查询天气信息、搜索内部文档知识库，以及查询 Prometheus 告警信息。\n");
        systemPromptBuilder.append("当用户询问时间相关问题时，使用 getCurrentDateTime 工具。\n");
        systemPromptBuilder.append("当用户需要查询公司内部文档、流程、最佳实践或技术指南时，使用 queryInternalDocs 工具。\n");
        systemPromptBuilder.append("当用户需要查询 Prometheus 告警、监控指标或系统告警状态时，使用 queryPrometheusAlerts 工具。\n");
        systemPromptBuilder.append("当用户需要查询腾讯云日志时，请调用腾讯云mcp服务查询,默认查询地域ap-guangzhou,查询时间范围为近一个月。\n\n");
        
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
        
        systemPromptBuilder.append("请基于以上对话历史，回答用户的新问题。");
        
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

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
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
