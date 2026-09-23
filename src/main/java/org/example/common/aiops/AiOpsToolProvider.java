package org.example.common.aiops;

import org.springframework.ai.tool.ToolCallback;

/**
 * 为一次 AIOps 运行提供工具。生产和测评只替换该数据来源，不替换 Agent 内核。
 */
public interface AiOpsToolProvider {
    ToolCallback[] getToolCallbacks(AiOpsRunContext context);
}
