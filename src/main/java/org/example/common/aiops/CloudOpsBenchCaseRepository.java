package org.example.common.aiops;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Cloud-OpsBench 案例只读仓库。
 *
 * caseId 只允许采用 dataset/category/number 形式，避免把调用方输入直接
 * 拼接成任意文件路径。
 */
@Component
public class CloudOpsBenchCaseRepository {
    private static final Pattern CASE_ID_PATTERN = Pattern.compile(
            "^[A-Za-z0-9_-]+/[A-Za-z0-9_-]+/[0-9]+$");

    private final ObjectMapper objectMapper;
    private final Path datasetRoot;

    public CloudOpsBenchCaseRepository(ObjectMapper objectMapper,
            @Value("${aiops.evaluation.dataset-root:../Cloud-OpsBench/benchmark}") String datasetRoot) {
        this.objectMapper = objectMapper;
        this.datasetRoot = Path.of(datasetRoot).toAbsolutePath().normalize();
    }

    public CloudOpsBenchCase load(String caseId) {
        validateCaseId(caseId);

        Path caseDirectory = datasetRoot.resolve(caseId).normalize();
        if (!caseDirectory.startsWith(datasetRoot)) {
            throw new IllegalArgumentException("非法 caseId 路径");
        }

        Path metadataPath = caseDirectory.resolve("metadata.json");
        Path toolCachePath = caseDirectory.resolve("tool_cache.json");
        if (!Files.isRegularFile(metadataPath) || !Files.isRegularFile(toolCachePath)) {
            throw new IllegalArgumentException("Cloud-OpsBench 案例不存在或缺少 metadata.json/tool_cache.json: " + caseId);
        }

        try {
            JsonNode metadata = objectMapper.readTree(Files.readString(metadataPath));
            Map<String, JsonNode> rawCache = objectMapper.readValue(Files.readString(toolCachePath),
                    new TypeReference<Map<String, JsonNode>>() {
                    });
            Map<String, String> toolCache = new LinkedHashMap<>();
            rawCache.forEach((key, value) -> toolCache.put(key, value == null || value.isNull()
                    ? "" : value.isTextual() ? value.textValue() : value.toString()));
            return new CloudOpsBenchCase(caseId, caseDirectory, metadata, toolCache);
        } catch (IOException exception) {
            throw new IllegalStateException("读取 Cloud-OpsBench 案例失败: " + caseId, exception);
        }
    }

    public void validateCaseId(String caseId) {
        if (caseId == null || !CASE_ID_PATTERN.matcher(caseId).matches()) {
            throw new IllegalArgumentException("caseId 格式非法，应为 dataset/category/number");
        }
    }

    public Path getDatasetRoot() {
        return datasetRoot;
    }
}
