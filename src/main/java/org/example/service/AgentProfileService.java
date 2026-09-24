package org.example.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Loads and versions the independently configurable agent profiles. */
@Service
public class AgentProfileService {
    public static final String CHAT = "CHAT";
    public static final String AIOPS_LIVE = "AIOPS_LIVE";
    public static final String EVALUATION = "EVALUATION";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AiOpsService aiOpsService;

    public AgentProfileService(JdbcTemplate jdbc, ObjectMapper mapper, AiOpsService aiOpsService) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.aiOpsService = aiOpsService;
    }

    public ProfileView get(String profile) {
        String key = normalizeProfile(profile);
        ProfileRow row = jdbc.queryForObject(
                "SELECT profile, active_version FROM agent_profile WHERE profile = ?",
                (rs, n) -> {
                    int version = rs.getInt("active_version");
                    return new ProfileRow(rs.getString("profile"), rs.wasNull() ? null : version);
                }, key);
        ProfileSnapshot active = row.activeVersion() == null
                ? defaults(key)
                : findVersion(key, row.activeVersion()).orElseGet(() -> defaults(key));
        Integer savedVersion = latestUnappliedVersion(key, row.activeVersion());
        ProfileSnapshot saved = savedVersion == null ? null : findVersion(key, savedVersion).orElse(null);
        return new ProfileView(key, active, saved, versions(key));
    }

    public List<ProfileView> getAll() {
        return List.of(get(CHAT), get(AIOPS_LIVE), get(EVALUATION));
    }

    public ProfileSnapshot getActive(String profile) {
        return get(profile).active();
    }

    public ProfileSnapshot getVersionOrActive(String profile, Integer version) {
        String key = normalizeProfile(profile);
        if (version == null) return getActive(key);
        return findVersion(key, version).orElseThrow(() -> new IllegalArgumentException(
                "Agent profile version not found: " + key + "/" + version));
    }

    @Transactional
    public ProfileSnapshot save(String profile, Map<String, String> prompts, SamplingConfig sampling) {
        String key = normalizeProfile(profile);
        validate(key, prompts, sampling);
        Integer currentMax = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM agent_profile_version WHERE profile = ?",
                Integer.class, key);
        int version = (currentMax == null ? 0 : currentMax) + 1;
        try {
            jdbc.update("INSERT INTO agent_profile_version " +
                            "(id, profile, version_no, prompt_config_json, sampling_config_json) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID().toString(), key, version,
                    mapper.writeValueAsString(prompts), mapper.writeValueAsString(sampling));
            jdbc.update("UPDATE agent_profile SET updated_at = CURRENT_TIMESTAMP WHERE profile = ?", key);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to save agent profile", e);
        }
        return findVersion(key, version).orElseThrow();
    }

    @Transactional
    public ProfileSnapshot apply(String profile, Integer requestedVersion) {
        String key = normalizeProfile(profile);
        ProfileView view = get(key);
        if (view.saved() == null) {
            throw new IllegalStateException("该 Agent 没有待应用的配置");
        }
        int version = requestedVersion == null ? view.saved().version() : requestedVersion;
        if (version != view.saved().version()) {
            throw new IllegalArgumentException("只能应用最新保存的未应用版本");
        }
        int changed = jdbc.update("UPDATE agent_profile SET active_version = ?, updated_at = CURRENT_TIMESTAMP " +
                "WHERE profile = ?", version, key);
        if (changed != 1) throw new IllegalStateException("Agent profile not found: " + key);
        jdbc.update("UPDATE agent_profile_version SET applied_at = CURRENT_TIMESTAMP " +
                "WHERE profile = ? AND version_no = ?", key, version);
        return findVersion(key, version).orElseThrow();
    }

    private List<VersionSummary> versions(String profile) {
        Integer activeVersion = jdbc.queryForObject(
                "SELECT active_version FROM agent_profile WHERE profile = ?", Integer.class, profile);
        return jdbc.query("SELECT version_no, created_at, applied_at FROM agent_profile_version " +
                        "WHERE profile = ? ORDER BY version_no DESC",
                (rs, n) -> new VersionSummary(rs.getInt("version_no"), rs.getString("created_at"),
                        rs.getString("applied_at"), activeVersion != null && activeVersion == rs.getInt("version_no")),
                profile);
    }

    private Integer latestUnappliedVersion(String profile, Integer activeVersion) {
        List<Integer> versions = jdbc.query("SELECT version_no FROM agent_profile_version WHERE profile = ? " +
                        "AND applied_at IS NULL ORDER BY version_no DESC LIMIT 1",
                (rs, n) -> rs.getInt(1), profile);
        if (versions.isEmpty()) return null;
        int latest = versions.get(0);
        return activeVersion == null || latest > activeVersion ? latest : null;
    }

    private java.util.Optional<ProfileSnapshot> findVersion(String profile, int version) {
        List<ProfileSnapshot> rows = jdbc.query("SELECT profile, version_no, prompt_config_json, " +
                        "sampling_config_json, created_at, applied_at FROM agent_profile_version " +
                        "WHERE profile = ? AND version_no = ?",
                (rs, n) -> new ProfileSnapshot(rs.getString("profile"), rs.getInt("version_no"),
                        readPrompts(rs.getString("profile"), rs.getString("prompt_config_json")),
                        readSampling(rs.getString("sampling_config_json")),
                        rs.getString("created_at"), rs.getString("applied_at")), profile, version);
        return rows.stream().findFirst();
    }

    private Map<String, String> readPrompts(String profile, String json) {
        try {
            Map<String, String> prompts = mapper.readValue(json, new TypeReference<>() {});
            // V2 profiles may contain the former planner/executor/supervisor prompt set.
            // Those instructions cannot be combined safely into one ReAct prompt, so use
            // the new single-agent default until the user saves an explicit system prompt.
            if (!CHAT.equals(profile) && (prompts == null || prompts.get("system") == null
                    || prompts.get("system").isBlank())) {
                return defaultPrompts(profile);
            }
            return prompts;
        } catch (Exception e) {
            throw new IllegalStateException("Invalid agent prompt configuration", e);
        }
    }

    private SamplingConfig readSampling(String json) {
        try {
            return mapper.readValue(json, SamplingConfig.class);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid agent sampling configuration", e);
        }
    }

    private ProfileSnapshot defaults(String profile) {
        Map<String, String> prompts = new LinkedHashMap<>();
        SamplingConfig sampling;
        switch (profile) {
            case CHAT -> {
                prompts.put("system", "你是一个专业的智能助手，可以获取当前时间、查询天气信息、搜索内部文档知识库，以及查询 Prometheus 告警信息。\n" +
                        "当用户询问时间相关问题时，使用 getCurrentDateTime 工具。\n" +
                        "当用户需要查询公司内部文档、流程、最佳实践或技术指南时，使用 queryInternalDocs 工具。\n" +
                        "当用户需要查询 Prometheus 告警、监控指标或系统告警状态时，使用 queryPrometheusAlerts 工具。\n" +
                        "当用户需要查询腾讯云日志时，请调用腾讯云mcp服务查询,默认查询地域ap-guangzhou,查询时间范围为近一个月。\n" +
                        "请基于对话历史回答用户的新问题。");
                sampling = new SamplingConfig(0.7, 2000, 0.9);
            }
            case AIOPS_LIVE -> {
                prompts.putAll(aiOpsService.defaultPromptConfig());
                sampling = new SamplingConfig(0.3, 8000, 0.9);
            }
            case EVALUATION -> {
                prompts.putAll(aiOpsService.defaultEvaluationPromptConfig());
                sampling = new SamplingConfig(0.3, 8000, 0.9);
            }
            default -> throw new IllegalArgumentException("Unknown agent profile: " + profile);
        }
        return new ProfileSnapshot(profile, 0, prompts, sampling, null, null);
    }

    private Map<String, String> defaultPrompts(String profile) {
        return EVALUATION.equals(profile)
                ? aiOpsService.defaultEvaluationPromptConfig()
                : aiOpsService.defaultPromptConfig();
    }

    private void validate(String profile, Map<String, String> prompts, SamplingConfig sampling) {
        if (prompts == null || sampling == null) throw new IllegalArgumentException("提示词和采样参数不能为空");
        List<String> required = List.of("system");
        for (String field : required) {
            String value = prompts.get(field);
            if (value == null || value.isBlank() || value.length() > 30000) {
                throw new IllegalArgumentException("提示词字段无效: " + field);
            }
        }
        if (sampling.temperature() < 0 || sampling.temperature() > 2
                || sampling.topP() <= 0 || sampling.topP() > 1
                || sampling.maxTokens() < 1 || sampling.maxTokens() > 32768) {
            throw new IllegalArgumentException("采样参数超出允许范围");
        }
    }

    private String normalizeProfile(String profile) {
        String key = profile == null ? "" : profile.trim().toUpperCase();
        if (!List.of(CHAT, AIOPS_LIVE, EVALUATION).contains(key)) {
            throw new IllegalArgumentException("Unknown agent profile: " + profile);
        }
        return key;
    }

    public record SamplingConfig(double temperature, int maxTokens, double topP) {}
    public record ProfileSnapshot(String profile, int version, Map<String, String> prompts,
                                  SamplingConfig sampling, String createdAt, String appliedAt) {}
    public record ProfileView(String profile, ProfileSnapshot active, ProfileSnapshot saved,
                              List<VersionSummary> versions) {}
    public record VersionSummary(int version, String createdAt, String appliedAt, boolean active) {}
    private record ProfileRow(String profile, Integer activeVersion) {}
}
