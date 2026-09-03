package com.intentreactor.core.session;

import com.intentreactor.api.Message;
import com.intentreactor.api.PlanState;
import com.intentreactor.api.PlanStatus;
import com.intentreactor.api.PlanStep;
import com.intentreactor.api.SimpleAction;
import com.intentreactor.api.SimplePlanStep;
import com.intentreactor.api.SessionState;
import com.intentreactor.core.config.IntentReactorProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SessionStateStoreTest {

    private SessionStateStore store;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = JsonMapper.builder()
                .addModule(new SimpleModule()
                        .addAbstractTypeMapping(PlanStep.class, SimplePlanStep.class))
                .build();
        store = new SessionStateStore(InMemorySessionRepository.builder().build(), mapper);
    }

    private SessionState sessionWith(String id, Message... messages) {
        SessionState s = new SessionState(id);
        for (Message m : messages) s.addMessage(m);
        return s;
    }

    @Test
    void saveAndFindRoundTripsMessagesStateAndAttributes() {
        SessionState s = sessionWith("s1",
                Message.pinnedUser("first goal"),
                Message.system("[TOOL_RESULT] tool: {\"ok\":true}"),
                Message.assistant("done"));
        s.getAttributes().put("appKey", "appValue");
        s.getAttributes().put("count", 42);
        PlanState plan = new PlanState("goal-1");
        plan.setStatus(PlanStatus.COMPLETED);
        s.setPlanState(plan);
        s.getPlanState().getCompletedSteps().add(
                SimplePlanStep.act(new SimpleAction("t1", Map.of()), "desc", false));

        store.save(s);

        Optional<SessionState> loaded = store.findById("s1");
        assertThat(loaded).isPresent();
        SessionState r = loaded.get();
        assertThat(r.getMessages()).extracting(Message::getRole)
                .containsExactly(Message.Role.USER, Message.Role.SYSTEM, Message.Role.ASSISTANT);
        assertThat(r.getMessages().get(0).isPinned()).isTrue();
        assertThat(r.getMessages().get(0).getTimestamp()).isEqualTo(s.getMessages().get(0).getTimestamp());
        assertThat(r.getMessages()).extracting(Message::getContent)
                .containsExactly("first goal", "[TOOL_RESULT] tool: {\"ok\":true}", "done");
        assertThat(r.getAttributes().get("appKey")).isEqualTo("appValue");
        assertThat(r.getAttributes().get("count")).isEqualTo(42);
        assertThat(r.getPlanState().getGoalDescription()).isEqualTo("goal-1");
        assertThat(r.getPlanState().getStatus()).isEqualTo(PlanStatus.COMPLETED);
        assertThat(r.getPlanState().getCompletedSteps()).hasSize(1);
        assertThat(r.getPlanState().getCompletedSteps().get(0)).isInstanceOf(PlanStep.class);
        assertThat(r.getCreatedAt()).isEqualTo(s.getCreatedAt());
        assertThat(r.getUpdatedAt()).isEqualTo(s.getUpdatedAt());
    }

    @Test
    void repeatedSavesDoNotDuplicateMessages() {
        SessionState s = sessionWith("s2", Message.user("hi"));
        store.save(s);
        store.save(s);
        s.addMessage(Message.assistant("answer"));
        store.save(s);
        store.save(s);
        assertThat(store.findById("s2").orElseThrow().getMessages()).hasSize(2);
    }

    @Test
    void freshStateFromMissingSessionAppendsAllMessagesOnFirstSave() {
        SessionState s = new SessionState("s3"); // not loaded via findById
        s.addMessage(Message.user("q"));
        store.save(s);
        s.addMessage(Message.assistant("a"));
        store.save(s);
        assertThat(store.findById("s3").orElseThrow().getMessages()).hasSize(2);
    }

    @Test
    void missingSessionReturnsEmpty() {
        assertThat(store.findById("nope")).isEmpty();
    }

    @Test
    void deleteRemovesSession() {
        SessionState s = sessionWith("s4", Message.user("x"));
        store.save(s);
        store.delete("s4");
        assertThat(store.findById("s4")).isEmpty();
    }

    @Test
    void foreignEventDecodesByMessageType() {
        InMemorySessionRepository repo = InMemorySessionRepository.builder().build();
        SessionStateStore st = new SessionStateStore(repo, mapper);
        st.save(sessionWith("s5", Message.user("seed")));
        // simulated foreign (spring-ai-session native) event:
        repo.appendEvent(SessionEvent.builder()
                .id("foreign-1")
                .sessionId("s5")
                .message(new UserMessage("hello from ecosystem"))
                .metadata(Map.of())
                .build());
        List<Message> msgs = st.findById("s5").orElseThrow().getMessages();
        assertThat(msgs).hasSize(2);
        assertThat(msgs.get(1).getRole()).isEqualTo(Message.Role.USER);
        assertThat(msgs.get(1).getContent()).isEqualTo("hello from ecosystem");
    }

    @Test
    void typedAttributeValuesSurviveInMemoryRoundTrip() {
        SessionState s = sessionWith("s6", Message.user("multi"));
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("currentIntent", "it1");
        s.getAttributes().put("multiIntentState", ctx);
        store.save(s);
        // In-memory path keeps live objects: rehydrate must not replace the map
        Object v = store.findById("s6").orElseThrow().getAttributes().get("multiIntentState");
        assertThat(v).isEqualTo(ctx);
    }

    @Test
    void sessionWithNoMessagesAndAttributesOnly() {
        SessionState s = new SessionState("s7");
        s.getAttributes().put("only", "attr");
        store.save(s);
        SessionState r = store.findById("s7").orElseThrow();
        assertThat(r.getMessages()).isEmpty();
        assertThat(r.getAttributes().get("only")).isEqualTo("attr");
    }

    @Test
    void foreignEventTimestampFallsBackToEventTimestampWhenTsKeyMissing() {
        InMemorySessionRepository repo = InMemorySessionRepository.builder().build();
        SessionStateStore st = new SessionStateStore(repo, mapper);
        st.save(sessionWith("s5b", Message.user("seed")));
        Instant ts = Instant.parse("2024-05-01T10:15:30Z");
        // simulated foreign (spring-ai-session native) event: no com.intentreactor.ts key
        repo.appendEvent(SessionEvent.builder()
                .id("foreign-ts")
                .sessionId("s5b")
                .timestamp(ts)
                .message(new UserMessage("hello from ecosystem"))
                .metadata(Map.of())
                .build());
        List<Message> msgs = st.findById("s5b").orElseThrow().getMessages();
        assertThat(msgs).hasSize(2);
        assertThat(msgs.get(1).getTimestamp())
                .isEqualTo(LocalDateTime.ofInstant(ts, ZoneId.systemDefault()));
    }

    @Test
    void messageWithoutTimestampDoesNotBreakEventMapping() {
        SessionState s = sessionWith("t0", Message.user("no ts"));
        s.getMessages().get(0).setTimestamp(null);
        store.save(s);
        Message r = store.findById("t0").orElseThrow().getMessages().get(0);
        assertThat(r.getTimestamp()).isNotNull();
        assertThat(r.getContent()).isEqualTo("no ts");
    }

    @Test
    void saveKeepsForeignSessionMetadataKeys() {
        InMemorySessionRepository repo = InMemorySessionRepository.builder().build();
        repo.save(Session.builder().id("m1").userId("u1").createdAt(Instant.now())
                .metadata(Map.of("customerKey", "customerValue")).build());
        SessionStateStore st = new SessionStateStore(repo, mapper);
        st.save(sessionWith("m1", Message.user("hi")));

        Map<String, Object> md = repo.findById("m1").metadata();
        assertThat(md).containsEntry("customerKey", "customerValue");
        assertThat(md).containsKey(SessionStateStore.METADATA_KEY);
    }

    @Test
    void storeRoundTripOverFileSystemRepository(@TempDir java.nio.file.Path tempDir) {
        IntentReactorProperties props = new IntentReactorProperties();
        props.getSession().getFilesystem().setPath(tempDir.toString());
        FileSystemSessionRepository fileRepo = new FileSystemSessionRepository(props, mapper);
        SessionStateStore fileStore = new SessionStateStore(fileRepo, mapper);

        SessionState s = sessionWith("fs1", Message.user("q"), Message.assistant("a"));
        fileStore.save(s);
        s.addMessage(Message.user("q2"));
        fileStore.save(s);

        SessionState r = fileStore.findById("fs1").orElseThrow();
        assertThat(r.getMessages()).hasSize(3);
        assertThat(r.getMessages().get(1).getContent()).isEqualTo("a");
    }
}
