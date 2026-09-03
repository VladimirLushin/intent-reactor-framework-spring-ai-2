# Design: Spring AI Tool Bridge (SA tools auto-available to IntentReactor planners)

Date: 2026-09-03
Status: Approved in brainstorming (sections 1–3); pending written review.

## Context / Problem

IntentReactor defines its own tool contract (`com.intentreactor.api.Tool`) and drives its
own planning loop: the LLM sees tools as a text catalogue inside the system prompt
(`LlmResponseParser.formatTools`) and answers with ReACT JSON (`toolName`/`parameters`);
the engine executes tools itself and feeds `[TOOL_RESULT]`/`[TOOL_ERROR]` history back.
Spring AI tool calling (`ToolCallback`, `ToolCallingAdvisor`, native model tool calls) is
never used inside planners.

Today, tools written in the Spring AI style (`@Tool` methods wrapped by
`MethodToolCallbackProvider`, `FunctionToolCallback`/`MethodToolCallback` beans, or any
`ToolCallback`/`ToolCallbackProvider` bean) cannot be used by IntentReactor planners
without being re-implemented as `com.intentreactor.api.Tool`.

Goal (user decision): **"write tools once in Spring AI style — IntentReactor picks them
up automatically."** No migration of the IR tool contract, no switch of the planning loop
to native function calling.

## Facts established during exploration

- Spring AI 2.0.1 registers tools centrally: `ToolCallingAutoConfiguration` (in
  `spring-ai-autoconfigure-model-tool`) builds a `ToolCallbackResolver` from
  (a) all `ToolCallback` beans and (b) all `ToolCallbackProvider` beans, **excluding**
  `SyncMcpToolCallbackProvider`/`AsyncMcpToolCallbackProvider` (type-name based, to avoid
  eager network listing of MCP servers).
- `@Tool` methods are NOT scanned at runtime in SA 2.0; the canonical authoring paths are
  `ToolCallback` beans, `ToolCallbackProvider` beans (e.g. `MethodToolCallbackProvider`
  over a `@Tool` class), or per-call `.tools(...)` on the user's own ChatClient.
- Adapters in both directions already exist inside the MCP add-on modules and are the
  proven template: `McpToolAdapter` (SA `ToolCallback` → IR `Tool`, mcp-client) and
  `ToolCallbackAdapter` (IR `Tool` → SA `ToolCallback`, mcp-server).
- IR's only entry point for the tool catalogue is the auto-configured `ToolProvider`
  bean (`IntentReactorAutoConfiguration#defaultToolProvider`, guarded by
  `@ConditionalOnMissingBean(ToolProvider.class)`). All planners and the executor consume
  `ToolProvider`, so augmenting that one bean makes SA tools visible everywhere without
  touching planners, prompts, parsing, session persistence, or the API module.
- LATS: tools that are not `SimulatableTool` already receive neutral reward 0.5 during
  search (`LATSPlanner#simulate`); real execution happens on the final path. SA-sourced
  tools simply do not implement `SimulatableTool` → existing behaviour applies, no change.

## Goals

- Any SA-style tool registered in the application context (`ToolCallback` bean or
  `ToolCallbackProvider` bean, incl. `MethodToolCallbackProvider` over `@Tool` classes)
  becomes available to IR planners and executor with zero IR-specific code.
- Keep `com.intentreactor.api.Tool` as the sole planner-facing contract; keep
  `intent-reactor-api` free of Spring AI dependencies.
- Provide IR-specific semantics for foreign tools through configuration only:
  risk/confirmation (`isRisky`), since SA has no risk concept.
- Full backward compatibility: IR-authored tools keep working unchanged.

## Non-goals

- Reverse bridge (IR tools as SA `ToolCallback`s outside the MCP server module) — out of
  scope; the mcp-server module already covers the MCP-export direction.
- Migrating the planning loop to native Spring AI function calling (tool calls instead of
  the textual ReACT JSON contract).
- Replacing the IR `Tool` contract with `org.springframework.ai.tool.*`.
- Runtime scanning of `@Tool`-annotated methods. The bridge mirrors SA's own discovery
  (beans only); apps register `@Tool` classes the canonical SA way (provider/beans).

## Design overview

A single bridge at the `ToolProvider` boundary inside `intent-reactor-core`:

1. `SpringAiToolsCollector` gathers the SA tool sources from the application context:
   `List<ToolCallback>` beans plus flattened `List<ToolCallbackProvider>` beans.
2. Providers excluded by fully-qualified type name (no compile dependency, mirroring SA's
   own approach):
   - `org.springframework.ai.mcp.SyncMcpToolCallbackProvider`
   - `org.springframework.ai.mcp.AsyncMcpToolCallbackProvider`
   - `com.intentreactor.mcp.server.adapter.IntentReactorToolCallbackProvider`
   - `com.intentreactor.mcp.server.planner.PlannerMcpCallbackProvider`

   Rationale: MCP providers would force network round-trips at startup and are already
   consumed by the mcp-client module's own `ToolProvider`; the mcp-server providers
   re-expose IR tools as SA callbacks and would otherwise create IR→SA→IR duplicates.
   Excluded providers are never invoked.
3. Each collected `ToolCallback` is wrapped in `SpringAiToolAdapter implements
   com.intentreactor.api.Tool`.
4. `IntentReactorAutoConfiguration#defaultToolProvider` composes the final list:
   IR `Tool` beans first (they win name conflicts), then bridged SA tools. The
   `@ConditionalOnMissingBean(ToolProvider.class)` bean stays singular, preserving the
   parse-order interplay with the mcp-client module's conditional provider.

No changes to: `intent-reactor-api`, planners, `LlmResponseParser`, executor
(`IntentReactorServiceImpl`), prompts, `SessionState`/persistence, mcp modules,
tool-commons, tool-dynamic, `DefaultToolProvider`.

## Adapter semantics (`SpringAiToolAdapter`)

- `getName()`/`getDescription()`/`getParameterSchema()` ← `ToolCallback.getToolDefinition()`
  (`name()`, `description()`, parsed `inputSchema()` JSON string; missing/blank schema →
  `{"type":"object","properties":{}}`).
- `execute(ToolInput)`:
  - serializes `ToolInput.parameters` (`Map`) to JSON;
  - calls `toolCallback.call(json, toolContext)` where `toolContext` carries
    `sessionId` and the session attributes map (SA tools that declare a `ToolContext`
    parameter receive them; tools without such a parameter are unaffected);
  - success → `ToolResult.ok(rawString)`; exception or `ERROR:`-prefixed string →
    `ToolResult.error(...)`. Result strings flow through the existing
    `[TOOL_RESULT]`/`[TOOL_ERROR]` history markers unchanged.
- `isRisky()` = `treatAllAsRisky || riskyToolNames.contains(getName())`.
- `isGenerator()` = `false`; does not implement `SimulatableTool`.

## Configuration surface

New standalone `@ConfigurationProperties(prefix = "intent-reactor.tools.spring-ai")`
class `SpringAiToolsProperties` (precedent: `McpClientProperties` in mcp-client),
registered via `@EnableConfigurationProperties` on the auto-configuration.

| Property | Default | Meaning |
|---|---|---|
| `intent-reactor.tools.spring-ai.enabled` | `true` | Bridge active. `false` → SA beans ignored (escape hatch for polluted contexts). Empty SA catalog is a no-op. |
| `intent-reactor.tools.spring-ai.risky-tool-names` | empty | Names of SA tools treated as risky → confirmation gate when `intent-reactor.planning.autonomous=false`. |
| `intent-reactor.tools.spring-ai.treat-all-as-risky` | `false` | Treat every SA-sourced tool as risky. |

The bridge only applies to the default `ToolProvider`. A user-defined `ToolProvider`
bean fully owns its list, as today. When the mcp-client module is present it registers
its own `ToolProvider` (`@AutoConfigureBefore(IntentReactorAutoConfiguration.class)` +
`@ConditionalOnMissingBean`); that provider receives IR `Tool` beans but not bridged SA
tools — documented behaviour.

## Name conflicts

- IR tool and SA tool with the same name → IR tool wins, SA tool skipped, WARN logged.
- Duplicate names within the SA sources → first wins, WARN logged.
- `ToolCallback`/`ToolDefinition` with null or blank name → skipped, WARN logged.

## Error handling

- A `ToolCallbackProvider.getToolCallbacks()` throwing → WARN, continue with remaining
  providers (pattern already used by `IntentReactorToolCallbackProvider`).
- Exceptions from `ToolCallback.call(...)` are caught by the adapter and mapped to
  `ToolResult.error(...)` (the IR executor's existing catch-all stays as second line of
  defence).

## Files changed (all in intent-reactor-core)

1. `src/main/java/com/intentreactor/core/tool/SpringAiToolAdapter.java` (new)
2. `src/main/java/com/intentreactor/core/tool/SpringAiToolsCollector.java` (new)
3. `src/main/java/com/intentreactor/core/config/SpringAiToolsProperties.java` (new)
4. `src/main/java/com/intentreactor/core/config/IntentReactorAutoConfiguration.java`
   (extend `defaultToolProvider(...)`; collect SA sources via optional `ObjectProvider`
   parameters so the bean works even if no SA tool classes/beans are present;
   `enabled=false` in `SpringAiToolsProperties` short-circuits collection)

Versioning: no version change; property and dependency snippets in README unaffected.

## Testing (pure unit, Mockito, no LLM — per AGENTS.md conventions)

- `SpringAiToolAdapterTest`: metadata mapping; success/error/exception paths of `call()`;
  sessionId + attributes present in `ToolContext`; `isRisky` from list and from
  treat-all flag; blank schema fallback.
- `SpringAiToolsCollectorTest`: collection from direct `ToolCallback` beans and from
  `ToolCallbackProvider` beans; MCP/mcp-server providers excluded by type name and never
  invoked; IR-tool-wins on conflict; dedup within SA sources; one failing provider does
  not break the rest; blank-name callbacks skipped.
- Existing context test of `IntentReactorAutoConfiguration` extended/verified: default
  `ToolProvider` returns IR + bridged union when SA beans are present.
- Verification: `mvn test` from the reactor root; targeted runs via
  `mvn -pl intent-reactor-core -am -Dtest=<Class> test`.

## Documentation (EN + RU, mandatory mirroring)

- `README.md` / `README-ru.md`: tools section — "Spring AI tools are picked up
  automatically" authoring guidance (`@Tool` + `MethodToolCallbackProvider`,
  `ToolCallback` beans), risk configuration pointer, mcp-client interplay note.
- `docs/13-configuration-reference.md` / `docs-ru/`: new properties with defaults.

## Risks / notes

- Users who register `ToolCallbackProvider` beans that are expensive to list at startup
  (beyond MCP providers) will see that cost at context startup; the exclusion list can be
  extended later if needed.
- `ERROR:`-prefix convention comes from the existing `ToolCallbackAdapter`; kept for
  symmetry. Adapter normalizes only actual exceptions; error strings from SA tools are
  surfaced as-is (may appear twice when the SA tool already returned `ERROR: ...` — it is
  still recorded as `[TOOL_ERROR]` only once by the executor).
