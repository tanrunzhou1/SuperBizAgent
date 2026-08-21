package org.example.common.tool;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "tool.registry")
public class ToolRegistryProperties {
    /**
     * 允许注册到运行时 Registry 的工具名。空集合表示不启用任何工具，避免新接入的 MCP 工具被默认放行。
     */
    private Set<String> enabled = new LinkedHashSet<>();

    public boolean isEnabled(String toolName) {
        return enabled.contains(toolName);
    }
}
