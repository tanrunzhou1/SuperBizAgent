package org.example.common.aiops;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

/**
 * 真实运行模式的工具提供者。REPLAY 模式由 ReplayAiOpsToolProvider 独立处理。
 */
@Component
public class LiveAiOpsToolProvider implements AiOpsToolProvider {
    private final ToolCallbackProvider toolCallbackProvider;

    public LiveAiOpsToolProvider(ToolCallbackProvider toolCallbackProvider) {
        this.toolCallbackProvider = toolCallbackProvider;
    }

    @Override
    public ToolCallback[] getToolCallbacks(AiOpsRunContext context) {
        if (context != null && context.getMode() == AiOpsRunMode.REPLAY) {
            throw new UnsupportedOperationException("REPLAY 工具提供者尚未接入 Cloud-OpsBench 回放数据");
        }
        return toolCallbackProvider.getToolCallbacks();
    }
}
