package org.example.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.example.entity.ChatMessageEntity;
import org.example.entity.ChatSessionEntity;
import org.example.mapper.ChatMessageMapper;
import org.example.mapper.ChatSessionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 聊天会话持久化服务。
 */
@Service
public class ChatSessionService {
    private static final int SESSION_LOCK_COUNT = 64;
    private static final int TITLE_MAX_LENGTH = 255;
    private static final String USER_ROLE = "USER";
    private static final String ASSISTANT_ROLE = "ASSISTANT";

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final TransactionTemplate transactionTemplate;
    private final ReentrantLock[] sessionLocks = createSessionLocks();

    public ChatSessionService(ChatSessionMapper chatSessionMapper, ChatMessageMapper chatMessageMapper,
            TransactionTemplate transactionTemplate) {
        this.chatSessionMapper = chatSessionMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 获取已有会话或创建新会话。
     *
     * @param requestedSessionId 请求携带的会话标识
     * @return 可用的会话标识
     */
    public String getOrCreateSession(String requestedSessionId) {
        String sessionId = requestedSessionId == null || requestedSessionId.isBlank()
                ? UUID.randomUUID().toString() : requestedSessionId;
        ReentrantLock lock = lockFor(sessionId);
        lock.lock();
        try {
            chatSessionMapper.insertIgnore(sessionId);
        }
        finally {
            lock.unlock();
        }
        return sessionId;
    }

    /**
     * 读取会话的全部历史消息。
     *
     * @param sessionId 会话标识
     * @return 按顺序排列的角色与内容
     */
    public List<Map<String, String>> loadHistory(String sessionId) {
        return chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessageEntity>()
                        .eq(ChatMessageEntity::getSessionId, sessionId)
                        .orderByAsc(ChatMessageEntity::getSequenceNo))
                .stream()
                .filter(message -> {
                    String type = message.getMessageType();
                    if (type == null || "CHAT".equals(type) || "AIOPS_REQUEST".equals(type)) return true;
                    return "AIOPS_RESULT".equals(type)
                            && !"RUNNING".equals(message.getStatus())
                            && message.getContent() != null && !message.getContent().isBlank();
                })
                .map(message -> Map.of(
                        "role", message.getRole().toLowerCase(),
                        "content", message.getContent() == null ? "" : message.getContent()))
                .toList();
    }

    public List<ChatMessageEntity> loadMessages(String sessionId) {
        return chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessageEntity>()
                .eq(ChatMessageEntity::getSessionId, sessionId)
                .orderByAsc(ChatMessageEntity::getSequenceNo));
    }

    /** Save the AIOps user request and assistant placeholder as one ordered pair. */
    public AiOpsMessages beginAiOps(String sessionId, String request, String runId, String traceId) {
        final AiOpsMessages[] result = new AiOpsMessages[1];
        withSessionLock(sessionId, () -> transactionTemplate.executeWithoutResult(status -> {
            if (chatSessionMapper.selectBySessionId(sessionId) == null) {
                throw new IllegalArgumentException("会话不存在");
            }
            Integer last = chatMessageMapper.selectMaxSequenceNo(sessionId);
            int first = (last == null ? 0 : last) + 1;
            String requestId = UUID.randomUUID().toString();
            String resultId = UUID.randomUUID().toString();
            insertMessage(sessionId, first, USER_ROLE, request, traceId, "AIOPS_REQUEST", "SUCCEEDED", runId, requestId);
            insertMessage(sessionId, first + 1, ASSISTANT_ROLE, "正在排查当前活跃告警…", traceId,
                    "AIOPS_RESULT", "RUNNING", runId, resultId);
            int affected = chatSessionMapper.update(null, new LambdaUpdateWrapper<ChatSessionEntity>()
                    .eq(ChatSessionEntity::getSessionId, sessionId)
                    .setSql("title = COALESCE(title, {0})", truncate(request, TITLE_MAX_LENGTH))
                    .set(ChatSessionEntity::getLastMessageAt, LocalDateTime.now()));
            assertAffectedRows(affected, "更新会话失败");
            result[0] = new AiOpsMessages(requestId, resultId, first + 1);
        }));
        return result[0];
    }

    public void finishAiOps(String sessionId, String runId, String report, boolean success) {
        withSessionLock(sessionId, () -> transactionTemplate.executeWithoutResult(status -> {
            int affected = chatMessageMapper.update(null, new LambdaUpdateWrapper<ChatMessageEntity>()
                    .eq(ChatMessageEntity::getSessionId, sessionId)
                    .eq(ChatMessageEntity::getRunId, runId)
                    .eq(ChatMessageEntity::getMessageType, "AIOPS_RESULT")
                    .set(ChatMessageEntity::getContent, report)
                    .set(ChatMessageEntity::getStatus, success ? "SUCCEEDED" : "FAILED"));
            if (affected != 1) throw new IllegalStateException("AIOps 会话结果记录不存在");
            int updated = chatSessionMapper.update(null, new LambdaUpdateWrapper<ChatSessionEntity>()
                    .eq(ChatSessionEntity::getSessionId, sessionId)
                    .set(ChatSessionEntity::getLastMessageAt, LocalDateTime.now()));
            assertAffectedRows(updated, "更新会话时间失败");
        }));
    }

    /**
     * 原子保存一轮用户消息和模型回复。
     *
     * @param sessionId 会话标识
     * @param question 用户问题
     * @param answer 模型回答
     * @param traceId 请求链路标识
     */
    public void saveTurn(String sessionId, String question, String answer, String traceId) {
        withSessionLock(sessionId, () -> transactionTemplate.executeWithoutResult(status -> {
                ChatSessionEntity session = chatSessionMapper.selectBySessionId(sessionId);
                if (session == null) {
                    throw new IllegalArgumentException("会话不存在");
                }

                Integer lastSequenceNo = chatMessageMapper.selectMaxSequenceNo(sessionId);
                int userSequenceNo = lastSequenceNo == null ? 1 : lastSequenceNo + 1;
                insertMessage(sessionId, userSequenceNo, USER_ROLE, question, traceId);
                insertMessage(sessionId, userSequenceNo + 1, ASSISTANT_ROLE, answer, traceId);

                // 首次消息作为标题，并更新会话最后活跃时间。
                int affectedRows = chatSessionMapper.update(null, new LambdaUpdateWrapper<ChatSessionEntity>()
                        .eq(ChatSessionEntity::getSessionId, sessionId)
                        .setSql("title = COALESCE(title, {0})", truncate(question, TITLE_MAX_LENGTH))
                        .set(ChatSessionEntity::getLastMessageAt, LocalDateTime.now()));
                assertAffectedRows(affectedRows, "更新会话失败");
            }));
    }

    /**
     * 清空会话消息和会话摘要。
     *
     * @param sessionId 会话标识
     */
    public void clear(String sessionId) {
        withSessionLock(sessionId, () -> transactionTemplate.executeWithoutResult(status -> {
                ChatSessionEntity session = chatSessionMapper.selectBySessionId(sessionId);
                if (session == null) {
                    throw new IllegalArgumentException("会话不存在");
                }

                chatMessageMapper.delete(new LambdaQueryWrapper<ChatMessageEntity>()
                        .eq(ChatMessageEntity::getSessionId, sessionId));
                int affectedRows = chatSessionMapper.update(null, new LambdaUpdateWrapper<ChatSessionEntity>()
                        .eq(ChatSessionEntity::getSessionId, sessionId)
                        .set(ChatSessionEntity::getSummary, null)
                        .set(ChatSessionEntity::getSummaryCoveredSequenceNo, 0)
                        .set(ChatSessionEntity::getLastMessageAt, null));
                assertAffectedRows(affectedRows, "清空会话失败");
            }));
    }

    /**
     * 查询会话基础信息。
     *
     * @param sessionId 会话标识
     * @return 会话详情
     */
    public Optional<SessionDetails> findSession(String sessionId) {
        ChatSessionEntity session = chatSessionMapper.selectOne(new LambdaQueryWrapper<ChatSessionEntity>()
                .eq(ChatSessionEntity::getSessionId, sessionId));
        if (session == null) {
            return Optional.empty();
        }

        Long messageCount = chatMessageMapper.selectCount(new LambdaQueryWrapper<ChatMessageEntity>()
                .eq(ChatMessageEntity::getSessionId, sessionId));
        int messagePairCount = Math.toIntExact((messageCount == null ? 0L : messageCount) / 2);
        long createTime = session.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return Optional.of(new SessionDetails(messagePairCount, createTime));
    }

    private void insertMessage(String sessionId, int sequenceNo, String role, String content, String traceId) {
        insertMessage(sessionId, sequenceNo, role, content, traceId, "CHAT", null, null, UUID.randomUUID().toString());
    }

    private void insertMessage(String sessionId, int sequenceNo, String role, String content, String traceId,
            String messageType, String status, String runId, String messageId) {
        ChatMessageEntity message = new ChatMessageEntity();
        message.setMessageId(messageId);
        message.setSessionId(sessionId);
        message.setSequenceNo(sequenceNo);
        message.setRole(role);
        message.setContent(content);
        message.setTraceId(traceId);
        message.setMessageType(messageType);
        message.setStatus(status);
        message.setRunId(runId);
        assertAffectedRows(chatMessageMapper.insert(message), "保存聊天消息失败");
    }

    private void assertAffectedRows(int affectedRows, String errorMessage) {
        if (affectedRows != 1) {
            throw new IllegalStateException(errorMessage);
        }
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private void withSessionLock(String sessionId, Runnable action) {
        ReentrantLock lock = lockFor(sessionId);
        lock.lock();
        try {
            action.run();
        }
        finally {
            lock.unlock();
        }
    }

    private ReentrantLock lockFor(String sessionId) {
        int index = Math.floorMod(sessionId.hashCode(), sessionLocks.length);
        return sessionLocks[index];
    }

    private static ReentrantLock[] createSessionLocks() {
        ReentrantLock[] locks = new ReentrantLock[SESSION_LOCK_COUNT];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
        return locks;
    }

    /**
     * 会话详情。
     *
     * @param messagePairCount 消息对数量
     * @param createTime 会话创建时间戳
     */
    public record SessionDetails(int messagePairCount, long createTime) {
    }

    public record AiOpsMessages(String requestMessageId, String resultMessageId, int resultSequenceNo) {}
}
