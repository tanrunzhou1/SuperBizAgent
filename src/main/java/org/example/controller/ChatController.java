package org.example.controller;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.Getter;
import lombok.Setter;
import org.example.common.api.ApiError;
import org.example.common.api.ApiResponse;
import org.example.common.api.ChatSessionContext;
import org.example.common.api.SseMessage;
import org.example.common.api.TraceIdContext;
import org.example.common.exception.AppException;
import org.example.common.exception.BusinessException;
import org.example.common.exception.ErrorCode;
import org.example.service.AiOpsService;
import org.example.service.ChatService;
import org.example.service.ChatSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 统一 API 控制器
 * 适配前端接口需求
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    @Autowired
    private AiOpsService aiOpsService;
    
    @Autowired
    private ChatService chatService;

    @Autowired
    private ChatSessionService chatSessionService;

    @Autowired
    private ToolCallbackProvider tools;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * 普通对话接口（支持工具调用）
     * 与 /chat_react 逻辑一致，但直接返回完整结果而非流式输出
     */
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request) {
        logger.info("收到对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "问题内容不能为空");
        }

        String sessionId = chatSessionService.getOrCreateSession(request.getId());
        List<Map<String, String>> history = chatSessionService.loadHistory(sessionId);
        logger.info("会话历史消息对数: {}", history.size() / 2);

        ChatSessionContext.set(sessionId);
        try {

        DashScopeApi dashScopeApi = chatService.createDashScopeApi();
        DashScopeChatModel chatModel = chatService.createStandardChatModel(dashScopeApi);

        chatService.logAvailableTools();

        logger.info("开始 ReactAgent 对话（支持自动工具调用）");
        String systemPrompt = chatService.buildSystemPrompt(history);
        ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt);
        String fullAnswer = chatService.executeChat(agent, request.getQuestion());
        chatSessionService.saveTurn(sessionId, request.getQuestion(), fullAnswer, TraceIdContext.getOrCreate());
        logger.info("已更新会话历史 - SessionId: {}", sessionId);
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(fullAnswer)));
        } finally {
            ChatSessionContext.clear();
        }
    }

    /**
     * 清空会话历史
     */
    @PostMapping("/chat/clear")
    public ResponseEntity<ApiResponse<String>> clearChatHistory(@RequestBody ClearRequest request) {
        logger.info("收到清空会话历史请求 - SessionId: {}", request.getId());

        if (request.getId() == null || request.getId().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "会话ID不能为空");
        }

        if (chatSessionService.findSession(request.getId()).isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "会话不存在");
        }
        chatSessionService.clear(request.getId());
        return ResponseEntity.ok(ApiResponse.success("会话历史已清空"));
    }

    /**
     * ReactAgent 对话接口（SSE 流式模式，支持多轮对话，支持自动工具调用，例如获取当前时间，查询日志，告警等）
     * 支持 session 管理，保留对话历史
     */
    @PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时
        String traceId = TraceIdContext.getOrCreate();

        // 参数校验
        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            logger.warn("问题内容为空");
            try {
                sendSseError(emitter, new BusinessException(ErrorCode.INVALID_REQUEST, "问题内容不能为空"));
                emitter.complete();
            } catch (IOException e) {
                emitter.complete();
            }
            return emitter;
        }

        executor.execute(() -> {
            try {
                logger.info("收到 ReactAgent 对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

                String sessionId = chatSessionService.getOrCreateSession(request.getId());
                List<Map<String, String>> history = chatSessionService.loadHistory(sessionId);
                ChatSessionContext.set(sessionId);
                logger.info("ReactAgent 会话历史消息对数: {}", history.size() / 2);

                // 创建 DashScope API 和 ChatModel
                DashScopeApi dashScopeApi = chatService.createDashScopeApi();
                DashScopeChatModel chatModel = chatService.createStandardChatModel(dashScopeApi);

                // 记录可用工具
                chatService.logAvailableTools();

                logger.info("开始 ReactAgent 流式对话（支持自动工具调用）");
                
                // 构建系统提示词（包含历史消息）
                String systemPrompt = chatService.buildSystemPrompt(history);
                
                // 创建 ReactAgent
                ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt);
                
                // 用于累积完整答案
                StringBuilder fullAnswerBuilder = new StringBuilder();
                
                // 使用 agent.stream() 进行流式对话
                Flux<NodeOutput> stream = agent.stream(request.getQuestion());
                
                stream.subscribe(
                    output -> {
                        try {
                            // 检查是否为 StreamingOutput 类型
                            if (output instanceof StreamingOutput streamingOutput) {
                                OutputType type = streamingOutput.getOutputType();
                                
                                // 处理模型推理的流式输出
                                if (type == OutputType.AGENT_MODEL_STREAMING) {
                                    // 流式增量内容，逐步显示
                                    String chunk = streamingOutput.message().getText();
                                    if (chunk != null && !chunk.isEmpty()) {
                                        fullAnswerBuilder.append(chunk);
                                        
                                        // 实时发送到前端
                                        emitter.send(SseEmitter.event()
                                                .name("message")
                                                .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
                                        
                                        logger.info("发送流式内容: {}", chunk);
                                    }
                                } else if (type == OutputType.AGENT_MODEL_FINISHED) {
                                    // 模型推理完成
                                    logger.info("模型输出完成");
                                } else if (type == OutputType.AGENT_TOOL_FINISHED) {
                                    // 工具调用完成
                                    logger.info("工具调用完成: {}", output.node());
                                } else if (type == OutputType.AGENT_HOOK_FINISHED) {
                                    // Hook 执行完成
                                    logger.debug("Hook 执行完成: {}", output.node());
                                }
                            }
                        } catch (IOException e) {
                            logger.error("发送流式消息失败", e);
                            throw new RuntimeException(e);
                        }
                    },
                    error -> {
                        // 错误处理
                        logger.error("ReactAgent 流式对话失败", error);
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(SseMessage.error(toApiError(error)), MediaType.APPLICATION_JSON));
                        } catch (IOException ex) {
                            logger.error("发送错误消息失败", ex);
                        }
                        emitter.complete();
                        ChatSessionContext.clear();
                    },
                    () -> {
                        // 完成处理
                        try {
                            String fullAnswer = fullAnswerBuilder.toString();
                            logger.info("ReactAgent 流式对话完成 - SessionId: {}, 答案长度: {}",
                                sessionId, fullAnswer.length());
                            
                            chatSessionService.saveTurn(sessionId, request.getQuestion(), fullAnswer, traceId);
                            logger.info("已更新会话历史 - SessionId: {}", sessionId);
                            
                            // 发送完成标记
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(SseMessage.done(), MediaType.APPLICATION_JSON));
                            emitter.complete();
                            ChatSessionContext.clear();
                        } catch (IOException e) {
                            logger.error("发送完成消息失败", e);
                            emitter.completeWithError(e);
                        }
                    }
                );

            } catch (Exception e) {
                logger.error("ReactAgent 对话初始化失败", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(SseMessage.error(toApiError(e)), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.complete();
                ChatSessionContext.clear();
            }
        });

        return emitter;
    }

    /**
     * AI 智能运维接口（SSE 流式模式）- 自动分析告警并生成运维报告
     * 无需用户输入，自动执行告警分析流程
     */
    @PostMapping(value = "/ai_ops", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter aiOps() {
        SseEmitter emitter = new SseEmitter(600000L); // 10分钟超时（告警分析可能较慢）

        executor.execute(() -> {
            try {
                logger.info("收到 AI 智能运维请求 - 启动多 Agent 协作流程");

                DashScopeApi dashScopeApi = chatService.createDashScopeApi();
                DashScopeChatModel chatModel = chatService.createChatModel(dashScopeApi, 0.3, 8000, 0.9);

                ToolCallback[] toolCallbacks = tools.getToolCallbacks();

                emitter.send(SseEmitter.event().name("message").data(SseMessage.content("正在读取告警并拆解任务...\n")));
                
                // 调用 AiOpsService 执行分析流程
                Optional<OverAllState> overAllStateOptional = aiOpsService.executeAiOpsAnalysis(chatModel, toolCallbacks);

                if (overAllStateOptional.isEmpty()) {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error(ApiError.of(ErrorCode.BUSINESS_ERROR,
                                    "多 Agent 编排未获取到有效结果")), MediaType.APPLICATION_JSON));
                    emitter.complete();
                    return;
                }

                OverAllState state = overAllStateOptional.get();
                logger.info("AI Ops 编排完成，开始提取最终报告...");

                // 提取最终报告
                Optional<String> finalReportOptional = aiOpsService.extractFinalReport(state);

                // 输出最终报告
                if (finalReportOptional.isPresent()) {
                    String finalReportText = finalReportOptional.get();
                    logger.info("提取到 Planner 最终报告，长度: {}", finalReportText.length());
                    
                    // 发送分隔线
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("\n\n" + "=".repeat(60) + "\n"), MediaType.APPLICATION_JSON));
                    
                    // 发送完整的告警分析报告
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("📋 **告警分析报告**\n\n"), MediaType.APPLICATION_JSON));
                    
                    int chunkSize = 50;
                    for (int i = 0; i < finalReportText.length(); i += chunkSize) {
                        int end = Math.min(i + chunkSize, finalReportText.length());
                        String chunk = finalReportText.substring(i, end);
                        
                        emitter.send(SseEmitter.event().name("message")
                                .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
                    }
                    
                    // 发送结束分隔线
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("\n" + "=".repeat(60) + "\n\n"), MediaType.APPLICATION_JSON));
                    
                    logger.info("最终报告已完整输出");
                } else {
                    logger.warn("未能提取到 Planner 最终报告");
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("⚠️ 多 Agent 流程已完成，但未能生成最终报告。"), MediaType.APPLICATION_JSON));
                }

                emitter.send(SseEmitter.event().name("message").data(SseMessage.done(), MediaType.APPLICATION_JSON));
                emitter.complete();
                logger.info("AI Ops 多 Agent 编排完成");

            } catch (Exception e) {
                logger.error("AI Ops 多 Agent 协作失败", e);
                try {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error(toApiError(e)), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.complete();
            }
        });

        return emitter;
    }


    /**
     * 获取会话信息
     */
    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId) {
        logger.info("收到获取会话信息请求 - SessionId: {}", sessionId);

        Optional<ChatSessionService.SessionDetails> session = chatSessionService.findSession(sessionId);
        if (session.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "会话不存在");
        }
        SessionInfoResponse response = new SessionInfoResponse();
        response.setSessionId(sessionId);
        response.setMessagePairCount(session.get().messagePairCount());
        response.setCreateTime(session.get().createTime());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    private ApiError toApiError(Throwable error) {
        if (error instanceof AppException appException) {
            return ApiError.of(appException.getErrorCode(), appException.getMessage());
        }
        logger.error("SSE request failed", error);
        return ApiError.of(ErrorCode.MODEL_UNAVAILABLE);
    }

    private void sendSseError(SseEmitter emitter, Throwable error) throws IOException {
        emitter.send(SseEmitter.event().name("message")
                .data(SseMessage.error(toApiError(error)), MediaType.APPLICATION_JSON));
    }

    // ==================== 内部类 ====================

    /**
     * 聊天请求
     */
    @Setter
    @Getter
    public static class ChatRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;
        
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Question")
        @com.fasterxml.jackson.annotation.JsonAlias({"question", "QUESTION"})
        private String Question;

    }

    /**
     * 清空会话请求
     */
    @Setter
    @Getter
    public static class ClearRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;
    }

    // ==================== 内部类 ====================

    /**
     * 会话信息响应
     */
    @Setter
    @Getter
    public static class SessionInfoResponse {
        private String sessionId;
        private int messagePairCount;
        private long createTime;
    }

    /**
     * 统一聊天响应格式
     * 适用于所有普通返回模式的对话接口
     */
    @Setter
    @Getter
    public static class ChatResponse {
        private boolean success;
        private String answer;
        private String errorMessage;

        public static ChatResponse success(String answer) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(true);
            response.setAnswer(answer);
            return response;
        }

        public static ChatResponse error(String errorMessage) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(false);
            response.setErrorMessage(errorMessage);
            return response;
        }
    }

}
