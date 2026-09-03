package com.intentreactor.core.session;

import com.intentreactor.api.Message;
import com.intentreactor.api.SessionState;
import com.intentreactor.core.config.IntentReactorProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FileSystemSessionRepositoryTest {

    @TempDir
    Path tempDir;

    private FileSystemSessionRepository repo;
    private IntentReactorProperties properties;

    @BeforeEach
    void setUp() {
        properties = new IntentReactorProperties();
        properties.getSession().getFilesystem().setPath(tempDir.toString());
        repo = new FileSystemSessionRepository(properties, JsonMapper.builder().build());
    }

    private Session newSession(String id) {
        return Session.builder().id(id).userId(id).createdAt(java.time.Instant.now())
                .expiresAt(null).metadata(Map.of()).build();
    }

    @Test
    void saveAndFindByIdRoundTrip() {
        repo.save(newSession("s1"));
        Session found = repo.findById("s1");
        assertThat(found).isNotNull();
        assertThat(found.id()).isEqualTo("s1");
        assertThat(found.userId()).isEqualTo("s1");
    }

    @Test
    void appendAndFindEventsPreserveOrderAndVersion() {
        repo.save(newSession("s2"));
        repo.appendEvent(SessionEvent.builder().id(UUID.randomUUID().toString()).sessionId("s2")
                .message(new UserMessage("one")).metadata(Map.of()).build());
        repo.appendEvent(SessionEvent.builder().id(UUID.randomUUID().toString()).sessionId("s2")
                .message(new UserMessage("two")).metadata(Map.of()).build());
        assertThat(repo.getEventVersion("s2")).isEqualTo(2);
        List<SessionEvent> events = repo.findEvents("s2", EventFilter.all());
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getMessage().getText()).isEqualTo("one");
        assertThat(events.get(1).getMessage().getText()).isEqualTo("two");
    }

    @Test
    void appendEventIsIdempotentById() {
        repo.save(newSession("s3"));
        SessionEvent e = SessionEvent.builder().id("fixed-id").sessionId("s3")
                .message(new UserMessage("x")).metadata(Map.of()).build();
        repo.appendEvent(e);
        repo.appendEvent(e);
        assertThat(repo.getEventVersion("s3")).isEqualTo(1);
        assertThat(repo.findEvents("s3", EventFilter.all())).hasSize(1);
    }

    @Test
    void deleteRemovesFile() {
        repo.save(newSession("s4"));
        repo.delete("s4");
        assertThat(repo.findById("s4")).isNull();
        assertThat(tempDir.resolve("s4.json")).doesNotExist();
    }

    @Test
    void legacySessionStateFileIsConvertedOnFirstRead() throws Exception {
        // legacy v0.1.x format: a full SessionState JSON
        SessionState legacy = new SessionState("s5");
        legacy.addMessage(Message.user("old question"));
        legacy.addMessage(Message.system("[TOOL_RESULT] tool: ok"));
        legacy.getAttributes().put("keep", "me");
        Path file = tempDir.resolve("s5.json");
        new ObjectMapper().writeValue(file.toFile(), legacy);

        Session session = repo.findById("s5");
        assertThat(session).isNotNull();
        assertThat(session.id()).isEqualTo("s5");
        List<SessionEvent> events = repo.findEvents("s5", EventFilter.all());
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getMessage().getText()).isEqualTo("old question");
    }

    @Test
    void legacySessionStateFileIsConvertedOnWritePathsToo() throws Exception {
        // legacy v0.1.x format: a full SessionState JSON
        SessionState legacy = new SessionState("s7");
        legacy.addMessage(Message.user("old question"));
        legacy.addMessage(Message.system("[TOOL_RESULT] tool: ok"));
        Path file = tempDir.resolve("s7.json");
        new ObjectMapper().writeValue(file.toFile(), legacy);

        // getEventVersion must convert first, not report 0
        assertThat(repo.getEventVersion("s7")).isEqualTo(2);
        // appendEvent/save on a legacy file must not drop the converted messages
        repo.appendEvent(SessionEvent.builder().id(UUID.randomUUID().toString()).sessionId("s7")
                .message(new UserMessage("new")).metadata(Map.of()).build());
        List<SessionEvent> events = repo.findEvents("s7", EventFilter.all());
        assertThat(events).hasSize(3);
        assertThat(events.get(0).getMessage().getText()).isEqualTo("old question");
        assertThat(events.get(2).getMessage().getText()).isEqualTo("new");
    }

    @Test
    void syntheticFlagSurvivesRoundTrip() {
        repo.save(newSession("s8"));
        repo.appendEvent(SessionEvent.builder().id("syn-id").sessionId("s8")
                .message(new UserMessage("compacted away"))
                .metadata(Map.of(SessionEvent.METADATA_SYNTHETIC, true)).build());
        List<SessionEvent> events = repo.findEvents("s8", EventFilter.all());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).isSynthetic()).isTrue();
        assertThat(events.get(0).getMetadata()).containsKey(SessionEvent.METADATA_SYNTHETIC);
    }

    @Test
    void appendEventsBatchAppendsOnceAndIsIdempotentById() {
        repo.save(newSession("s9"));
        SessionEvent e1 = SessionEvent.builder().id("e1").sessionId("s9")
                .message(new UserMessage("one")).metadata(Map.of()).build();
        SessionEvent e2 = SessionEvent.builder().id("e2").sessionId("s9")
                .message(new UserMessage("two")).metadata(Map.of()).build();
        repo.appendEvents("s9", List.of(e1, e2));
        assertThat(repo.getEventVersion("s9")).isEqualTo(2);
        repo.appendEvents("s9", List.of(e1, e2));
        assertThat(repo.getEventVersion("s9")).isEqualTo(2);
        assertThat(repo.findEvents("s9", EventFilter.all())).hasSize(2);
    }

    @Test
    void compactEventsUsesExpectedVersionCas() {
        repo.save(newSession("s6"));
        repo.appendEvent(SessionEvent.builder().id(UUID.randomUUID().toString()).sessionId("s6")
                .message(new UserMessage("a")).metadata(Map.of()).build());
        boolean ok = repo.compactEvents("s6", List.of(), List.of(), 1L);
        assertThat(ok).isTrue();
        assertThat(repo.getEventVersion("s6")).isEqualTo(2);
        assertThat(repo.compactEvents("s6", List.of(), List.of(), 1L)).isFalse();
    }
}
