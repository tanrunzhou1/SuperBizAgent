package org.example.common.tool;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在应用启动完成后发现本地 {@link Tool} 方法及已连接 MCP 服务提供的工具，并绑定启用状态和重试策略。
 */
@Component
public class ToolRegistry {
    private static final Logger logger = LoggerFactory.getLogger(ToolRegistry.class);

    private final ApplicationContext applicationContext;
    private final ToolCallbackProvider toolCallbackProvider;
    private final ToolRegistryProperties registryProperties;
    private final ToolRetryProperties retryProperties;
    private final Map<String, ToolDescriptor> tools = new ConcurrentHashMap<>();

    public ToolRegistry(ApplicationContext applicationContext, ToolCallbackProvider toolCallbackProvider,
            ToolRegistryProperties registryProperties, ToolRetryProperties retryProperties) {
        this.applicationContext = applicationContext;
        this.toolCallbackProvider = toolCallbackProvider;
        this.registryProperties = registryProperties;
        this.retryProperties = retryProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void discoverTools() {
        Map<String, ToolDescriptor> discovered = new LinkedHashMap<>();
        discoverLocalTools(discovered);
        discoverMcpTools(discovered);
        tools.clear();
        tools.putAll(discovered);
        validateConfiguredTools();
        tools.values().forEach(tool -> logger.info(
                "tool registered, name={}, source={}, enabled={}, maxAttempts={}", tool.getName(), tool.getSource(),
                tool.isEnabled(), tool.getRetryPolicy().getMaxAttempts()));
    }

    public Optional<ToolDescriptor> find(String toolName) {
        return Optional.ofNullable(tools.get(toolName));
    }

    public Collection<ToolDescriptor> all() {
        return tools.values();
    }

    private void discoverLocalTools(Map<String, ToolDescriptor> discovered) {
        applicationContext.getBeansWithAnnotation(Component.class).forEach((beanName, bean) -> {
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            ReflectionUtils.doWithMethods(targetClass, method -> registerLocalTool(discovered, method));
        });
    }

    private void registerLocalTool(Map<String, ToolDescriptor> discovered, Method method) {
        Tool annotation = AnnotatedElementUtils.findMergedAnnotation(method, Tool.class);
        if (annotation == null) {
            return;
        }
        String toolName = annotation.name().isBlank() ? method.getName() : annotation.name();
        register(discovered, toolName, ToolSource.LOCAL);
    }

    private void discoverMcpTools(Map<String, ToolDescriptor> discovered) {
        try {
            for (ToolCallback callback : toolCallbackProvider.getToolCallbacks()) {
                register(discovered, callback.getToolDefinition().name(), ToolSource.MCP);
            }
        } catch (Exception exception) {
            logger.warn("MCP tool discovery failed; local tools remain available", exception);
        }
    }

    private void register(Map<String, ToolDescriptor> discovered, String toolName, ToolSource source) {
        ToolDescriptor descriptor = new ToolDescriptor(toolName, source, registryProperties.isEnabled(toolName),
                retryProperties.forTool(toolName));
        ToolDescriptor existing = discovered.putIfAbsent(toolName, descriptor);
        if (existing != null) {
            logger.error("duplicate tool name detected, name={}, firstSource={}, duplicateSource={}", toolName,
                    existing.getSource(), source);
        }
    }

    private void validateConfiguredTools() {
        registryProperties.getEnabled().stream()
                .filter(toolName -> !tools.containsKey(toolName))
                .forEach(toolName -> logger.warn("enabled tool is not available at startup, name={}", toolName));
        retryProperties.getPolicies().keySet().stream()
                .filter(toolName -> !tools.containsKey(toolName))
                .forEach(toolName -> logger.warn("retry policy configured for unknown tool, name={}", toolName));
    }

    public enum ToolSource {
        LOCAL,
        MCP
    }

    @Getter
    public static class ToolDescriptor {
        private final String name;
        private final ToolSource source;
        private final boolean enabled;
        private final ToolRetryProperties.RetryPolicy retryPolicy;

        private ToolDescriptor(String name, ToolSource source, boolean enabled,
                ToolRetryProperties.RetryPolicy retryPolicy) {
            this.name = name;
            this.source = source;
            this.enabled = enabled;
            this.retryPolicy = retryPolicy;
        }
    }
}
