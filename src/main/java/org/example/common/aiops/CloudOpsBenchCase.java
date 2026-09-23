package org.example.common.aiops;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

/**
 * 一个只读的 Cloud-OpsBench 案例快照。
 */
@Getter
public class CloudOpsBenchCase {
    private final String caseId;
    private final Path directory;
    private final JsonNode metadata;
    private final Map<String, String> toolCache;
    private final Map<String, JsonNode> rawLogs;

    public CloudOpsBenchCase(String caseId, Path directory, JsonNode metadata,
            Map<String, String> toolCache) {
        this(caseId, directory, metadata, toolCache, Collections.emptyMap());
    }

    public CloudOpsBenchCase(String caseId, Path directory, JsonNode metadata,
            Map<String, String> toolCache, Map<String, JsonNode> rawLogs) {
        this.caseId = caseId;
        this.directory = directory;
        this.metadata = metadata;
        this.toolCache = Collections.unmodifiableMap(toolCache);
        this.rawLogs = Collections.unmodifiableMap(rawLogs);
    }
}
