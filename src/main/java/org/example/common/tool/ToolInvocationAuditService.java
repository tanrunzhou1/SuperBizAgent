package org.example.common.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.common.api.ApiError;
import org.example.common.api.ChatSessionContext;
import org.example.common.api.TraceIdContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class ToolInvocationAuditService {
    private static final int SUMMARY_MAX_LENGTH = 8_000;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ToolInvocationAuditService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public String start(String toolName, ToolRegistry.ToolDescriptor descriptor, String requestSummary) {
        String invocationId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO tool_invocation " +
                        "(invocation_id, trace_id, session_id, tool_name, tool_source, status, started_at, request_summary) " +
                        "VALUES (?, ?, ?, ?, ?, 'RUNNING', ?, ?)",
                invocationId, TraceIdContext.getOrCreate(), ChatSessionContext.get(), toolName, descriptor.getSource().name(),
                LocalDateTime.now(), truncate(requestSummary));
        return invocationId;
    }

    public long startAttempt(String invocationId, int attempt, String requestSummary) {
        jdbcTemplate.update("INSERT INTO tool_invocation_attempt " +
                        "(invocation_id, attempt_no, status, started_at, request_summary) VALUES (?, ?, 'RUNNING', ?, ?)",
                invocationId, attempt, LocalDateTime.now(), truncate(requestSummary));
        return attempt;
    }

    public void succeedAttempt(String invocationId, long attempt, Object result) {
        String response = toSummary(result);
        jdbcTemplate.update("UPDATE tool_invocation_attempt SET status = 'SUCCESS', finished_at = ?, response_summary = ? " +
                "WHERE invocation_id = ? AND attempt_no = ?", LocalDateTime.now(), response, invocationId, attempt);
    }

    public void failAttempt(String invocationId, long attempt, ApiError error) {
        jdbcTemplate.update("UPDATE tool_invocation_attempt SET status = 'FAILED', finished_at = ?, error_code = ?, " +
                        "error_message = ?, retryable = ? WHERE invocation_id = ? AND attempt_no = ?",
                LocalDateTime.now(), error.getCode(), truncate(error.getMessage()), error.isRetryable(), invocationId, attempt);
    }

    public void complete(String invocationId, ToolResult<?> result) {
        ApiError error = result.getError();
        jdbcTemplate.update("UPDATE tool_invocation SET status = ?, finished_at = ?, attempt_count = ?, error_code = ?, " +
                        "error_message = ?, result_summary = ? WHERE invocation_id = ?",
                result.isSuccess() ? "SUCCESS" : "FAILED", LocalDateTime.now(), result.getAttempts(),
                error == null ? null : error.getCode(), error == null ? null : truncate(error.getMessage()),
                result.isSuccess() ? toSummary(result.getData()) : null, invocationId);
    }

    private String toSummary(Object value) {
        try {
            return truncate(objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException exception) {
            return truncate(String.valueOf(value));
        }
    }

    private String truncate(String value) {
        if (value == null || value.length() <= SUMMARY_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, SUMMARY_MAX_LENGTH) + "...";
    }
}
