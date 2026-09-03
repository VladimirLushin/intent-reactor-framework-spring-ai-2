package com.intentreactor.core.session;

import com.intentreactor.api.Message;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.SessionEvent;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mapping between the framework's {@link Message} history model and spring-ai-session
 * {@link SessionEvent}s. Kept in the same package so both {@link SessionStateStore} and
 * {@link FileSystemSessionRepository} (legacy file conversion) can reuse it.
 */
final class SessionEventCodec {

    static final String PINNED_KEY = "com.intentreactor.pinned";
    static final String TIMESTAMP_KEY = "com.intentreactor.ts";

    private SessionEventCodec() {
    }

    static SessionEvent messageToEvent(String sessionId, Message message) {
        org.springframework.ai.chat.messages.Message aiMessage = switch (message.getRole()) {
            case USER -> new UserMessage(message.getContent());
            case ASSISTANT -> new AssistantMessage(message.getContent());
            case SYSTEM -> new SystemMessage(message.getContent());
        };
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(PINNED_KEY, message.isPinned());
        LocalDateTime ts = message.getTimestamp() != null ? message.getTimestamp() : LocalDateTime.now();
        metadata.put(TIMESTAMP_KEY, ts.toString());
        return SessionEvent.builder()
                .id(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .timestamp(toInstant(ts))
                .message(aiMessage)
                .metadata(metadata)
                .build();
    }

    static Message eventToMessage(SessionEvent event) {
        org.springframework.ai.chat.messages.Message ai = event.getMessage();
        Map<String, Object> metadata = event.getMetadata() == null ? Map.of() : event.getMetadata();
        Message message;
        if (ai instanceof UserMessage) {
            message = Boolean.TRUE.equals(metadata.get(PINNED_KEY))
                    ? Message.pinnedUser(ai.getText())
                    : Message.user(ai.getText());
        } else if (ai instanceof AssistantMessage) {
            message = Message.assistant(ai.getText());
        } else {
            // SystemMessage, ToolResponseMessage and any other type map to our SYSTEM role
            message = Message.system(ai.getText());
        }
        Object ts = metadata.get(TIMESTAMP_KEY);
        if (ts instanceof String s) {
            try {
                message.setTimestamp(LocalDateTime.parse(s));
            } catch (Exception ignored) {
                // keep the factory timestamp
            }
        } else if (event.getTimestamp() != null) {
            // Foreign (spring-ai-session native) events carry no framework timestamp key —
            // fall back to the event timestamp instead of the factory "now".
            message.setTimestamp(toLocalDateTime(event.getTimestamp()));
        }
        return message;
    }

    /** Rebuilds a Spring AI message from the persisted role name and text. */
    static org.springframework.ai.chat.messages.Message messageForType(String roleName, String content) {
        return switch (roleName == null ? "SYSTEM" : roleName) {
            case "USER" -> new UserMessage(content == null ? "" : content);
            case "ASSISTANT" -> new AssistantMessage(content == null ? "" : content);
            default -> new SystemMessage(content == null ? "" : content);
        };
    }

    static Instant toInstant(LocalDateTime localDateTime) {
        return localDateTime.atZone(ZoneId.systemDefault()).toInstant();
    }

    static LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
