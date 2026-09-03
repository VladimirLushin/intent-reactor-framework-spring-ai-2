package com.intentreactor.core.session;

import com.intentreactor.api.Message;
import com.intentreactor.api.PlanStep;
import com.intentreactor.api.SimpleAction;
import com.intentreactor.api.SimplePlanStep;
import com.intentreactor.api.SessionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.session.jdbc.JdbcSessionRepository;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the SessionStateStore envelope (plain nested map) survives a real JSON
 * round-trip through the spring-ai-session JDBC repository with its default JsonMapper:
 * polymorphic planState data is rehydrated by SessionStateStore on load.
 */
class SessionStateStoreJdbcIntegrationTest {

    private SessionStateStore store;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:sstest;DB_CLOSE_DELAY=-1", "sa", "");
        ScriptUtils.executeSqlScript(ds.getConnection(), new org.springframework.core.io.ClassPathResource(
                "org/springframework/ai/session/jdbc/schema-h2.sql"));
        JdbcSessionRepository repo = JdbcSessionRepository.builder().dataSource(ds).build();
        store = new SessionStateStore(repo, mapper());
    }

    private static ObjectMapper mapper() {
        return JsonMapper.builder()
                .addModule(new SimpleModule()
                        .addAbstractTypeMapping(PlanStep.class, SimplePlanStep.class))
                .build();
    }

    @Test
    void fullRoundTripThroughJdbcRepository() {
        SessionState s = new SessionState("j1");
        s.addMessage(Message.pinnedUser("goal"));
        s.addMessage(Message.system("[TOOL_RESULT] tool: ok"));
        s.addMessage(Message.assistant("answer"));
        s.getAttributes().put("text", "value");
        s.getPlanState().getCompletedSteps().add(
                SimplePlanStep.act(new SimpleAction("toolA", Map.of("k", "v")), "desc", false));

        store.save(s);
        store.save(s); // second save must not duplicate events
        s.addMessage(Message.user("follow-up"));
        store.save(s);

        SessionState r = store.findById("j1").orElseThrow();
        assertThat(r.getMessages()).hasSize(4);
        assertThat(r.getMessages().get(0).isPinned()).isTrue();
        assertThat(r.getMessages().get(3).getContent()).isEqualTo("follow-up");
        assertThat(r.getAttributes().get("text")).isEqualTo("value");
        assertThat(r.getPlanState().getCompletedSteps()).hasSize(1);
        assertThat(r.getPlanState().getCompletedSteps().get(0).action().toolName()).isEqualTo("toolA");
    }
}
