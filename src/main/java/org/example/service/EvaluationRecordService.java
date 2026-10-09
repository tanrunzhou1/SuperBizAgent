package org.example.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.common.aiops.AiOpsRunContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 测评请求和结果持久化服务。
 *
 * @author Codex
 * @date 2026-09-28
 */
@Service
public class EvaluationRecordService {

    private static final String TYPE_REPORT = "REPORT";
    private static final String TYPE_SCORE = "SCORE";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_FAILED = "FAILED";
    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_ERROR_LENGTH = 2000;
    private static final Pattern JSON_SECRET_PATTERN = Pattern.compile(
            "(?i)(\\\"?(?:api[_-]?key|authorization|access[_-]?token|secret|password)\\\"?\\s*:\\s*\\\")[^\\\"]*(\\\")");
    private static final Pattern TEXT_SECRET_PATTERN = Pattern.compile(
            "(?i)(api[_-]?key|authorization|access[_-]?token|secret|password)\\s*[:=]\\s*[^\\s,;]+|Bearer\\s+[^\\s,;]+");

    private static final String SELECT_COLUMNS = "id, run_id, type, dataset, case_id, status, "
            + "error_message, agent_profile, profile_version, model_provider, model_name, "
            + "started_at, completed_at";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public EvaluationRecordService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建一条运行中的测评记录。
     *
     * @param context 测评运行上下文
     * @param type 产物类型
     * @param requestBody 原始请求 DTO
     * @param profileName 实际使用的 Agent 配置
     * @param profileVersion 实际使用的配置版本
     * @param modelProvider 模型供应商
     * @param modelName 模型名称
     * @return 数据库自增记录 ID
     */
    public long start(AiOpsRunContext context, String type, Object requestBody, String profileName,
            int profileVersion, String modelProvider, String modelName) {
        validateStartArguments(context, type, requestBody, profileName);
        String requestJson = toJson(requestBody);
        String dataset = "cloud-ops-bench";
        int affectedRows = jdbcTemplate.update(
                "INSERT INTO evaluation_record (run_id, type, request_body, dataset, case_id, status, "
                        + "agent_profile, profile_version, model_provider, model_name) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                context.getRunId(), type, requestJson, dataset, context.getCaseId(), STATUS_RUNNING,
                profileName, profileVersion, modelProvider, modelName);
        if (affectedRows != 1) {
            throw new IllegalStateException("创建测评记录失败");
        }
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM evaluation_record WHERE run_id = ?", Long.class, context.getRunId());
        if (id == null) {
            throw new IllegalStateException("测评记录已插入但无法读取自增 ID");
        }
        return id;
    }

    /**
     * 保存成功响应并完成测评记录。
     *
     * @param id 测评记录自增 ID
     * @param responseBody 规范化业务响应
     */
    public void complete(long id, Object responseBody) {
        String responseJson = toJson(responseBody);
        int affectedRows = jdbcTemplate.update(
                "UPDATE evaluation_record SET status = ?, response_body = ?, error_message = NULL, "
                        + "completed_at = CURRENT_TIMESTAMP WHERE id = ? AND status = ?",
                STATUS_SUCCEEDED, responseJson, id, STATUS_RUNNING);
        assertUpdated(affectedRows, id);
    }

    /**
     * 保存失败信息并完成测评记录。
     *
     * @param id 测评记录自增 ID
     * @param errorMessage 失败信息
     */
    public void fail(long id, String errorMessage) {
        String safeMessage = sanitizeError(errorMessage);
        int affectedRows = jdbcTemplate.update(
                "UPDATE evaluation_record SET status = ?, error_message = ?, "
                        + "completed_at = CURRENT_TIMESTAMP WHERE id = ? AND status = ?",
                STATUS_FAILED, safeMessage, id, STATUS_RUNNING);
        assertUpdated(affectedRows, id);
    }

    /**
     * 按筛选条件分页读取测评记录。
     *
     * @param type 产物类型，可为空
     * @param dataset 数据集，可为空
     * @param caseId 案例 ID，可为空
     * @param status 运行状态，可为空
     * @param requestedPage 页码，从零开始
     * @param requestedSize 每页条数
     * @return 测评记录分页
     */
    public EvaluationRecordPage list(String type, String dataset, String caseId, String status,
            Integer requestedPage, Integer requestedSize) {
        validateFilter(type, status);
        int page = requestedPage == null ? DEFAULT_PAGE : Math.max(DEFAULT_PAGE, requestedPage);
        int size = requestedSize == null ? DEFAULT_PAGE_SIZE : Math.max(1, Math.min(MAX_PAGE_SIZE, requestedSize));
        List<String> conditions = new ArrayList<>();
        List<Object> arguments = new ArrayList<>();
        addFilter(conditions, arguments, "type", normalizeCode(type));
        addFilter(conditions, arguments, "dataset", normalize(dataset));
        addFilter(conditions, arguments, "case_id", normalize(caseId));
        addFilter(conditions, arguments, "status", normalizeCode(status));
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM evaluation_record" + where, Long.class, arguments.toArray());
        List<Object> pageArguments = new ArrayList<>(arguments);
        pageArguments.add(size);
        pageArguments.add((long) page * size);
        List<EvaluationRecordSummary> records = jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM evaluation_record" + where
                        + " ORDER BY started_at DESC, id DESC LIMIT ? OFFSET ?",
                summaryRowMapper(), pageArguments.toArray());
        return new EvaluationRecordPage(page, size, count == null ? 0L : count, records);
    }

    /**
     * 查询单条测评记录及其请求、响应快照。
     *
     * @param id 测评记录自增 ID
     * @return 测评记录详情；不存在时返回 null
     */
    public EvaluationRecordDetail findById(long id) {
        List<EvaluationRecordDetail> records = jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + ", request_body, response_body "
                        + "FROM evaluation_record WHERE id = ?",
                (resultSet, rowNumber) -> new EvaluationRecordDetail(
                        readSummary(resultSet),
                        parseJson(resultSet.getString("request_body")),
                        parseJson(resultSet.getString("response_body"))), id);
        return records.isEmpty() ? null : records.get(0);
    }

    private void validateStartArguments(AiOpsRunContext context, String type, Object requestBody,
            String profileName) {
        if (context == null || context.getRunId() == null || context.getRunId().isBlank()
                || context.getCaseId() == null || context.getCaseId().isBlank()) {
            throw new IllegalArgumentException("测评运行上下文缺少 runId 或 caseId");
        }
        if (!TYPE_REPORT.equals(type) && !TYPE_SCORE.equals(type)) {
            throw new IllegalArgumentException("测评记录类型仅支持 REPORT 或 SCORE");
        }
        if (requestBody == null || profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("测评请求和 Agent 配置不能为空");
        }
    }

    private void validateFilter(String type, String status) {
        String normalizedType = normalizeCode(type);
        String normalizedStatus = normalizeCode(status);
        if (normalizedType != null && !TYPE_REPORT.equals(normalizedType) && !TYPE_SCORE.equals(normalizedType)) {
            throw new IllegalArgumentException("type 仅支持 REPORT 或 SCORE");
        }
        if (normalizedStatus != null && !STATUS_RUNNING.equals(normalizedStatus)
                && !STATUS_SUCCEEDED.equals(normalizedStatus) && !STATUS_FAILED.equals(normalizedStatus)) {
            throw new IllegalArgumentException("status 仅支持 RUNNING、SUCCEEDED 或 FAILED");
        }
    }

    private void addFilter(List<String> conditions, List<Object> arguments, String column, String value) {
        if (value != null) {
            conditions.add(column + " = ?");
            arguments.add(value);
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizeCode(String value) {
        String normalized = normalize(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private String toJson(Object value) {
        try {
            return redactSecrets(objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("测评请求或响应无法序列化为 JSON", exception);
        }
    }

    private JsonNode parseJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("测评记录中的 JSON 数据无效", exception);
        }
    }

    private String sanitizeError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "测评执行失败";
        }
        String sanitized = redactSecrets(errorMessage);
        return sanitized.length() <= MAX_ERROR_LENGTH ? sanitized : sanitized.substring(0, MAX_ERROR_LENGTH);
    }

    private String redactSecrets(String value) {
        String sanitized = JSON_SECRET_PATTERN.matcher(value).replaceAll("$1[REDACTED]$2");
        return TEXT_SECRET_PATTERN.matcher(sanitized).replaceAll("[REDACTED]");
    }

    private RowMapper<EvaluationRecordSummary> summaryRowMapper() {
        return (resultSet, rowNumber) -> readSummary(resultSet);
    }

    private EvaluationRecordSummary readSummary(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new EvaluationRecordSummary(
                resultSet.getLong("id"),
                resultSet.getString("run_id"),
                resultSet.getString("type"),
                resultSet.getString("dataset"),
                resultSet.getString("case_id"),
                resultSet.getString("status"),
                resultSet.getString("error_message"),
                resultSet.getString("agent_profile"),
                resultSet.getInt("profile_version"),
                resultSet.getString("model_provider"),
                resultSet.getString("model_name"),
                resultSet.getString("started_at"),
                resultSet.getString("completed_at"));
    }

    private void assertUpdated(int affectedRows, long id) {
        if (affectedRows != 1) {
            throw new IllegalStateException("测评记录未处于运行状态或记录不存在，id=" + id);
        }
    }

    public record EvaluationRecordSummary(long id, String runId, String type, String dataset, String caseId,
            String status, String errorMessage, String agentProfile, int profileVersion,
            String modelProvider, String modelName, String startedAt, String completedAt) {
    }

    public record EvaluationRecordDetail(EvaluationRecordSummary record, JsonNode requestBody,
            JsonNode responseBody) {
    }

    public record EvaluationRecordPage(int page, int size, long total,
            List<EvaluationRecordSummary> records) {
    }
}
