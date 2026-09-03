package com.intentreactor.core.tool;

import com.intentreactor.api.Tool;
import com.intentreactor.core.config.IntentReactorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ListableBeanFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Discovers Spring AI tools ({@code ToolCallback} beans and {@code ToolCallbackProvider}
 * beans) in the application context and adapts them to IntentReactor {@link Tool}s.
 *
 * <p>The scan mirrors Spring AI's own {@code ToolCallingAutoConfiguration} behaviour.
 * Providers whose type name is in {@link #DEFAULT_EXCLUDED_PROVIDER_TYPES} are skipped
 * based on {@code beanFactory.getType(beanName)} and are never instantiated — this keeps
 * MCP providers from doing network round-trips at startup and breaks the dependency cycle
 * with the mcp-server module's {@code IntentReactorToolCallbackProvider}, whose
 * constructor requires the very {@code ToolProvider} this collector feeds. As
 * {@code getType} may only reveal the declared bean type (e.g. an interface-typed
 * {@code @Bean} method), the runtime class of an instantiated provider is re-checked
 * before its tools are listed.
 */
public class SpringAiToolsCollector {

    private static final Logger log = LoggerFactory.getLogger(SpringAiToolsCollector.class);

    public static final List<String> DEFAULT_EXCLUDED_PROVIDER_TYPES = List.of(
            "org.springframework.ai.mcp.SyncMcpToolCallbackProvider",
            "org.springframework.ai.mcp.AsyncMcpToolCallbackProvider",
            "com.intentreactor.mcp.server.adapter.IntentReactorToolCallbackProvider",
            "com.intentreactor.mcp.server.planner.PlannerMcpCallbackProvider");

    private final Set<String> excludedProviderTypeNames;

    public SpringAiToolsCollector() {
        this(new HashSet<>(DEFAULT_EXCLUDED_PROVIDER_TYPES));
    }

    public SpringAiToolsCollector(Set<String> excludedProviderTypeNames) {
        this.excludedProviderTypeNames = new HashSet<>(excludedProviderTypeNames);
    }

    /**
     * Collects all Spring AI tools currently registered in the bean factory.
     */
    public List<Tool> collectSpringAiTools(ListableBeanFactory beanFactory,
                                           ObjectMapper objectMapper,
                                           IntentReactorProperties.SpringAiToolsConfig config) {
        List<Tool> tools = new ArrayList<>();
        Set<String> seenNames = new LinkedHashSet<>();

        for (String beanName : beanFactory.getBeanNamesForType(ToolCallbackProvider.class)) {
            if (isExcludedProvider(beanFactory, beanName)) {
                log.debug("Skipping excluded ToolCallbackProvider bean '{}'", beanName);
                continue;
            }
            ToolCallbackProvider provider;
            try {
                provider = beanFactory.getBean(beanName, ToolCallbackProvider.class);
            } catch (BeansException e) {
                log.warn("Could not obtain ToolCallbackProvider bean '{}': {}", beanName, e.getMessage());
                continue;
            }
            if (excludedProviderTypeNames.contains(provider.getClass().getName())) {
                log.debug("Skipping ToolCallbackProvider bean '{}' with excluded runtime type '{}'",
                        beanName, provider.getClass().getName());
                continue;
            }
            ToolCallback[] callbacks;
            try {
                callbacks = provider.getToolCallbacks();
            } catch (Exception e) {
                log.warn("ToolCallbackProvider '{}' failed to list tools: {}", beanName, e.getMessage());
                continue;
            }
            addCallbacks(tools, seenNames, callbacks, objectMapper, config);
        }

        for (String beanName : beanFactory.getBeanNamesForType(ToolCallback.class)) {
            try {
                addCallbacks(tools, seenNames,
                        new ToolCallback[]{beanFactory.getBean(beanName, ToolCallback.class)},
                        objectMapper, config);
            } catch (BeansException e) {
                log.warn("Could not obtain ToolCallback bean '{}': {}", beanName, e.getMessage());
            }
        }

        return tools;
    }

    /**
     * Merges native IR tools with bridged Spring AI tools. IR tools keep their order and
     * win name conflicts; conflicting Spring AI tools are skipped with a warning.
     */
    public List<Tool> mergeWithIrTools(List<Tool> irTools, List<Tool> saTools) {
        List<Tool> merged = new ArrayList<>(irTools.size() + saTools.size());
        Set<String> takenNames = new HashSet<>();
        for (Tool tool : irTools) {
            if (takenNames.add(tool.getName())) {
                merged.add(tool);
            } else {
                log.warn("Skipping duplicate IntentReactor tool '{}'", tool.getName());
            }
        }
        for (Tool tool : saTools) {
            if (takenNames.add(tool.getName())) {
                merged.add(tool);
            } else {
                log.warn("Skipping Spring AI tool '{}': name conflicts with an IntentReactor tool",
                        tool.getName());
            }
        }
        return merged;
    }

    private boolean isExcludedProvider(ListableBeanFactory beanFactory, String beanName) {
        Class<?> type = beanFactory.getType(beanName);
        return type != null && excludedProviderTypeNames.contains(type.getName());
    }

    private void addCallbacks(List<Tool> tools, Set<String> seenNames, ToolCallback[] callbacks,
                              ObjectMapper objectMapper,
                              IntentReactorProperties.SpringAiToolsConfig config) {
        if (callbacks == null) {
            return;
        }
        for (ToolCallback callback : callbacks) {
            if (callback == null || callback.getToolDefinition() == null) {
                log.warn("Skipping null Spring AI tool callback or definition");
                continue;
            }
            String name = callback.getToolDefinition().name();
            if (name == null || name.isBlank()) {
                log.warn("Skipping Spring AI tool with blank name");
                continue;
            }
            if (!seenNames.add(name)) {
                log.warn("Skipping duplicate Spring AI tool '{}'", name);
                continue;
            }
            tools.add(new SpringAiToolAdapter(callback, objectMapper, config));
        }
    }
}
