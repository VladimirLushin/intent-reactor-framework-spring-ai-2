# Session Stores

`SessionState` holds the complete mutable state of a conversation — message history, plan progress, and arbitrary attributes. The engine persists it between calls through `SessionStateStore`, a facade in `intent-reactor-core` (package `com.intentreactor.core.session`) built on top of the **spring-ai-session** persistence SPI — `org.springframework.ai.session.SessionRepository` from `org.springaicommunity:spring-ai-session` 0.8.0.

---

## Storage model

spring-ai-session separates a session into identity/lifecycle data (`Session`) and an append-only history of `SessionEvent`s. IntentReactor maps its `SessionState` onto that model without changing engine semantics:

| `SessionState` | spring-ai-session |
|---|---|
| Message history (`messages`; append-only) | One `SessionEvent` per message. The event wraps a Spring AI message: role `USER` → `UserMessage`, `ASSISTANT` → `AssistantMessage`, `SYSTEM` → `SystemMessage` (tool-result markers like `[TOOL_RESULT]` stay plain text). The pinned flag and the original timestamp are kept in the event metadata, so the round-trip is lossless. History order = event order. |
| `planState`, attributes, `createdAt`, `updatedAt` | One nested envelope map in `Session.metadata` under the key `com.intentreactor.state` |
| `id` | `Session.id`; `userId` = `sessionId` (the framework has no user concept) |
| Expiration | None: `expiresAt` is `null`, so sessions never expire (spring-ai-session's default 60-day TTL is not applied) |

### State envelope

Planner state and attributes travel as a plain nested map — not a JSON string. Repositories that serialize (filesystem, JDBC) persist the envelope as JSON; when a session is loaded, `SessionStateStore` rehydrates the typed parts with the auto-configured Jackson 3 `ObjectMapper`: `planState` is converted from the map via `convertValue`, and known typed attributes are rebuilt as well (`multiIntentState` → `MultiIntentContext`, `originalIntent` → `IntentAnalysisResult`, `searchTree` → `DefaultSearchTree`). The in-memory repository keeps the live objects, so no rehydration happens there. Application code may store arbitrary Jackson-serializable values in attributes; after a JSON round-trip they come back as raw maps unless one of the rehydration rules above applies.

### Saving

`save()` is an upsert of the `Session` row/envelope plus an append of only the *new* events (the delta is computed against the repository's event count). Repeated saves of the same instance — or saves of clones produced by multi-intent dispatch — never duplicate history. `delete(sessionId)` removes the session and its events.

### Internal attribute keys

The framework writes the following keys into `session.attributes`:

| Key | Type | Description |
|---|---|---|
| `"originalIntent"` | `IntentAnalysisResult` | Cached intent from `analyze()`; preserved across planning iterations |
| `"pendingStep"` | `PlanStep` (serialized as Map) | Step paused awaiting confirmation |
| `"confirmationRequestedAt"` | `String` (LocalDateTime) | When confirmation was requested; used for timeout check |
| `"pendingModifiedParameters"` | `Map<String, Object>` | User-modified parameters from `ConfirmationResult` |
| `"multiIntentState"` | `MultiIntentContext` | Orchestration state during multi-intent processing |
| `"searchTree"` | `SearchTree` | LATS MCTS tree; persisted across planning iterations |
| `"thoughts"` | `List<String>` | REASON step contents (not written to message history) |

---

## Store comparison

| Store | Enabled by | Survives restart | Where data lives | Use case |
|---|---|---|---|---|
| In-memory | Default (no configuration) | No | JVM heap (`InMemorySessionRepository`) | Development, tests, stateless single instance |
| Filesystem | `intent-reactor.session.store: filesystem` | Yes | One JSON file per session under `intent-reactor.session.filesystem.path` | Simple persistence without a database |
| JDBC | Add `spring-ai-starter-session-jdbc` (spring-ai-session) | Yes | `AI_SESSION` / `AI_SESSION_EVENT` tables in a relational database | Production, multiple instances |

---

## Choosing a store

The backend is selected by `intent-reactor.session.store`:

```yaml
intent-reactor:
  session:
    store: filesystem      # in-memory (default) | filesystem
```

JDBC is **not** a property value — it is brought in by adding the spring-ai-session JDBC starter as a dependency (see [JDBC](#jdbc-spring-ai-session-add-on)). Auto-configuration registers exactly one fallback `SessionRepository`:

- `InMemorySessionRepository` (spring-ai-session) when `store=in-memory` — the default;
- `FileSystemSessionRepository` (IntentReactor) when `store=filesystem`.

Both beans are declared with `@ConditionalOnMissingBean(SessionRepository.class)`, so **any** external `SessionRepository` bean — your own or the one auto-configured by the spring-ai-session JDBC starter — automatically takes precedence over the fallbacks. This also means that if a JDBC repository is present and `store: filesystem` is set, the external repository still wins.

---

## In-memory (default)

No configuration needed — spring-ai-session's `InMemorySessionRepository` keeps everything in JVM memory, so sessions are lost on restart.

```yaml
intent-reactor:
  session:
    store: in-memory
```

---

## Filesystem

Sessions are stored as JSON files, one per session ID, in a configurable directory:

```yaml
intent-reactor:
  session:
    store: filesystem
    filesystem:
      path: ./sessions   # relative to the working directory; created automatically
```

File names follow the pattern `{sessionId}.json` inside that directory. Each file holds a small envelope: the session header (`id`, `userId`, `createdAt`, `expiresAt`, `metadata` — including the `com.intentreactor.state` envelope), the list of `SessionEvent`s (role, content, metadata), and a monotonically increasing version counter.

Writes are atomic — a temp file is written and moved over the destination (`Files.move` with `ATOMIC_MOVE`, falling back to `REPLACE_EXISTING` when the filesystem does not support atomic moves). Concurrent writes to the **same** session are serialized with per-session JVM locks. Files are serialized with the auto-configured Jackson 3 `ObjectMapper`.

**Legacy 0.1.x files are converted automatically.** A file written by 0.1.x (the whole serialized `SessionState` as top-level JSON) is detected on first read and rewritten in the current envelope format — no configuration or manual migration is needed for existing session files.

---

## JDBC (spring-ai-session add-on)

JDBC persistence is provided by the spring-ai-session ecosystem, not by an IntentReactor module:

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-starter-session-jdbc</artifactId>
    <version>0.8.0</version>
</dependency>
```

With a `DataSource` on the classpath, spring-ai-session auto-configures a `JdbcSessionRepository` bean which takes precedence over the built-in fallbacks. No `intent-reactor.session.*` property is needed — `store` can be left at its default.

Two tables are used:

- `AI_SESSION` — one row per session: `id`, `user_id`, `created_at`, `expires_at`, `metadata` (the JSON envelope incl. `com.intentreactor.state`), `event_version`.
- `AI_SESSION_EVENT` — the append-only history: `seq` (insertion order), `id`, `session_id`, `timestamp`, `message_type`, `message_content`, `message_data`, `synthetic`, `archived`, `branch`, `metadata`; foreign key to `AI_SESSION` with `ON DELETE CASCADE`.

**Schema initialization** is controlled by the spring-ai-session property `spring.ai.session.repository.jdbc.initialize-schema` (values: `embedded` — default, only embedded databases; `always`; `never`). When the property is left at its default and a schema-capable embedded database (H2) is used, the schema is created automatically by the starter. For external databases set `always`, or disable initialization with `never` and create the tables manually — the DDL ships with the `spring-ai-session-jdbc` artifact as `org/springframework/ai/session/jdbc/schema-{h2,mysql,postgresql}.sql`.

Supported databases: PostgreSQL, MySQL, MariaDB, and H2.

---

## Custom SessionRepository

Custom stores implement the spring-ai-session SPI directly — there is no IntentReactor storage interface anymore:

```java
public interface SessionRepository {    // org.springframework.ai.session

    Session save(Session session);

    Session findById(String sessionId);

    List<Session> findByUserId(String userId);

    List<String> findExpiredSessionIds(Instant before);

    void delete(String sessionId);

    void appendEvent(SessionEvent event);   // idempotent by event id

    boolean compactEvents(String sessionId, List<SessionEvent> archivedEvents,
                          List<SessionEvent> retainedEvents, long expectedVersion);

    long getEventVersion(String sessionId);

    List<SessionEvent> findEvents(String sessionId, EventFilter filter);
}
```

Declare your implementation as a regular bean — no `@Primary` and no property switch are required, because the built-in fallbacks are `@ConditionalOnMissingBean(SessionRepository.class)`:

```java
@Bean
public SessionRepository mySessionRepository() {
    return new MySessionRepository(...);
}
```

Two contract points matter for IntentReactor sessions:

- **Envelope round-trip.** Keep `Session.metadata` (under `com.intentreactor.state`) and the message events serializable as plain JSON; typed engine data is rehydrated automatically on load. An in-memory custom repository may keep the live objects as they are.
- **Event bookkeeping.** Persist events in append order, make `appendEvent` idempotent by event id, and report the current event count via `getEventVersion` — `SessionStateStore` relies on it to append only new events on every save.

---

## Migrating from 0.1.x

Version 0.2.0 replaced the self-hosted persistence layer with spring-ai-session. The change is breaking for persistence configuration:

| Was (0.1.x) | Now (0.2.0) | What to do |
|---|---|---|
| `intent-reactor-session-jdbc` module | Removed | Remove the dependency; optionally add `org.springaicommunity:spring-ai-starter-session-jdbc:0.8.0` instead |
| `intent-reactor-session-jpa` module | Removed | Use the filesystem store or the spring-ai-session JDBC starter instead |
| `api.SessionStore` interface | Deleted. The engine now uses the `SessionStateStore` facade; the extension point is the `SessionRepository` SPI | Custom stores must be reimplemented as `SessionRepository` beans |
| `store: jdbc` / `store: jpa` property values | Not supported. Without an external `SessionRepository` bean the context fails with `No qualifying bean of type 'SessionRepository'` | Delete the property; if you were on JDBC, add the spring-ai-session JDBC starter |
| `intent-reactor.session.jdbc.table-name` | Removed (the old JDBC implementation never read it) | Delete the property |
| `intent_reactor_sessions` table (JDBC/JPA) | Not read by new code, which uses `AI_SESSION` / `AI_SESSION_EVENT` | Migrate the data manually if you need to keep it; otherwise drop the table |
| Filesystem session files (whole `SessionState` JSON) | Converted to the envelope format automatically on first read | Nothing to do |

If you stayed on the default in-memory store, no action is required — sessions were ephemeral there anyway.

---

## LATS planner compatibility

The LATS planner keeps its Monte-Carlo search tree in `session.attributes["searchTree"]` between planning iterations (see [strategies/03-lats.md](strategies/03-lats.md)). Because attributes live in the state envelope, persistence behavior depends on the store:

- **In-memory** — the tree stays a live object across calls within the JVM, exactly as before.
- **Filesystem / JDBC (JSON-serializing repositories)** — the tree is stored as JSON inside the envelope and rehydrated into a typed `DefaultSearchTree` on every load through the framework's `ObjectMapper`. A partially explored tree therefore survives restarts and the planner resumes with it.

The same rehydration applies to the other typed pieces of the envelope: `planState`, `multiIntentState`, and `originalIntent`.
