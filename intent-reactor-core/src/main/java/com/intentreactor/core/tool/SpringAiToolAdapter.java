package com.intentreactor.core.tool;

import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import com.intentreactor.core.config.IntentReactorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Adapts a Spring AI {@link ToolCallback} (registered as a bean or returned by a
 * {@code ToolCallbackProvider} bean) to the IntentReactor {@link Tool} interface so that
 * Spring AI-authored tools become available to IntentReactor planners.
 *
 * <p>IntentReactor-specific semantics that Spring AI does not model are derived from
 * configuration: {@link #isRisky()} uses
 * {@code intent-reactor.tools.spring-ai.risky-tool-names} /
 * {@code intent-reactor.tools.spring-ai.treat-all-as-risky}. The adapter is never a
 * generator and is not simulatable (LATS gives it a neutral reward during search).
 */
public class SpringAiToolAdapter implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SpringAiToolAdapter.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ToolCallback delegate;
    private final ObjectMapper objectMapper;
    private final IntentReactorProperties.SpringAiToolsConfig config;
    private final ToolDefinition definition;
    private final Map<String, Object> parameterSchema;

    public SpringAiToolAdapter(ToolCallback delegate,
                               ObjectMapper objectMapper,
                               IntentReactorProperties.SpringAiToolsConfig config) {
        this.delegate = delegate;
        this.objectMapper = objectMapper;
        this.config = config;
        this.definition = delegate.getToolDefinition();
        this.parameterSchema = parseSchema(definition.inputSchema());
    }

    @Override
    public String getName() {
        return definition.name();
    }

    @Override
    public String getDescription() {
        return definition.description();
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return parameterSchema;
    }

    @Override
    public ToolResult execute(ToolInput input) {
        try {
            String json = objectMapper.writeValueAsString(
                    input.getParameters() != null ? input.getParameters() : Collections.emptyMap());
            String result = delegate.call(json, buildToolContext(input.getSessionId()));
            return ToolResult.ok(result);
        } catch (Exception e) {
            log.warn("Spring AI tool '{}' threw exception", getName(), e);
            String detail = e.getMessage() != null ? e.getMessage() : e.toString();
            return ToolResult.error("Spring AI tool execution failed: " + detail);
        }
    }

    @Override
    public boolean isRisky() {
        return config.isTreatAllAsRisky() || config.getRiskyToolNames().contains(getName());
    }

    @Override
    public boolean isGenerator() {
        return false;
    }

    private ToolContext buildToolContext(String sessionId) {
        Map<String, Object> context = new HashMap<>();
        if (sessionId != null) {
            context.put("sessionId", sessionId);
        }
        return new ToolContext(context);
    }

    private Map<String, Object> parseSchema(String inputSchema) {
        if (inputSchema == null || inputSchema.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(inputSchema, MAP_TYPE);
        } catch (Exception e) {
            log.warn("Could not parse input schema of Spring AI tool '{}': {}", getName(), e.getMessage());
            return Collections.emptyMap();
        }
    }
}
