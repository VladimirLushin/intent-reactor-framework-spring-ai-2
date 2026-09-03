package com.intentreactor.core.tool;

import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import com.intentreactor.core.config.IntentReactorProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiToolAdapterTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final String SCHEMA = """
            {"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}
            """;

    private static ToolCallback callback(String name) {
        ToolCallback cb = mock(ToolCallback.class);
        when(cb.getToolDefinition()).thenReturn(DefaultToolDefinition.builder()
                .name(name)
                .description("Description of " + name)
                .inputSchema(SCHEMA)
                .build());
        return cb;
    }

    @Test
    void exposesDefinitionMetadata() {
        SpringAiToolAdapter adapter = new SpringAiToolAdapter(
                callback("get_weather"), MAPPER, new IntentReactorProperties.SpringAiToolsConfig());

        assertThat(adapter.getName()).isEqualTo("get_weather");
        assertThat(adapter.getDescription()).isEqualTo("Description of get_weather");
        assertThat(adapter.getParameterSchema()).containsKeys("type", "properties", "required");
        assertThat(adapter.isGenerator()).isFalse();
    }

    @Test
    void executeSerializesParametersAndReturnsOk() throws Exception {
        ToolCallback delegate = callback("get_weather");
        when(delegate.call(anyString(), any(ToolContext.class))).thenReturn("sunny");
        SpringAiToolAdapter adapter = new SpringAiToolAdapter(
                delegate, MAPPER, new IntentReactorProperties.SpringAiToolsConfig());

        ToolResult result = adapter.execute(new ToolInput(Map.of("city", "Berlin"), "s1"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo("sunny");
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(delegate).call(jsonCaptor.capture(), any(ToolContext.class));
        assertThat(jsonCaptor.getValue()).contains("\"city\":\"Berlin\"");
    }

    @Test
    void executePassesSessionIdViaToolContext() {
        ToolCallback delegate = callback("get_weather");
        when(delegate.call(anyString(), any(ToolContext.class))).thenReturn("x");
        SpringAiToolAdapter adapter = new SpringAiToolAdapter(
                delegate, MAPPER, new IntentReactorProperties.SpringAiToolsConfig());

        adapter.execute(new ToolInput(Map.of(), "session-42"));

        ArgumentCaptor<ToolContext> ctxCaptor = ArgumentCaptor.forClass(ToolContext.class);
        verify(delegate).call(anyString(), ctxCaptor.capture());
        assertThat(ctxCaptor.getValue().getContext()).containsEntry("sessionId", "session-42");
    }

    @Test
    void executeSerializesEmptyObjectWhenParametersNull() throws Exception {
        ToolCallback delegate = callback("get_weather");
        when(delegate.call(anyString(), any(ToolContext.class))).thenReturn("ok");
        SpringAiToolAdapter adapter = new SpringAiToolAdapter(
                delegate, MAPPER, new IntentReactorProperties.SpringAiToolsConfig());

        adapter.execute(new ToolInput(null, null));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(delegate).call(jsonCaptor.capture(), any(ToolContext.class));
        assertThat(jsonCaptor.getValue()).isEqualTo("{}");
    }

    @Test
    void executeMapsThrownExceptionToErrorResult() {
        ToolCallback delegate = callback("get_weather");
        when(delegate.call(anyString(), any(ToolContext.class)))
                .thenThrow(new RuntimeException("boom"));
        SpringAiToolAdapter adapter = new SpringAiToolAdapter(
                delegate, MAPPER, new IntentReactorProperties.SpringAiToolsConfig());

        ToolResult result = adapter.execute(new ToolInput(Map.of(), "s1"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("boom");
    }

    @Test
    void isRiskyFromRiskyToolNames() {
        IntentReactorProperties.SpringAiToolsConfig config =
                new IntentReactorProperties.SpringAiToolsConfig();
        config.setRiskyToolNames(Set.of("send_email"));
        SpringAiToolAdapter safe = new SpringAiToolAdapter(
                callback("get_weather"), MAPPER, config);
        SpringAiToolAdapter dangerous = new SpringAiToolAdapter(
                callback("send_email"), MAPPER, config);

        assertThat(safe.isRisky()).isFalse();
        assertThat(dangerous.isRisky()).isTrue();
    }

    @Test
    void isRiskyWhenTreatAllAsRisky() {
        IntentReactorProperties.SpringAiToolsConfig config =
                new IntentReactorProperties.SpringAiToolsConfig();
        config.setTreatAllAsRisky(true);
        SpringAiToolAdapter adapter = new SpringAiToolAdapter(
                callback("get_weather"), MAPPER, config);

        assertThat(adapter.isRisky()).isTrue();
    }

    @Test
    void blankSchemaFallsBackToEmptyParameterMap() {
        ToolCallback cb = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("odd_tool");
        when(definition.description()).thenReturn("no schema");
        when(definition.inputSchema()).thenReturn("  ");
        when(cb.getToolDefinition()).thenReturn(definition);
        SpringAiToolAdapter adapter = new SpringAiToolAdapter(
                cb, MAPPER, new IntentReactorProperties.SpringAiToolsConfig());

        assertThat(adapter.getParameterSchema()).isEmpty();
    }
}
