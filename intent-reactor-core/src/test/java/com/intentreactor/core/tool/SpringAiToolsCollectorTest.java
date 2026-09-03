package com.intentreactor.core.tool;

import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import com.intentreactor.core.config.IntentReactorProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiToolsCollectorTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final IntentReactorProperties.SpringAiToolsConfig CFG =
            new IntentReactorProperties.SpringAiToolsConfig();
    private static final String SCHEMA = """
            {"type":"object","properties":{}}
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

    private static class StaticProvider implements ToolCallbackProvider {
        private final ToolCallback[] callbacks;

        StaticProvider(ToolCallback... callbacks) {
            this.callbacks = callbacks;
        }

        @Override
        public ToolCallback[] getToolCallbacks() {
            return callbacks;
        }
    }

    /** Provider that explodes on listing — collection must survive it. */
    private static class FailingProvider implements ToolCallbackProvider {
        @Override
        public ToolCallback[] getToolCallbacks() {
            throw new IllegalStateException("list failed");
        }
    }

    private static class IrTool implements Tool {
        private final String name;

        IrTool(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "IR tool " + name;
        }

        @Override
        public Map<String, Object> getParameterSchema() {
            return Map.of();
        }

        @Override
        public ToolResult execute(ToolInput input) {
            return ToolResult.ok(name);
        }

        @Override
        public boolean isRisky() {
            return false;
        }
    }

    @Test
    void defaultExcludedTypesAreTheMcpAndMcpServerProviders() {
        assertThat(SpringAiToolsCollector.DEFAULT_EXCLUDED_PROVIDER_TYPES).containsExactlyInAnyOrder(
                "org.springframework.ai.mcp.SyncMcpToolCallbackProvider",
                "org.springframework.ai.mcp.AsyncMcpToolCallbackProvider",
                "com.intentreactor.mcp.server.adapter.IntentReactorToolCallbackProvider",
                "com.intentreactor.mcp.server.planner.PlannerMcpCallbackProvider");
    }

    @Test
    void collectsDirectToolCallbackBeansAndFlattenedProviderBeans() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("directCb", callback("direct_tool"));
        bf.registerSingleton("provider", new StaticProvider(callback("provider_tool")));

        List<Tool> tools = new SpringAiToolsCollector()
                .collectSpringAiTools(bf, MAPPER, CFG);

        assertThat(tools).extracting(Tool::getName)
                .containsExactlyInAnyOrder("direct_tool", "provider_tool");
    }

    @Test
    void excludedProviderIsNeverInstantiatedOrInvoked() {
        ToolCallbackProvider excludedProvider = mock(ToolCallbackProvider.class);
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("excluded", excludedProvider);

        // getType() of a manually registered singleton returns the instance's runtime
        // class, so the exclusion must carry the mock's generated class name, not the
        // ToolCallbackProvider interface name. Leaving getToolCallbacks() unstubbed
        // keeps the "never invoked" property falsifiable: a collector regression that
        // instantiates or lists the provider would call it and fail the verify below.
        List<Tool> tools = new SpringAiToolsCollector(Set.of(excludedProvider.getClass().getName()))
                .collectSpringAiTools(bf, MAPPER, CFG);

        assertThat(tools).isEmpty();
        verify(excludedProvider, never()).getToolCallbacks();
    }

    @Test
    void failingProviderDoesNotBreakCollectionOfOthers() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("failing", new FailingProvider());
        bf.registerSingleton("good", callback("good_tool"));

        List<Tool> tools = new SpringAiToolsCollector()
                .collectSpringAiTools(bf, MAPPER, CFG);

        assertThat(tools).extracting(Tool::getName).containsExactly("good_tool");
    }

    @Test
    void duplicateNamesWithinSpringAiSourcesAreDeduplicated() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("cb1", callback("dup_tool"));
        bf.registerSingleton("provider", new StaticProvider(callback("dup_tool")));

        List<Tool> tools = new SpringAiToolsCollector()
                .collectSpringAiTools(bf, MAPPER, CFG);

        assertThat(tools).extracting(Tool::getName).containsExactly("dup_tool");
    }

    @Test
    void blankNamedCallbackIsSkipped() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        ToolCallback blank = mock(ToolCallback.class);
        ToolDefinition blankDefinition = mock(ToolDefinition.class);
        when(blankDefinition.name()).thenReturn("  ");
        when(blank.getToolDefinition()).thenReturn(blankDefinition);
        bf.registerSingleton("blank", blank);
        bf.registerSingleton("good", callback("good_tool"));

        List<Tool> tools = new SpringAiToolsCollector()
                .collectSpringAiTools(bf, MAPPER, CFG);

        assertThat(tools).extracting(Tool::getName).containsExactly("good_tool");
    }

    @Test
    void mergeWithIrToolsKeepsIrOrderAndIrWinsConflicts() {
        List<Tool> saTools = List.of(new IrTool("shared"), new IrTool("sa_only"));
        List<Tool> irTools = List.of(new IrTool("shared"), new IrTool("ir_only"));

        List<Tool> merged = new SpringAiToolsCollector().mergeWithIrTools(irTools, saTools);

        assertThat(merged).extracting(Tool::getName)
                .containsExactly("shared", "ir_only", "sa_only");
    }
}
