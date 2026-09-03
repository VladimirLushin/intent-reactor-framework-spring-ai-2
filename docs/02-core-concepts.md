# Core Concepts

This page describes every interface in IntentReactor and how they relate to each other.

---

## Architecture overview

```
┌─────────────────────────────────────────────────────┐
│                IntentReactorService                  │
│                                                     │
│  process(sessionId, message)                        │
│      │                                              │
│      ├─► IntentPreprocessor.analyze()               │
│      │       └─► IntentAnalysisResult               │
│      │                                              │
│      ├─► SessionStateStore.findById() / save()      │
│      │                                              │
│      └─► [ReACT loop]                               │
│              │                                      │
│              ├─► Planner.plan(session, intent)      │
│              │       └─► Plan (list of PlanSteps)   │
│              │                                      │
│              └─► Tool.execute(ToolInput)            │
│                      └─► ToolResult                 │
└─────────────────────────────────────────────────────┘
```

Events are published at each stage. Micrometer metrics are recorded if `MeterRegistry` is on the classpath.

---

## IntentReactorService

The single entry point for all request processing.

```java
public interface IntentReactorService {

    // One-shot: temporary session, discarded after call
    ReactorResponse process(String message, Map<String, Object> context);

    // Dialog: session loaded/saved via SessionStateStore
    ReactorResponse process(String sessionId, String message);

    // Resume after risky-tool confirmation
    ReactorResponse proceedAfterConfirmation(String sessionId, ConfirmationResult confirmation);

    // Inspect full session state (messages, plan progress)
    SessionState getSessionState(String sessionId);
}
```

The default implementation `IntentReactorServiceImpl` is auto-registered by `IntentReactorAutoConfiguration`. Override it by declaring a `@Primary` bean.

---

## Tool

The primary extension point. Every `@Component` implementing `Tool` is auto-discovered.

```java
public interface Tool {
    String getName();                          // lowercase_underscore identifier
    String getDescription();                   // shown in LLM prompt — be precise
    Map<String, Object> getParameterSchema();  // JSON Schema "object"
    ToolResult execute(ToolInput input);       // must be thread-safe
    boolean isRisky();                         // true = pause for confirmation
    default boolean isGenerator() { return false; } // true = tool factory
}
```

**Naming convention** — use `lowercase_underscore` names (e.g., `order_lookup`, `send_email`). The name is embedded verbatim in every LLM prompt, so clarity matters.

**Thread safety** — `execute()` may be called concurrently from multiple sessions. Keep tools stateless or use thread-safe state.

---

## SimulatableTool

Extends `Tool` with a dry-run method used by the [LATS planner](strategies/03-lats.md) during Monte Carlo simulations.

```java
public interface SimulatableTool extends Tool {
    ToolResult simulate(ToolInput input); // side-effect-free simulation
}
```

If your tool performs network calls or writes data, implement `simulate()` to return realistic-looking data without side effects. If `SimulatableTool` is not implemented, LATS falls back to real execution (controlled by `planning.lats.allow-real-actions-in-simulation`).

---

## Planner

Called once per ReACT iteration. Must be stateless — all conversation context lives in `SessionState`.

```java
public interface Planner {
    Plan plan(SessionState sessionState, IntentAnalysisResult intent);
}
```

`plan()` returns a `Plan` containing an ordered list of `PlanStep` objects. Each step has a `StepType`:

| StepType | Meaning |
|---|---|
| `REASON` | Internal thought; stored in session attributes, not sent to LLM |
| `ACT` | Tool invocation; triggers `Tool.execute()` |
| `OBSERVE` | Observation appended to session history |
| `REFLECT` | Reflection text (used by Reflexion planner) |
| `DONE` | Terminal success; `description` becomes `finalText` |
| `FAIL` | Terminal failure; `description` becomes the error message |

To use a custom planner, declare it as a `@Primary` bean or set `intent-reactor.planning.strategy` to one of the 18 built-in values.

---

## IntentPreprocessor

Called **once per `process()` invocation** before the planning loop starts. Classifies the user message into structured intents.

```java
public interface IntentPreprocessor {
    IntentAnalysisResult analyze(String message, SessionState sessionState,
                                  Map<String, Object> context);
}
```

`IntentAnalysisResult` contains:
- `intents` — list of `Intent` (name, confidence 0–1, domain attributes)
- `entities` — list of `Entity` (type, value, metadata)
- `uncertain` — true if the LLM was not confident
- `reasoningSuggestion` — natural-language goal passed to the planner

Override by declaring a `@Primary` bean implementing this interface.

---

## Session persistence

The engine persists and retrieves `SessionState` objects through `SessionStateStore`, a facade living in `intent-reactor-core` (package `com.intentreactor.core.session`):

```java
public class SessionStateStore {
    Optional<SessionState> findById(String sessionId);
    void save(SessionState sessionState);
    void delete(String sessionId); // no-op if not found
}
```

`SessionStateStore` is an engine-internal facade, not an extension point. Actual storage is plugged in through `SessionRepository` — the persistence SPI of spring-ai-session (`org.springframework.ai.session.SessionRepository`, artifact `org.springaicommunity:spring-ai-session`). Three repositories can be used: the in-memory one (default), the filesystem one, and the spring-ai-session JDBC repository, added as an optional dependency. Any custom `SessionRepository` bean overrides the built-ins. See [05-session-stores.md](05-session-stores.md) for details.

---

## ToolProvider

Controls which tools are visible to the planner for a given session. Useful for role-based tool filtering.

```java
public interface ToolProvider {
    List<Tool> getAvailableTools(SessionState sessionState);
}
```

Default implementation (`DefaultToolProvider`) returns all discovered `Tool` beans that are not generators (`isGenerator() == false`). Provide a custom `@Bean` to filter:

```java
@Bean
@Primary
public ToolProvider roleAwareToolProvider(List<Tool> allTools) {
    return session -> {
        String role = (String) session.getAttributes().get("userRole");
        return allTools.stream()
            .filter(t -> !"admin_tool".equals(t.getName()) || "ADMIN".equals(role))
            .filter(t -> !t.isGenerator())
            .toList();
    };
}
```

---

## PromptContextProvider

Injects additional template variables into system prompts. Called once per `plan()` invocation.

```java
public interface PromptContextProvider {
    Map<String, Object> getAdditionalVariables(SessionState session);
}
```

Example — inject the current user's name into every system prompt:

```java
@Component
public class UserContextProvider implements PromptContextProvider {
    @Override
    public Map<String, Object> getAdditionalVariables(SessionState session) {
        String userId = (String) session.getAttributes().get("userId");
        return Map.of("userName", lookupName(userId));
    }
}
```

Then reference `{userName}` in your custom system prompt template.

---

## ConfirmationManager

Decides whether a tool needs confirmation and builds the request shown to the user.

```java
public interface ConfirmationManager {
    boolean needsConfirmation(Tool tool);
    ConfirmationRequest buildRequest(PlanStep step);
}
```

The default `DefaultConfirmationManager` returns `true` when `tool.isRisky() == true` and `autonomous=false`. Override to add custom logic (e.g., skip confirmation for certain users).

---

## Key data objects

| Object | Description |
|---|---|
| `ReactorResponse` | Returned by `process()`: `status`, `finalText`, `actions`, `reasoningSteps`, `sessionId`, `confirmationRequest` |
| `SessionState` | Full mutable state of a session: messages, planState, attributes |
| `PlanState` | Goal description, current status (`RUNNING`/`COMPLETED`/`FAILED`/`AWAITING_CONFIRMATION`), completed steps |
| `ToolInput` | Parameters map + `sessionId` passed to `Tool.execute()` |
| `ToolResult` | `ToolResult.ok(data)` or `ToolResult.error(message)` |
| `PerformedAction` | Tool name, parameters, result — collected per `process()` call |
| `ReasoningStep` | Type, description, timestamp — one per SYSTEM message in session history |
