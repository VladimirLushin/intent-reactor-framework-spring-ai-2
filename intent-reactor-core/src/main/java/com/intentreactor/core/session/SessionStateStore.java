package com.intentreactor.core.session;

import com.intentreactor.api.Message;
import com.intentreactor.api.PlanState;
import com.intentreactor.api.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Session facade of the execution engine over the spring-ai-session SPI.
 * <p>
 * History messages are stored as {@link SessionEvent}s (append-only; the engine never
 * removes messages). Planning state and attributes travel as one nested map under
 * {@value #METADATA_KEY} in {@link Session#metadata()}: in-memory repositories keep the
 * live objects (same semantics as the former in-memory store), serializing repositories
 * persist plain JSON which {@link #applyDecodedState} rehydrates with the configured
 * {@link ObjectMapper}.
 * <p>
 * Users plug custom persistence by providing their own {@link SessionRepository} bean —
 * it wins over the built-in in-memory/filesystem fallbacks (see auto-configuration).
 * <p>
 * Concurrency note: concurrent requests mutating the same session id are not supported
 * (same limitation as the previous whole-row save).
 */
public class SessionStateStore {

    public static final String METADATA_KEY = "com.intentreactor.state";

    private static final Logger log = LoggerFactory.getLogger(SessionStateStore.class);

    static final String PLAN_STATE_KEY = "planState";
    static final String ATTRIBUTES_KEY = "attributes";
    static final String CREATED_AT_KEY = "createdAt";
    static final String UPDATED_AT_KEY = "updatedAt";

    /** Framework attribute keys whose values are rehydrated from maps after a JSON round-trip. */
    private static final Map<String, Class<?>> REHYDRATABLE = new HashMap<>();

    static {
        REHYDRATABLE.put("multiIntentState", com.intentreactor.api.MultiIntentContext.class);
        REHYDRATABLE.put("originalIntent", com.intentreactor.api.IntentAnalysisResult.class);
        REHYDRATABLE.put("searchTree", com.intentreactor.core.planner.search.DefaultSearchTree.class);
    }

    private final SessionRepository sessionRepository;
    private final ObjectMapper objectMapper;

    public SessionStateStore(SessionRepository sessionRepository, ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
    }

    public Optional<SessionState> findById(String sessionId) {
        Session session = sessionRepository.findById(sessionId);
        if (session == null) return Optional.empty();
        List<SessionEvent> events = sessionRepository.findEvents(sessionId, EventFilter.all());
        SessionState state = new SessionState(sessionId);
        for (SessionEvent event : events) {
            state.addMessage(SessionEventCodec.eventToMessage(event));
        }
        applyDecodedState(state, (session.metadata() == null ? Map.of() : session.metadata()).get(METADATA_KEY));
        return Optional.of(state);
    }

    public void save(SessionState sessionState) {
        sessionState.touch();
        long version = sessionRepository.getEventVersion(sessionState.getId());
        Session existing = sessionRepository.findById(sessionState.getId());
        Map<String, Object> envelope = encodeState(sessionState);
        Session session;
        if (existing == null) {
            session = Session.builder().id(sessionState.getId()).userId(sessionState.getId())
                    .createdAt(Instant.now()).expiresAt(null)
                    .metadata(Map.of(METADATA_KEY, envelope)).build();
        } else {
            // Keep foreign metadata keys (session service, user code) — only our envelope is replaced.
            Map<String, Object> metadata = existing.metadata() == null
                    ? new HashMap<>()
                    : new HashMap<>(existing.metadata());
            metadata.put(METADATA_KEY, envelope);
            session = Session.builder().id(existing.id()).userId(existing.userId())
                    .createdAt(existing.createdAt()).expiresAt(existing.expiresAt())
                    .metadata(metadata).build();
        }
        sessionRepository.save(session);
        List<Message> messages = sessionState.getMessages();
        if (messages.size() > version) {
            List<SessionEvent> newEvents = new ArrayList<>();
            for (int i = (int) version; i < messages.size(); i++) {
                newEvents.add(SessionEventCodec.messageToEvent(sessionState.getId(), messages.get(i)));
            }
            if (sessionRepository instanceof FileSystemSessionRepository fileSystemRepository) {
                // One locked read-modify-write for the whole batch instead of N full rewrites.
                fileSystemRepository.appendEvents(sessionState.getId(), newEvents);
            } else {
                for (SessionEvent event : newEvents) {
                    sessionRepository.appendEvent(event);
                }
            }
        }
    }

    public void delete(String sessionId) {
        sessionRepository.delete(sessionId);
    }

    /** Encodes the non-message part of a session as the envelope map stored in Session.metadata. */
    static Map<String, Object> encodeState(SessionState state) {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put(PLAN_STATE_KEY, state.getPlanState());
        envelope.put(ATTRIBUTES_KEY, state.getAttributes());
        envelope.put(CREATED_AT_KEY, state.getCreatedAt());
        envelope.put(UPDATED_AT_KEY, state.getUpdatedAt());
        return envelope;
    }

    /** Applies a decoded envelope (raw map from the repository) onto a freshly loaded state. */
    @SuppressWarnings("unchecked")
    private void applyDecodedState(SessionState state, Object rawEnvelope) {
        if (!(rawEnvelope instanceof Map<?, ?> envelope)) return;
        Object rawPlan = envelope.get(PLAN_STATE_KEY);
        if (rawPlan instanceof PlanState planState) {
            state.setPlanState(planState);
        } else if (rawPlan instanceof Map<?, ?>) {
            try {
                state.setPlanState(objectMapper.convertValue(rawPlan, PlanState.class));
            } catch (Exception e) {
                log.warn("Failed to rehydrate planState for session {}", state.getId(), e);
            }
        }
        Object rawAttrs = envelope.get(ATTRIBUTES_KEY);
        if (rawAttrs instanceof Map<?, ?> attrs) {
            for (Map.Entry<?, ?> entry : attrs.entrySet()) {
                if (!(entry.getKey() instanceof String key)) continue;
                Object value = entry.getValue();
                if (value instanceof Map<?, ?> && REHYDRATABLE.containsKey(key)) {
                    try {
                        value = objectMapper.convertValue(value, REHYDRATABLE.get(key));
                    } catch (Exception e) {
                        log.warn("Failed to rehydrate attribute {} for session {}", key, state.getId(), e);
                    }
                }
                state.getAttributes().put(key, value);
            }
        }
        state.setCreatedAt(coerceTimestamp(envelope.get(CREATED_AT_KEY), state.getCreatedAt()));
        state.setUpdatedAt(coerceTimestamp(envelope.get(UPDATED_AT_KEY), state.getUpdatedAt()));
    }

    private static LocalDateTime coerceTimestamp(Object raw, LocalDateTime fallback) {
        if (raw instanceof LocalDateTime ldt) return ldt;
        if (raw instanceof String s) {
            try {
                return LocalDateTime.parse(s);
            } catch (Exception ignored) {
                // fall through
            }
        }
        return fallback;
    }
}
