package org.example.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ChatSessionService {
    private final JdbcTemplate jdbcTemplate;

    public ChatSessionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String getOrCreateSession(String requestedSessionId) {
        String sessionId = requestedSessionId == null || requestedSessionId.isBlank()
                ? UUID.randomUUID().toString() : requestedSessionId;
        jdbcTemplate.update("INSERT IGNORE INTO chat_session (session_id) VALUES (?)", sessionId);
        return sessionId;
    }

    public List<Map<String, String>> loadHistory(String sessionId) {
        return jdbcTemplate.query("SELECT role, content FROM chat_message WHERE session_id = ? ORDER BY sequence_no ASC",
                (rs, rowNum) -> {
                    Map<String, String> message = new HashMap<>();
                    message.put("role", rs.getString("role").toLowerCase());
                    message.put("content", rs.getString("content"));
                    return message;
                }, sessionId);
    }

    @Transactional
    public void saveTurn(String sessionId, String question, String answer, String traceId) {
        // 锁定会话行，确保同一会话的 sequence_no 不会并发重复。
        jdbcTemplate.queryForObject("SELECT session_id FROM chat_session WHERE session_id = ? FOR UPDATE",
                String.class, sessionId);
        Integer lastSequence = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sequence_no), 0) FROM chat_message WHERE session_id = ?", Integer.class, sessionId);
        int userSequence = (lastSequence == null ? 0 : lastSequence) + 1;
        jdbcTemplate.update("INSERT INTO chat_message " +
                        "(message_id, session_id, sequence_no, role, content, trace_id) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), sessionId, userSequence, "USER", question, traceId);
        jdbcTemplate.update("INSERT INTO chat_message " +
                        "(message_id, session_id, sequence_no, role, content, trace_id) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), sessionId, userSequence + 1, "ASSISTANT", answer, traceId);
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("UPDATE chat_session SET title = COALESCE(title, ?), last_message_at = ? WHERE session_id = ?",
                truncate(question, 255), now, sessionId);
    }

    @Transactional
    public void clear(String sessionId) {
        if (!exists(sessionId)) {
            throw new IllegalArgumentException("会话不存在");
        }
        jdbcTemplate.update("DELETE FROM chat_message WHERE session_id = ?", sessionId);
        jdbcTemplate.update("UPDATE chat_session SET summary = NULL, summary_covered_sequence_no = 0, last_message_at = NULL " +
                "WHERE session_id = ?", sessionId);
    }

    public Optional<SessionDetails> findSession(String sessionId) {
        List<SessionDetails> result = jdbcTemplate.query("SELECT created_at, " +
                        "(SELECT COUNT(*) FROM chat_message WHERE session_id = ?) / 2 AS message_pair_count " +
                        "FROM chat_session WHERE session_id = ?",
                (rs, rowNum) -> new SessionDetails(rs.getInt("message_pair_count"),
                        rs.getTimestamp("created_at").getTime()), sessionId, sessionId);
        return result.stream().findFirst();
    }

    private boolean exists(String sessionId) {
        return findSession(sessionId).isPresent();
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public record SessionDetails(int messagePairCount, long createTime) {
    }
}
