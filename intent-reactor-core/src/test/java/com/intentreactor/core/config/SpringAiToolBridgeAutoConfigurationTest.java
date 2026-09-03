package com.intentreactor.core.config;

import com.intentreactor.api.SessionState;
import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolProvider;
import com.intentreactor.api.ToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the Spring AI tool bridge wiring in {@link IntentReactorAutoConfiguration}:
 * SA tool callbacks end up in the auto-configured {@link ToolProvider}.
 */
class SpringAiToolBridgeAutoConfigurationTest {

    private static final ChatClient.Builder MOCK_CHAT_CLIENT_BUILDER = chatClientBuilder();

    private static ChatClient.Builder chatClientBuilder() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        return builder;
    }

    /** Native IR tool used as a baseline in the assertions. */
    private static class NativeIrTool implements Tool {
        @Override
        public String getName() {
            return "native_ir_tool";
        }

        @Override
        public String getDescription() {
            return "A native IntentReactor tool";
        }

        @Override
        public Map<String, Object> getParameterSchema() {
            return Map.of();
        }

        @Override
        public ToolResult execute(ToolInput input) {
            return ToolResult.ok("native");
        }

        @Override
        public boolean isRisky() {
            return false;
        }
    }

    private static class StaticSaProvider implements ToolCallbackProvider {
        private final ToolCallback[] callbacks;

        StaticSaProvider(ToolCallback... callbacks) {
            this.callbacks = callbacks;
        }

        @Override
        public ToolCallback[] getToolCallbacks() {
            return callbacks;
        }
    }

    private static ToolCallback saCallback(String name) {
        ToolCallback cb = mock(ToolCallback.class);
        when(cb.getToolDefinition()).thenReturn(DefaultToolDefinition.builder()
                .name(name)
                .description("Spring AI tool " + name)
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build());
        return cb;
    }

    @Test
    void defaultToolProviderBridgesSpringAiTools() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IntentReactorAutoConfiguration.class))
                .withBean(ChatClient.Builder.class, () -> MOCK_CHAT_CLIENT_BUILDER)
                .withBean(NativeIrTool.class)
                .withBean("directSaTool", ToolCallback.class, () -> saCallback("sa_direct"))
                .withBean("saProvider", ToolCallbackProvider.class,
                        () -> new StaticSaProvider(saCallback("sa_provider")))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ToolProvider.class);
                    ToolProvider provider = context.getBean(ToolProvider.class);
                    assertThat(provider.getAvailableTools(new SessionState("s")))
                            .extracting(Tool::getName)
                            .containsExactlyInAnyOrder("native_ir_tool", "sa_direct", "sa_provider");
                });
    }

    @Test
    void disabledBridgeIgnoresSpringAiTools() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IntentReactorAutoConfiguration.class))
                .withPropertyValues("intent-reactor.tools.spring-ai.enabled=false")
                .withBean(ChatClient.Builder.class, () -> MOCK_CHAT_CLIENT_BUILDER)
                .withBean(NativeIrTool.class)
                .withBean("directSaTool", ToolCallback.class, () -> saCallback("sa_direct"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ToolProvider provider = context.getBean(ToolProvider.class);
                    assertThat(provider.getAvailableTools(new SessionState("s")))
                            .extracting(Tool::getName)
                            .containsExactly("native_ir_tool");
                });
    }
}
