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
