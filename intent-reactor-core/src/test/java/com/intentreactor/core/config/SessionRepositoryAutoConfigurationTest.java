package com.intentreactor.core.config;

import com.intentreactor.core.session.SessionStateStore;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.jdbc.JdbcSessionRepository;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springaicommunity.session.jdbc.autoconfigure.JdbcSessionRepositoryAutoConfiguration;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for the auto-configuration ordering between the spring-ai-session JDBC
 * auto-configuration and {@link IntentReactorAutoConfiguration}: their
 * {@code JdbcSessionRepositoryAutoConfiguration} declares {@code jdbcSessionRepository}
 * with a bare {@code @ConditionalOnMissingBean} (derived from the return type), so it
 * never backs off for our {@code @ConditionalOnMissingBean(SessionRepository.class)}
 * in-memory/filesystem fallbacks. Without the {@code @AutoConfigureAfter} on
 * {@link IntentReactorAutoConfiguration}, both repositories would be registered and the
 * context would fail with {@code NoUniqueBeanDefinitionException} when creating
 * {@code sessionStateStore}.
 */
class SessionRepositoryAutoConfigurationTest {

    private static final ChatClient.Builder MOCK_CHAT_CLIENT_BUILDER = chatClientBuilder();

    private static ChatClient.Builder chatClientBuilder() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        return builder;
    }

    @Test
    void withJdbcAutoConfigurationOnlyTheJdbcRepositoryIsRegistered() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        JdbcSessionRepositoryAutoConfiguration.class,
                        IntentReactorAutoConfiguration.class))
                .withBean(DataSource.class, () -> new DriverManagerDataSource(
                        "jdbc:h2:mem:acctest;DB_CLOSE_DELAY=-1", "sa", ""))
                .withBean(ChatClient.Builder.class, () -> MOCK_CHAT_CLIENT_BUILDER)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SessionStateStore.class);
                    assertThat(context).hasSingleBean(SessionRepository.class);
                    assertThat(context.getBean(SessionRepository.class))
                            .isInstanceOf(JdbcSessionRepository.class);
                    assertThat(context).doesNotHaveBean("inMemorySessionRepository");
                    assertThat(context).doesNotHaveBean("fileSystemSessionRepository");
                });
    }

    @Test
    void withoutJdbcAutoConfigurationTheInMemoryFallbackIsUsed() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IntentReactorAutoConfiguration.class))
                .withBean(ChatClient.Builder.class, () -> MOCK_CHAT_CLIENT_BUILDER)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SessionStateStore.class);
                    assertThat(context).hasSingleBean(SessionRepository.class);
                    assertThat(context.getBean(SessionRepository.class))
                            .isInstanceOf(InMemorySessionRepository.class);
                    assertThat(context).doesNotHaveBean("fileSystemSessionRepository");
                });
    }
}
