package org.example.controller;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.NodeOutput;
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
import org.example.common.aiops.AiOpsRunContext;
import org.example.common.aiops.AiOpsRunResult;
import org.example.common.exception.AppException;
import org.example.common.exception.BusinessException;
import org.example.common.exception.ErrorCode;
import org.example.service.ChatService;
import org.example.service.ChatSessionService;
import org.example.service.AiOpsRunService;
import org.example.dto.AIOpsRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private ChatService chatService;

    @Autowired
    private AiOpsRunService aiOpsRunService;

    @Autowired
    private ChatSessionService chatSessionService;

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
     * AI 智能运维接口（SSE 流式模式）。请求体可选，未提供时读取当前活跃告警。
     */
    @PostMapping(value = "/ai_ops", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter aiOps(@RequestBody(required = false) AIOpsRequest request) {
        AIOpsRequest safeRequest = request == null ? new AIOpsRequest() : request;
        AiOpsRunContext context = safeRequest.toRunContext();
        if (context.getMode() != org.example.common.aiops.AiOpsRunMode.LIVE) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "生产接口只允许 LIVE 运行模式");
        }
        return startAiOpsStream(context);
    }

    /**
     * 单案例测评接口。当前从 Cloud-OpsBench 冻结快照执行一个 caseId。
     */
    @PostMapping(value = "/ai-ops/evaluations", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter evaluateAiOps(@RequestBody AIOpsRequest request) {
        if (request == null || request.getCaseId() == null || request.getCaseId().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "测评接口必须提供 caseId");
        }
        request.setMode(org.example.common.aiops.AiOpsRunMode.REPLAY);
        return startAiOpsStream(request.toRunContext());
    }

    private SseEmitter startAiOpsStream(AiOpsRunContext context) {
        SseEmitter emitter = new SseEmitter(600000L);
        executor.execute(() -> {
            try {
                logger.info("收到 AI Ops 请求 - mode={}, caseId={}, runId={}",
                        context.getMode(), context.getCaseId(), context.getRunId());
                DashScopeApi dashScopeApi = chatService.createDashScopeApi();
                DashScopeChatModel chatModel = chatService.createChatModel(dashScopeApi, 0.3, 8000, 0.9);
                emitter.send(SseEmitter.event().name("message")
                        .data(SseMessage.content("正在读取告警并拆解任务...\n"), MediaType.APPLICATION_JSON));

                AiOpsRunResult runResult = aiOpsRunService.run(chatModel, context);
                String report = runResult.getMarkdownReport();
                for (int i = 0; i < report.length(); i += 50) {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content(report.substring(i, Math.min(i + 50, report.length()))),
                                    MediaType.APPLICATION_JSON));
                }
                emitter.send(SseEmitter.event().name("message")
                        .data(SseMessage.done(), MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (Exception exception) {
                logger.error("AI Ops 协作失败, mode={}, caseId={}", context.getMode(), context.getCaseId(), exception);
                try {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error(toApiError(exception)), MediaType.APPLICATION_JSON));
                } catch (IOException sendException) {
                    logger.error("发送 AI Ops 错误消息失败", sendException);
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
        if (error instanceof UnsupportedOperationException) {
            return ApiError.of(ErrorCode.BUSINESS_ERROR, error.getMessage());
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
