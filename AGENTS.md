# AGENTS.md

IntentReactor: multi-module Maven (Java 21, bytecode target 21) framework/library that adds LLM intent analysis and ReACT-style planning to Spring Boot 4 / Spring AI 2.0 apps (`com.intentreactor:*` on Maven Central). Tested against Spring Boot 4.0.8 / Spring AI 2.0.1 / Jackson 3 (`tools.jackson`). The project is bilingual: README and user-facing text are mirrored in English (`README.md`, `docs/`) and Russian (`README-ru.md`, `docs-ru/`) — change both, plus the matching prompt-template pairs (below).

## Commands

- No Maven wrapper (`mvnw`) is checked in — system `mvn` + JDK 21 required. (This dev machine runs Temurin JDK 25 with Maven 3.9.16 installed under `~/tools` and `JAVA_HOME` set; the build targets bytecode 21, so a JDK 21+ is sufficient.)
- Full build + tests: `mvn test` from the root reactor.
- One module or one test: `mvn -pl <module> -am -Dtest=ClassName test` (`-am` builds inter-module deps; many modules depend on `intent-reactor-api`/`-core`). Without `-am`, a stale locally installed api/core jar may be used — ALWAYS pass `-am`.
- All tests are pure unit tests: the LLM is always a Mockito-mocked Spring AI `ChatClient`, tools/providers mocked — no API keys, no network. `@JdbcTest` slices use an embedded DB from the test classpath. The one exception is the opt-in live test `LiveLlmEndToEndIT` in core, which is excluded from `mvn test`/CI by its `*IT` suffix and additionally skips itself when `LLM_API_KEY` is unset:
  - Run: `$env:LLM_API_KEY='sk-...'; mvn -pl intent-reactor-core -am -Dtest=LiveLlmEndToEndIT -Dsurefire.failIfNoSpecifiedTests=false test`
- Release (CI-only): pushing a `v*` tag runs `.github/workflows/release.yml` — `versions:set` from the tag, `mvn test`, then `mvn clean deploy -Prelease` (flatten + sources + javadoc + GPG + Central publishing), then attaches all `target/*.jar` to a GitHub Release. Keep the version in the README dependency snippets in sync.

## Module map

- `intent-reactor-api` — public contracts only: `Tool`, `Planner`, `SessionStore`, `IntentPreprocessor`, DTOs, events (`com.intentreactor.api`). Deliberately no Spring Boot: only `spring-context` + Jackson. New public API belongs here; do not let it depend on other modules.
- `intent-reactor-core` — implementations + main auto-configuration: `IntentReactorAutoConfiguration`, `IntentReactorProperties` (prefix `intent-reactor`). Home of `DefaultReACTPlanner`, `ReflexionPlanner`, `LATSPlanner` (+ search trees in `planner/search/`), `IntentReactorServiceImpl`.
- `intent-reactor-spring-boot-starter` — thin aggregator: core + optional `mcp-client`/`mcp-server`.
- `intent-reactor-strategies` — extra planners; strategy is chosen purely by property `intent-reactor.planning.strategy`. Each bean in `StrategiesAutoConfiguration` carries `@ConditionalOnProperty(havingValue = "...")` and many wrap `DefaultReACTPlanner` as a delegate. To add a strategy: new `Planner` impl + conditional bean method + README/docs updates (EN + RU).
- Optional add-ons, each with its own auto-configuration: `session-jdbc`, `session-jpa` (`com.intentreactor.session.*`), `tool-commons` (`com.intentreactor.tools`), `tool-dynamic` (`com.intentreactor.tools.dynamic` — Rhino JS sandbox), `rag`, `mcp-client`, `mcp-server`, `sand-train` (exports SAND deliberation logs as JSONL; only meaningful with `strategy=sand`).

Package roots differ per module (`core.*`, `strategies.*`, `session.*`, `tools.*`, `sandtrain`) — match the module you edit, not a global scheme.

## Gotchas

- **Default prompts are Russian.** Every built-in prompt template exists in an EN/`-ru` pair (core: `intent-reactor-core/src/main/resources/prompts/`; strategies: `.../prompts/strategies/`). The code defaults in `IntentReactorProperties.PromptResources` point to the **`-ru`** files (e.g. `default-system-ru.md`), while most strategies defaults in `StrategiesProperties.PromptsConfig` point to English files (SAND defaults are `-ru`). Overrides: `intent-reactor.llm.prompt-resources.*` and `intent-reactor.planning.strategies.prompts.*`. When adding a template, add both language variants.
- **Prompt text is a parser contract.** `DefaultReACTPlanner` parses LLM output as JSON with keys `done`/`failed`/`toolName` (`LlmResponseParser`); tool results/errors are appended to history as SYSTEM messages prefixed `[TOOL_RESULT]`/`[TOOL_ERROR]`; there is a format-correction retry loop (`react-format-correction`). Keep markers/keys in sync when rewording templates.
- **Session state is Jackson 3-serialized** (`tools.jackson`: `SessionState`, messages, `LocalDateTime` fields). Jackson 3 databind handles `java.time` natively, so no `JavaTimeModule` registration is needed or done — stores serialize with a plain `tools.jackson` `ObjectMapper` (`JdbcSessionStore`, `JpaSessionStore`) or the auto-configured bean (`FileSystemSessionStore`).
- **Session attribute keys are split** across `api.SessionAttributeKeys` (shared/framework-level, e.g. `_pinNextUserMessage`), `core.service.CoreSessionKeys` (confirmation flow: `pendingStep`, `pendingModifiedParameters`, `confirmationRequestedAt`, `originalIntent`, `thoughts`), and `strategies.config.StrategySessionKeys` (per-strategy state). Keys shared across modules must live in `api`; the sand-train module reads the literal `"sand_training_log"` attribute written by `SANDPlanner`.
- `intent-reactor-ui-testing-*` entries in the parent `dependencyManagement` and some javadoc comments refer to a **private companion app**, not modules in this repo — leave them alone.
- Cross-module auto-config ordering precedent: `tool-dynamic` depends on core with `provided` scope and uses `@AutoConfigureBefore(IntentReactorAutoConfiguration.class)`.
- Javadoc comments referencing "CLAUDE.md" are stale; the configuration reference lives in README / `docs/13-configuration-reference.md`. Detailed per-topic guides: `docs/01..13` + `docs/strategies/` (SAND is implemented but not yet in README/docs strategy tables).
