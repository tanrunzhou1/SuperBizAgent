package org.example.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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

/**
 * 聊天会话持久化服务。
 */
@Service
public class ChatSessionService {
    private static final int TITLE_MAX_LENGTH = 255;
    private static final String USER_ROLE = "USER";
    private static final String ASSISTANT_ROLE = "ASSISTANT";

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final TransactionTemplate transactionTemplate;

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
        chatSessionMapper.insertIgnore(sessionId);
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
                .map(message -> Map.of(
                        "role", message.getRole().toLowerCase(),
                        "content", message.getContent()))
                .toList();
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
        transactionTemplate.executeWithoutResult(status -> {
            // 锁定会话行，保证同一会话生成的消息序号不会重复。
            ChatSessionEntity session = chatSessionMapper.selectBySessionIdForUpdate(sessionId);
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
        });
    }

    /**
     * 清空会话消息和会话摘要。
     *
     * @param sessionId 会话标识
     */
    public void clear(String sessionId) {
        transactionTemplate.executeWithoutResult(status -> {
            ChatSessionEntity session = chatSessionMapper.selectBySessionIdForUpdate(sessionId);
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
        });
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
        ChatMessageEntity message = new ChatMessageEntity();
        message.setMessageId(UUID.randomUUID().toString());
        message.setSessionId(sessionId);
        message.setSequenceNo(sequenceNo);
        message.setRole(role);
        message.setContent(content);
        message.setTraceId(traceId);
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

    /**
     * 会话详情。
     *
     * @param messagePairCount 消息对数量
     * @param createTime 会话创建时间戳
     */
    public record SessionDetails(int messagePairCount, long createTime) {
    }
}
