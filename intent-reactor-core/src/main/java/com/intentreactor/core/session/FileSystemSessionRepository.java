package com.intentreactor.core.session;

import com.intentreactor.api.Message;
import com.intentreactor.api.SessionState;
import com.intentreactor.core.config.IntentReactorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-backed {@link SessionRepository}: one JSON file per session under
 * {@code intent-reactor.session.filesystem.path}. Writes are atomic (temp file + rename),
 * per-session locks prevent concurrent corruption within the JVM.
 * <p>Legacy v0.1.x files (a whole serialized {@link SessionState}) are converted to the
 * current format on first read.
 */
public class FileSystemSessionRepository implements SessionRepository {

    private static final Logger log = LoggerFactory.getLogger(FileSystemSessionRepository.class);

    private final Path basePath;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();

    public FileSystemSessionRepository(IntentReactorProperties properties, ObjectMapper mapper) {
        String pathStr = properties.getSession().getFilesystem().getPath();
        this.basePath = Paths.get(pathStr);
        this.mapper = mapper;
        try {
            Files.createDirectories(this.basePath);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create session store directory: " + pathStr, e);
        }
    }

    @Override
    public Session save(Session session) {
        synchronized (lockFor(session.id())) {
            SessionFile file = readOrInit(session.id());
            file.session = toSessionDto(session);
            write(file, session.id());
        }
        return session;
    }

    @Override
    public Session findById(String sessionId) {
        synchronized (lockFor(sessionId)) {
            File f = sessionFile(sessionId);
            if (!f.exists()) return null;
            try {
                if (isLegacyFormat(f)) {
                    convertLegacy(f, sessionId);
                }
                SessionFile file = mapper.readValue(f, SessionFile.class);
                return toSession(file.session);
            } catch (Exception e) {
                log.error("Failed to read session {}", sessionId, e);
                return null;
            }
        }
    }

    @Override
    public List<Session> findByUserId(String userId) {
        List<Session> result = new ArrayList<>();
        File[] files = basePath.toFile().listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return result;
        for (File f : files) {
            String id = f.getName().substring(0, f.getName().length() - 5);
            Session s = findById(id);
            if (s != null && userId.equals(s.userId())) result.add(s);
        }
        return result;
    }

    @Override
    public List<String> findExpiredSessionIds(Instant before) {
        List<String> result = new ArrayList<>();
        File[] files = basePath.toFile().listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return result;
        for (File f : files) {
            String id = f.getName().substring(0, f.getName().length() - 5);
            Session s = findById(id);
            if (s != null && s.expiresAt() != null && s.expiresAt().isBefore(before)) result.add(id);
        }
        return result;
    }

    @Override
    public void delete(String sessionId) {
        synchronized (lockFor(sessionId)) {
            File file = sessionFile(sessionId);
            if (file.exists() && !file.delete()) {
                log.warn("Could not delete session file for {}", sessionId);
            }
        }
    }

    @Override
    public void appendEvent(SessionEvent event) {
        synchronized (lockFor(event.getSessionId())) {
            SessionFile file = readOrInit(event.getSessionId());
            if (containsEvent(file.events, event.getId())) return; // idempotent by id
            file.events.add(toEventDto(event));
            file.version++;
            write(file, event.getSessionId());
        }
    }

    /**
     * Appends several events in one locked read-modify-write (idempotent by event id, like
     * {@link #appendEvent}). Used by {@link SessionStateStore} for file-backed sessions to
     * avoid one full file rewrite per appended message. This method is part of the internal
     * engine contract, not of the {@link SessionRepository} SPI.
     */
    void appendEvents(String sessionId, List<SessionEvent> events) {
        if (events.isEmpty()) return;
        synchronized (lockFor(sessionId)) {
            SessionFile file = readOrInit(sessionId);
            boolean appended = false;
            for (SessionEvent event : events) {
                if (containsEvent(file.events, event.getId())) continue;
                file.events.add(toEventDto(event));
                file.version++;
                appended = true;
            }
            if (appended) {
                write(file, sessionId);
            }
        }
    }

    @Override
    public boolean compactEvents(String sessionId, List<SessionEvent> archivedEvents,
                                 List<SessionEvent> retainedEvents, long expectedVersion) {
        synchronized (lockFor(sessionId)) {
            SessionFile file = readOrInit(sessionId);
            if (file.version != expectedVersion) return false;
            file.events.clear();
            for (SessionEvent e : retainedEvents) file.events.add(toEventDto(e));
            for (SessionEvent e : archivedEvents) file.events.add(toEventDto(e));
            file.version = expectedVersion + 1;
            write(file, sessionId);
            return true;
        }
    }

    @Override
    public long getEventVersion(String sessionId) {
        synchronized (lockFor(sessionId)) {
            // readOrInit converts legacy files first, so the version is consistent with the
            // events a subsequent save/append would read.
            return readOrInit(sessionId).version;
        }
    }

    @Override
    public List<SessionEvent> findEvents(String sessionId, EventFilter filter) {
        synchronized (lockFor(sessionId)) {
            File f = sessionFile(sessionId);
            if (!f.exists()) return List.of();
            try {
                if (isLegacyFormat(f)) {
                    convertLegacy(f, sessionId);
                }
                SessionFile file = mapper.readValue(f, SessionFile.class);
                List<SessionEvent> events = new ArrayList<>();
                for (EventDto dto : file.events) events.add(toEvent(dto));
                return events;
            } catch (Exception e) {
                log.error("Failed to read events for {}", sessionId, e);
                return List.of();
            }
        }
    }

    // ---- internals ----

    private boolean isLegacyFormat(File file) throws IOException {
        return mapper.readTree(file).has("messages");
    }

    private void convertLegacy(File file, String sessionId) throws IOException {
        SessionState legacy = mapper.readValue(file, SessionState.class);
        SessionState fresh = new SessionState(sessionId);
        for (Message m : legacy.getMessages()) fresh.addMessage(m);
        fresh.getAttributes().putAll(legacy.getAttributes());
        fresh.setPlanState(legacy.getPlanState() != null ? legacy.getPlanState() : fresh.getPlanState());
        fresh.setCreatedAt(legacy.getCreatedAt());
        fresh.setUpdatedAt(legacy.getUpdatedAt());

        SessionFile converted = new SessionFile();
        converted.session = new SessionDto();
        converted.session.id = sessionId;
        converted.session.userId = sessionId;
        converted.session.createdAt = toInstant(legacy.getCreatedAt());
        converted.session.expiresAt = null;
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(SessionStateStore.METADATA_KEY, SessionStateStore.encodeState(fresh));
        converted.session.metadata = metadata;
        converted.version = fresh.getMessages().size();
        for (Message m : fresh.getMessages()) {
            converted.events.add(toEventDto(SessionEventCodec.messageToEvent(sessionId, m)));
        }
        write(converted, sessionId);
        log.info("Converted legacy session file {} to the spring-ai-session format", file.getName());
    }

    private SessionFile readOrInit(String sessionId) {
        File f = sessionFile(sessionId);
        if (!f.exists()) return new SessionFile();
        try {
            if (isLegacyFormat(f)) {
                convertLegacy(f, sessionId);
            }
            return mapper.readValue(f, SessionFile.class);
        } catch (Exception e) {
            log.error("Failed to read session file for {}, starting fresh", sessionId, e);
            return new SessionFile();
        }
    }

    private void write(SessionFile file, String sessionId) {
        try {
            Path tmp = basePath.resolve(sessionId + ".json.tmp");
            Path dest = basePath.resolve(sessionId + ".json");
            mapper.writeValue(tmp.toFile(), file);
            try {
                Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                log.warn("Atomic move not supported, falling back to regular move: {}", ex.getMessage());
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("Failed to save session file for {}", sessionId, e);
        }
    }

    private Session toSession(SessionDto dto) {
        if (dto == null) return null;
        return Session.builder().id(dto.id).userId(dto.userId)
                .createdAt(dto.createdAt).expiresAt(dto.expiresAt)
                .metadata(dto.metadata == null ? Map.of() : dto.metadata)
                .build();
    }

    private SessionDto toSessionDto(Session s) {
        SessionDto dto = new SessionDto();
        dto.id = s.id();
        dto.userId = s.userId();
        dto.createdAt = s.createdAt();
        dto.expiresAt = s.expiresAt();
        dto.metadata = s.metadata();
        return dto;
    }

    private SessionEvent toEvent(EventDto dto) {
        Map<String, Object> metadata = dto.metadata == null ? new HashMap<>() : new HashMap<>(dto.metadata);
        if (dto.synthetic) {
            // SessionEvent stores the synthetic flag in metadata (SessionEvent.isSynthetic).
            metadata.put(SessionEvent.METADATA_SYNTHETIC, Boolean.TRUE);
        }
        return SessionEvent.builder()
                .id(dto.id)
                .sessionId(dto.sessionId)
                .timestamp(dto.timestamp)
                .message(SessionEventCodec.messageForType(dto.messageType, dto.content))
                .metadata(metadata)
                .branch(dto.branch)
                .archived(dto.archived)
                .build();
    }

    private EventDto toEventDto(SessionEvent e) {
        EventDto dto = new EventDto();
        dto.id = e.getId();
        dto.sessionId = e.getSessionId();
        dto.timestamp = e.getTimestamp();
        dto.messageType = e.getMessage() instanceof org.springframework.ai.chat.messages.UserMessage ? "USER"
                : e.getMessage() instanceof org.springframework.ai.chat.messages.AssistantMessage ? "ASSISTANT" : "SYSTEM";
        dto.content = e.getMessage().getText();
        dto.metadata = e.getMetadata();
        dto.branch = e.getBranch();
        dto.archived = e.isArchived();
        dto.synthetic = e.isSynthetic();
        return dto;
    }

    private static boolean containsEvent(List<EventDto> events, String id) {
        for (EventDto dto : events) {
            if (dto.id.equals(id)) return true;
        }
        return false;
    }

    private static Instant toInstant(LocalDateTime ldt) {
        return ldt.atZone(ZoneId.systemDefault()).toInstant();
    }

    private Object lockFor(String id) {
        return sessionLocks.computeIfAbsent(id, k -> new Object());
    }

    private File sessionFile(String sessionId) {
        return basePath.resolve(sessionId + ".json").toFile();
    }

    // ---- Jackson DTOs (mutable beans; the framework avoids records for Jackson 3) ----

    static class SessionFile {
        public SessionDto session;
        public List<EventDto> events = new ArrayList<>();
        public long version;
    }

    static class SessionDto {
        public String id;
        public String userId;
        public Instant createdAt;
        public Instant expiresAt;
        public Map<String, Object> metadata;
    }

    static class EventDto {
        public String id;
        public String sessionId;
        public Instant timestamp;
        public String messageType;
        public String content;
        public Map<String, Object> metadata;
        public String branch;
        public boolean archived;
        public boolean synthetic;
    }
}
