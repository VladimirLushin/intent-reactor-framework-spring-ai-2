# Spring AI Tool Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Spring AI tools (`ToolCallback` beans / `ToolCallbackProvider` beans) are automatically discovered and exposed to IntentReactor planners and executor, with configurable risk semantics — no changes to the IR `Tool` contract or planners.

**Architecture:** A collector inside `intent-reactor-core` reads the application context the same way Spring AI's own `ToolCallingAutoConfiguration` does (all `ToolCallback` beans + flattened `ToolCallbackProvider` beans), excludes MCP/mcp-server provider types **by class name before instantiation** (cycle-free: `IntentReactorToolCallbackProvider` needs the `ToolProvider` bean we are building), wraps each callback in a new `SpringAiToolAdapter implements com.intentreactor.api.Tool`, and feeds the merged list (IR tools win name conflicts) into the existing default `ToolProvider` bean.

**Tech Stack:** Java 21, Spring Boot 4 / Spring AI 2.0.1 (`org.springframework.ai.tool.*`, `org.springframework.ai.chat.model.ToolContext`), Jackson 3 (`tools.jackson.*`), Lombok (properties classes), JUnit 5 + Mockito + AssertJ, Spring Boot `ApplicationContextRunner`.

**Spec:** `docs/superpowers/specs/2026-09-03-spring-ai-tool-bridge-design.md`

## Global Constraints

- Java 21, bytecode target 21; build with system `mvn` (no wrapper) from the reactor root.
- Jackson 3 is `tools.jackson.*` — never `com.fasterxml.*`.
- `intent-reactor-api` must stay free of Spring AI dependencies — no API changes in this plan.
- No new runtime/compile dependencies in `intent-reactor-core`; mcp artifacts are referenced only as string type names (no compile dep on `spring-ai-mcp` or mcp modules).
- Tests are pure unit tests: mocked Spring AI objects, no LLM, no network, no API keys.
- Property prefix is `intent-reactor.tools.spring-ai` (nested `SpringAiToolsConfig` inside `IntentReactorProperties.ToolsConfig`, matching core convention).
- Excluded provider type names (never instantiated, decided via `beanFactory.getType(beanName)`):
  `org.springframework.ai.mcp.SyncMcpToolCallbackProvider`,
  `org.springframework.ai.mcp.AsyncMcpToolCallbackProvider`,
  `com.intentreactor.mcp.server.adapter.IntentReactorToolCallbackProvider`,
  `com.intentreactor.mcp.server.planner.PlannerMcpCallbackProvider`.
- Documentation changes must land in BOTH English and Russian mirrors: `README.md` + `README-ru.md`, `docs/13-configuration-reference.md` + `docs-ru/13-configuration-reference.md`.
- Doc files in `docs/13*` and `docs-ru/13*` are UTF-8; PowerShell console may garble Cyrillic output — use the read/edit tools, not console output, for content decisions.
- Do not touch: planners, `LlmResponseParser`, `IntentReactorServiceImpl`, prompts, session layer, mcp modules, tool-commons, tool-dynamic, `DefaultToolProvider`.
- Commit at the end of every task with the exact command given in the task.

---

### Task 1: Config section for the Spring AI bridge

**Files:**
- Modify: `intent-reactor-core/src/main/java/com/intentreactor/core/config/IntentReactorProperties.java`
- Test: `intent-reactor-core/src/test/java/com/intentreactor/core/config/IntentReactorPropertiesTest.java`

**Interfaces:**
- Produces: nested class `IntentReactorProperties.ToolsConfig.SpringAiToolsConfig` with Lombok getters/setters:
  - `boolean enabled` (default `true`)
  - `java.util.Set<String> riskyToolNames` (default empty set)
  - `boolean treatAllAsRisky` (default `false`)
- Produces: `ToolsConfig` gains field `private SpringAiToolsConfig springAi = new SpringAiToolsConfig();` (getter `getSpringAi()` via Lombok).

- [ ] **Step 1: Write the failing tests**

Append to `IntentReactorPropertiesTest.java` (keep existing tests). The file already imports `org.junit.jupiter.api.Test`, `ApplicationContextRunner`, `assertThat`; add imports:

```java
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
```

New methods inside the existing test class:

```java
    @Test
    void springAiToolsConfig_defaults() {
        IntentReactorProperties props = new IntentReactorProperties();
        assertThat(props.getTools().getSpringAi().isEnabled()).isTrue();
        assertThat(props.getTools().getSpringAi().getRiskyToolNames()).isEmpty();
        assertThat(props.getTools().getSpringAi().isTreatAllAsRisky()).isFalse();
    }

    @Test
    void springAiToolsConfig_bindsFromProperties() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropsBindingConfig.class)
                .withPropertyValues(
                        "intent-reactor.tools.spring-ai.enabled=false",
                        "intent-reactor.tools.spring-ai.treat-all-as-risky=true",
                        "intent-reactor.tools.spring-ai.risky-tool-names=send_email,delete_order")
                .run(ctx -> {
                    IntentReactorProperties props = ctx.getBean(IntentReactorProperties.class);
                    assertThat(props.getTools().getSpringAi().isEnabled()).isFalse();
                    assertThat(props.getTools().getSpringAi().isTreatAllAsRisky()).isTrue();
                    assertThat(props.getTools().getSpringAi().getRiskyToolNames())
                            .containsExactlyInAnyOrder("send_email", "delete_order");
                });
    }
```

Add a nested config class inside `IntentReactorPropertiesTest` (alongside the existing `LoggingAutoConfig`):

```java
    @Configuration
    @EnableConfigurationProperties(IntentReactorProperties.class)
    static class PropsBindingConfig {
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -pl intent-reactor-core -am -Dtest=IntentReactorPropertiesTest test`
Expected: FAIL — compilation error (`getSpringAi()` does not exist).

- [ ] **Step 3: Implement the config section**

In `IntentReactorProperties.java`:
- Add imports `import java.util.HashSet;` and `import java.util.Set;`.
- In the field block (around line 21) nothing changes; `ToolsConfig` is already a field.
- Replace the `ToolsConfig` class body:

```java
    @Getter
    @Setter
    public static class ToolsConfig {
        private String scanPackages = "";
        private SpringAiToolsConfig springAi = new SpringAiToolsConfig();
    }

    /**
     * Bridge that exposes Spring AI {@code ToolCallback}/{@code ToolCallbackProvider}
     * beans to IntentReactor planners as {@code Tool}s.
     */
    @Getter
    @Setter
    public static class SpringAiToolsConfig {
        /**
         * Collect Spring AI tool callbacks into the default ToolProvider.
         */
        private boolean enabled = true;

        /**
         * Spring AI tool names always treated as risky (confirmation gate when
         * intent-reactor.planning.autonomous=false).
         */
        private Set<String> riskyToolNames = new HashSet<>();

        /**
         * Treat every Spring AI-sourced tool as risky.
         */
        private boolean treatAllAsRisky = false;
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -pl intent-reactor-core -am -Dtest=IntentReactorPropertiesTest test`
Expected: PASS (all tests incl. the pre-existing ones).

- [ ] **Step 5: Commit**

```bash
git add intent-reactor-core/src/main/java/com/intentreactor/core/config/IntentReactorProperties.java intent-reactor-core/src/test/java/com/intentreactor/core/config/IntentReactorPropertiesTest.java
git commit -m "Add spring-ai tool bridge config section to IntentReactorProperties"
```

---

### Task 2: `SpringAiToolAdapter` (SA `ToolCallback` → IR `Tool`)

**Files:**
- Create: `intent-reactor-core/src/main/java/com/intentreactor/core/tool/SpringAiToolAdapter.java`
- Test: `intent-reactor-core/src/test/java/com/intentreactor/core/tool/SpringAiToolAdapterTest.java`

**Interfaces:**
- Consumes: `IntentReactorProperties.ToolsConfig.SpringAiToolsConfig` (Task 1).
- Produces: `public class SpringAiToolAdapter implements com.intentreactor.api.Tool`
  - constructor `SpringAiToolAdapter(org.springframework.ai.tool.ToolCallback delegate, tools.jackson.databind.ObjectMapper objectMapper, IntentReactorProperties.SpringAiToolsConfig config)`
  - semantics: `getName()`/`getDescription()`/`getParameterSchema()` from `delegate.getToolDefinition()`; `isRisky()` = `config.isTreatAllAsRisky() || config.getRiskyToolNames().contains(getName())`; `isGenerator()` = `false`; does NOT implement `SimulatableTool`; `execute(ToolInput)` serializes params to JSON and calls `delegate.call(json, toolContext)` where `toolContext = new org.springframework.ai.chat.model.ToolContext(map)` with `map.get("sessionId")` when non-null; thrown exceptions → `ToolResult.error("Spring AI tool execution failed: " + e.getMessage())`; blank/null `inputSchema` → empty parameter map.

- [ ] **Step 1: Write the failing test**

Create `SpringAiToolAdapterTest.java`:

```java
package com.intentreactor.core.tool;

import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import com.intentreactor.core.config.IntentReactorProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiToolAdapterTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final String SCHEMA = """
            {"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}
            """;

    private static ToolCallback callback(String name) {
        ToolCallback cb = mock(ToolCallback.class);
        when(cb.getToolDefinition()).thenReturn(DefaultToolDefinition.builder()
                .name(name)
                .description("Description of " + name)
                .inputSchema(SCHEMA)
                .build());
        return cb;
    }

    @Test
    void exposesDefinitionMetadata() {
        SpringAiToolAdapter adapter = new SpringAiToolAdapter(
                callback("get_weather"), MAPPER, new IntentReactorProperties.SpringAiToolsConfig());

        assertThat(adapter.getName()).isEqualTo("get_weather");
        assertThat(adapter.getDescription()).isEqualTo("Description of get_weather");
        assertThat(adapter.getParameterSchema()).containsKeys("type", "properties", "required");
        assertThat(adapter.isGenerator()).isFalse();
    }

    @Test
    void executeSerializesParametersAndReturnsOk() throws Exception {
        ToolCallback delegate = callback("get_weather");
        when(delegate.call(anyString(), any(ToolContext.class))).thenReturn("sunny");
        SpringAiToolAdapter adapter = new SpringAiToolAdapter(
                delegate, MAPPER, new IntentReactorProperties.SpringAiToolsConfig());

        ToolResult result = adapter.execute(new ToolInput(Map.of("city", "Berlin"), "s1"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo("sunny");
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(delegate).call(jsonCaptor.capture(), any(ToolContext.class));
        assertThat(jsonCaptor.getValue()).contains("\"city\":\"Berlin\"");
    }

    @Test
    void executePassesSessionIdViaToolContext() {
        ToolCallback delegate = callback("get_weather");
        when(delegate.call(anyString(), any(ToolContext.class))).thenReturn("x");
        SpringAiToolAdapter adapter = new SpringAiToolAdapter(
                delegate, MAPPER, new IntentReactorProperties.SpringAiToolsConfig());

        adapter.execute(new ToolInput(Map.of(), "session-42"));

        ArgumentCaptor<ToolContext> ctxCaptor = ArgumentCaptor.forClass(ToolContext.class);
        verify(delegate).call(anyString(), ctxCaptor.capture());
        assertThat(ctxCaptor.getValue().getContext()).containsEntry("sessionId", "session-42");
    }

    @Test
    void executeSerializesEmptyObjectWhenParametersNull() throws Exception {
        ToolCallback delegate = callback("get_weather");
        when(delegate.call(anyString(), any(ToolContext.class))).thenReturn("ok");
        SpringAiToolAdapter adapter = new SpringAiToolAdapter(
                delegate, MAPPER, new IntentReactorProperties.SpringAiToolsConfig());

        adapter.execute(new ToolInput(null, null));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(delegate).call(jsonCaptor.capture(), any(ToolContext.class));
        assertThat(jsonCaptor.getValue()).isEqualTo("{}");
    }

    @Test
    void executeMapsThrownExceptionToErrorResult() {
        ToolCallback delegate = callback("get_weather");
        when(delegate.call(anyString(), any(ToolContext.class)))
                .thenThrow(new RuntimeException("boom"));
        SpringAiToolAdapter adapter = new SpringAiToolAdapter(
                delegate, MAPPER, new IntentReactorProperties.SpringAiToolsConfig());

        ToolResult result = adapter.execute(new ToolInput(Map.of(), "s1"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("boom");
    }

    @Test
    void isRiskyFromRiskyToolNames() {
        IntentReactorProperties.SpringAiToolsConfig config =
                new IntentReactorProperties.SpringAiToolsConfig();
        config.setRiskyToolNames(Set.of("send_email"));
        SpringAiToolAdapter safe = new SpringAiToolAdapter(
                callback("get_weather"), MAPPER, config);
        SpringAiToolAdapter dangerous = new SpringAiToolAdapter(
                callback("send_email"), MAPPER, config);

        assertThat(safe.isRisky()).isFalse();
        assertThat(dangerous.isRisky()).isTrue();
    }

    @Test
    void isRiskyWhenTreatAllAsRisky() {
        IntentReactorProperties.SpringAiToolsConfig config =
                new IntentReactorProperties.SpringAiToolsConfig();
        config.setTreatAllAsRisky(true);
        SpringAiToolAdapter adapter = new SpringAiToolAdapter(
                callback("get_weather"), MAPPER, config);

        assertThat(adapter.isRisky()).isTrue();
    }

    @Test
    void blankSchemaFallsBackToEmptyParameterMap() {
        ToolCallback cb = mock(ToolCallback.class);
        when(cb.getToolDefinition()).thenReturn(DefaultToolDefinition.builder()
                .name("odd_tool")
                .description("no schema")
                .inputSchema("  ")
                .build());
        SpringAiToolAdapter adapter = new SpringAiToolAdapter(
                cb, MAPPER, new IntentReactorProperties.SpringAiToolsConfig());

        assertThat(adapter.getParameterSchema()).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl intent-reactor-core -am -Dtest=SpringAiToolAdapterTest test`
Expected: FAIL — compilation error (`SpringAiToolAdapter` does not exist).

- [ ] **Step 3: Implement the adapter**

Create `SpringAiToolAdapter.java`:

```java
package com.intentreactor.core.tool;

import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import com.intentreactor.core.config.IntentReactorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Adapts a Spring AI {@link ToolCallback} (registered as a bean or returned by a
 * {@code ToolCallbackProvider} bean) to the IntentReactor {@link Tool} interface so that
 * Spring AI-authored tools become available to IntentReactor planners.
 *
 * <p>IntentReactor-specific semantics that Spring AI does not model are derived from
 * configuration: {@link #isRisky()} uses
 * {@code intent-reactor.tools.spring-ai.risky-tool-names} /
 * {@code intent-reactor.tools.spring-ai.treat-all-as-risky}. The adapter is never a
 * generator and is not simulatable (LATS gives it a neutral reward during search).
 */
public class SpringAiToolAdapter implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SpringAiToolAdapter.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ToolCallback delegate;
    private final ObjectMapper objectMapper;
    private final IntentReactorProperties.SpringAiToolsConfig config;
    private final ToolDefinition definition;
    private final Map<String, Object> parameterSchema;

    public SpringAiToolAdapter(ToolCallback delegate,
                               ObjectMapper objectMapper,
                               IntentReactorProperties.SpringAiToolsConfig config) {
        this.delegate = delegate;
        this.objectMapper = objectMapper;
        this.config = config;
        this.definition = delegate.getToolDefinition();
        this.parameterSchema = parseSchema(definition.inputSchema());
    }

    @Override
    public String getName() {
        return definition.name();
    }

    @Override
    public String getDescription() {
        return definition.description();
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return parameterSchema;
    }

    @Override
    public ToolResult execute(ToolInput input) {
        try {
            String json = objectMapper.writeValueAsString(
                    input.getParameters() != null ? input.getParameters() : Collections.emptyMap());
            String result = delegate.call(json, buildToolContext(input.getSessionId()));
            return ToolResult.ok(result);
        } catch (Exception e) {
            log.warn("Spring AI tool '{}' threw exception", getName(), e);
            return ToolResult.error("Spring AI tool execution failed: " + e.getMessage());
        }
    }

    @Override
    public boolean isRisky() {
        return config.isTreatAllAsRisky() || config.getRiskyToolNames().contains(getName());
    }

    @Override
    public boolean isGenerator() {
        return false;
    }

    private ToolContext buildToolContext(String sessionId) {
        Map<String, Object> context = new HashMap<>();
        if (sessionId != null) {
            context.put("sessionId", sessionId);
        }
        return new ToolContext(context);
    }

    private Map<String, Object> parseSchema(String inputSchema) {
        if (inputSchema == null || inputSchema.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(inputSchema, MAP_TYPE);
        } catch (Exception e) {
            log.warn("Could not parse input schema of Spring AI tool '{}': {}", getName(), e.getMessage());
            return Collections.emptyMap();
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl intent-reactor-core -am -Dtest=SpringAiToolAdapterTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add intent-reactor-core/src/main/java/com/intentreactor/core/tool/SpringAiToolAdapter.java intent-reactor-core/src/test/java/com/intentreactor/core/tool/SpringAiToolAdapterTest.java
git commit -m "Add SpringAiToolAdapter bridging Spring AI ToolCallbacks into IR tools"
```

---

### Task 3: `SpringAiToolsCollector` (context scan, exclusion, merge)

**Files:**
- Create: `intent-reactor-core/src/main/java/com/intentreactor/core/tool/SpringAiToolsCollector.java`
- Test: `intent-reactor-core/src/test/java/com/intentreactor/core/tool/SpringAiToolsCollectorTest.java`

**Interfaces:**
- Consumes: `SpringAiToolAdapter` (Task 2), `IntentReactorProperties.SpringAiToolsConfig` (Task 1).
- Produces:
  - `public class SpringAiToolsCollector`
  - `public static final List<String> DEFAULT_EXCLUDED_PROVIDER_TYPES` (the 4 FQNs from Global Constraints)
  - no-arg constructor; constructor `SpringAiToolsCollector(Set<String> excludedProviderTypeNames)`
  - `public List<Tool> collectSpringAiTools(org.springframework.beans.factory.ListableBeanFactory beanFactory, ObjectMapper objectMapper, IntentReactorProperties.SpringAiToolsConfig config)`
  - `public List<Tool> mergeWithIrTools(List<Tool> irTools, List<Tool> saTools)` — IR-first, conflicts resolved to IR tool, WARN per skipped SA tool.

- [ ] **Step 1: Write the failing test**

Create `SpringAiToolsCollectorTest.java`:

```java
package com.intentreactor.core.tool;

import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import com.intentreactor.core.config.IntentReactorProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpringAiToolsCollectorTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final IntentReactorProperties.SpringAiToolsConfig CFG =
            new IntentReactorProperties.SpringAiToolsConfig();
    private static final String SCHEMA = """
            {"type":"object","properties":{}}
            """;

    private static ToolCallback callback(String name) {
        ToolCallback cb = mock(ToolCallback.class);
        when(cb.getToolDefinition()).thenReturn(DefaultToolDefinition.builder()
                .name(name)
                .description("Description of " + name)
                .inputSchema(SCHEMA)
                .build());
        return cb;
    }

    private static class StaticProvider implements ToolCallbackProvider {
        private final ToolCallback[] callbacks;

        StaticProvider(ToolCallback... callbacks) {
            this.callbacks = callbacks;
        }

        @Override
        public ToolCallback[] getToolCallbacks() {
            return callbacks;
        }
    }

    /** Must never be invoked by the collector. */
    private static class ExcludedProvider implements ToolCallbackProvider {
        @Override
        public ToolCallback[] getToolCallbacks() {
            throw new IllegalStateException("Excluded provider must not be invoked");
        }
    }

    /** Provider that explodes on listing — collection must survive it. */
    private static class FailingProvider implements ToolCallbackProvider {
        @Override
        public ToolCallback[] getToolCallbacks() {
            throw new IllegalStateException("list failed");
        }
    }

    private static class IrTool implements Tool {
        private final String name;

        IrTool(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "IR tool " + name;
        }

        @Override
        public Map<String, Object> getParameterSchema() {
            return Map.of();
        }

        @Override
        public ToolResult execute(ToolInput input) {
            return ToolResult.ok(name);
        }

        @Override
        public boolean isRisky() {
            return false;
        }
    }

    @Test
    void defaultExcludedTypesAreTheMcpAndMcpServerProviders() {
        assertThat(SpringAiToolsCollector.DEFAULT_EXCLUDED_PROVIDER_TYPES).containsExactlyInAnyOrder(
                "org.springframework.ai.mcp.SyncMcpToolCallbackProvider",
                "org.springframework.ai.mcp.AsyncMcpToolCallbackProvider",
                "com.intentreactor.mcp.server.adapter.IntentReactorToolCallbackProvider",
                "com.intentreactor.mcp.server.planner.PlannerMcpCallbackProvider");
    }

    @Test
    void collectsDirectToolCallbackBeansAndFlattenedProviderBeans() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("directCb", callback("direct_tool"));
        bf.registerSingleton("provider", new StaticProvider(callback("provider_tool")));

        List<Tool> tools = new SpringAiToolsCollector()
                .collectSpringAiTools(bf, MAPPER, CFG);

        assertThat(tools).extracting(Tool::getName)
                .containsExactlyInAnyOrder("direct_tool", "provider_tool");
    }

    @Test
    void excludedProviderIsNeverInstantiatedOrInvoked() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("excluded", new ExcludedProvider());

        List<Tool> tools = new SpringAiToolsCollector(Set.of(ExcludedProvider.class.getName()))
                .collectSpringAiTools(bf, MAPPER, CFG);

        assertThat(tools).isEmpty();
    }

    @Test
    void failingProviderDoesNotBreakCollectionOfOthers() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("failing", new FailingProvider());
        bf.registerSingleton("good", callback("good_tool"));

        List<Tool> tools = new SpringAiToolsCollector()
                .collectSpringAiTools(bf, MAPPER, CFG);

        assertThat(tools).extracting(Tool::getName).containsExactly("good_tool");
    }

    @Test
    void duplicateNamesWithinSpringAiSourcesAreDeduplicated() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("cb1", callback("dup_tool"));
        bf.registerSingleton("provider", new StaticProvider(callback("dup_tool")));

        List<Tool> tools = new SpringAiToolsCollector()
                .collectSpringAiTools(bf, MAPPER, CFG);

        assertThat(tools).extracting(Tool::getName).containsExactly("dup_tool");
    }

    @Test
    void blankNamedCallbackIsSkipped() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("blank", callback("  "));
        bf.registerSingleton("good", callback("good_tool"));

        List<Tool> tools = new SpringAiToolsCollector()
                .collectSpringAiTools(bf, MAPPER, CFG);

        assertThat(tools).extracting(Tool::getName).containsExactly("good_tool");
    }

    @Test
    void mergeWithIrToolsKeepsIrOrderAndIrWinsConflicts() {
        List<Tool> saTools = List.of(new IrTool("shared"), new IrTool("sa_only"));
        List<Tool> irTools = List.of(new IrTool("shared"), new IrTool("ir_only"));

        List<Tool> merged = new SpringAiToolsCollector().mergeWithIrTools(irTools, saTools);

        assertThat(merged).extracting(Tool::getName)
                .containsExactly("shared", "ir_only", "sa_only");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl intent-reactor-core -am -Dtest=SpringAiToolsCollectorTest test`
Expected: FAIL — compilation error (`SpringAiToolsCollector` does not exist).

- [ ] **Step 3: Implement the collector**

Create `SpringAiToolsCollector.java`:

```java
package com.intentreactor.core.tool;

import com.intentreactor.api.Tool;
import com.intentreactor.core.config.IntentReactorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ListableBeanFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Discovers Spring AI tools ({@code ToolCallback} beans and {@code ToolCallbackProvider}
 * beans) in the application context and adapts them to IntentReactor {@link Tool}s.
 *
 * <p>The scan mirrors Spring AI's own {@code ToolCallingAutoConfiguration} behaviour.
 * Providers whose type name is in {@link #DEFAULT_EXCLUDED_PROVIDER_TYPES} are skipped
 * based on {@code beanFactory.getType(beanName)} and are never instantiated — this keeps
 * MCP providers from doing network round-trips at startup and breaks the dependency cycle
 * with the mcp-server module's {@code IntentReactorToolCallbackProvider}, whose
 * constructor requires the very {@code ToolProvider} this collector feeds.
 */
public class SpringAiToolsCollector {

    private static final Logger log = LoggerFactory.getLogger(SpringAiToolsCollector.class);

    public static final List<String> DEFAULT_EXCLUDED_PROVIDER_TYPES = List.of(
            "org.springframework.ai.mcp.SyncMcpToolCallbackProvider",
            "org.springframework.ai.mcp.AsyncMcpToolCallbackProvider",
            "com.intentreactor.mcp.server.adapter.IntentReactorToolCallbackProvider",
            "com.intentreactor.mcp.server.planner.PlannerMcpCallbackProvider");

    private final Set<String> excludedProviderTypeNames;

    public SpringAiToolsCollector() {
        this(new HashSet<>(DEFAULT_EXCLUDED_PROVIDER_TYPES));
    }

    public SpringAiToolsCollector(Set<String> excludedProviderTypeNames) {
        this.excludedProviderTypeNames = new HashSet<>(excludedProviderTypeNames);
    }

    /**
     * Collects all Spring AI tools currently registered in the bean factory.
     */
    public List<Tool> collectSpringAiTools(ListableBeanFactory beanFactory,
                                           ObjectMapper objectMapper,
                                           IntentReactorProperties.SpringAiToolsConfig config) {
        List<Tool> tools = new ArrayList<>();
        Set<String> seenNames = new LinkedHashSet<>();

        for (String beanName : beanFactory.getBeanNamesForType(ToolCallbackProvider.class)) {
            if (isExcludedProvider(beanFactory, beanName)) {
                log.debug("Skipping excluded ToolCallbackProvider bean '{}'", beanName);
                continue;
            }
            ToolCallbackProvider provider;
            try {
                provider = beanFactory.getBean(beanName, ToolCallbackProvider.class);
            } catch (BeansException e) {
                log.warn("Could not obtain ToolCallbackProvider bean '{}': {}", beanName, e.getMessage());
                continue;
            }
            ToolCallback[] callbacks;
            try {
                callbacks = provider.getToolCallbacks();
            } catch (Exception e) {
                log.warn("ToolCallbackProvider '{}' failed to list tools: {}", beanName, e.getMessage());
                continue;
            }
            addCallbacks(tools, seenNames, callbacks, objectMapper, config);
        }

        for (String beanName : beanFactory.getBeanNamesForType(ToolCallback.class)) {
            try {
                addCallbacks(tools, seenNames,
                        new ToolCallback[]{beanFactory.getBean(beanName, ToolCallback.class)},
                        objectMapper, config);
            } catch (BeansException e) {
                log.warn("Could not obtain ToolCallback bean '{}': {}", beanName, e.getMessage());
            }
        }

        return tools;
    }

    /**
     * Merges native IR tools with bridged Spring AI tools. IR tools keep their order and
     * win name conflicts; conflicting Spring AI tools are skipped with a warning.
     */
    public List<Tool> mergeWithIrTools(List<Tool> irTools, List<Tool> saTools) {
        List<Tool> merged = new ArrayList<>(irTools.size() + saTools.size());
        Set<String> takenNames = new HashSet<>();
        for (Tool tool : irTools) {
            if (takenNames.add(tool.getName())) {
                merged.add(tool);
            }
        }
        for (Tool tool : saTools) {
            if (takenNames.add(tool.getName())) {
                merged.add(tool);
            } else {
                log.warn("Skipping Spring AI tool '{}': name conflicts with an IntentReactor tool",
                        tool.getName());
            }
        }
        return merged;
    }

    private boolean isExcludedProvider(ListableBeanFactory beanFactory, String beanName) {
        Class<?> type = beanFactory.getType(beanName);
        return type != null && excludedProviderTypeNames.contains(type.getName());
    }

    private void addCallbacks(List<Tool> tools, Set<String> seenNames, ToolCallback[] callbacks,
                              ObjectMapper objectMapper,
                              IntentReactorProperties.SpringAiToolsConfig config) {
        if (callbacks == null) {
            return;
        }
        for (ToolCallback callback : callbacks) {
            if (callback == null || callback.getToolDefinition() == null) {
                log.warn("Skipping null Spring AI tool callback or definition");
                continue;
            }
            String name = callback.getToolDefinition().name();
            if (name == null || name.isBlank()) {
                log.warn("Skipping Spring AI tool with blank name");
                continue;
            }
            if (!seenNames.add(name)) {
                log.warn("Skipping duplicate Spring AI tool '{}'", name);
                continue;
            }
            tools.add(new SpringAiToolAdapter(callback, objectMapper, config));
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl intent-reactor-core -am -Dtest=SpringAiToolsCollectorTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add intent-reactor-core/src/main/java/com/intentreactor/core/tool/SpringAiToolsCollector.java intent-reactor-core/src/test/java/com/intentreactor/core/tool/SpringAiToolsCollectorTest.java
git commit -m "Add SpringAiToolsCollector scanning SA tool beans into IR tools"
```

---

### Task 4: Wire the bridge into `defaultToolProvider`

**Files:**
- Modify: `intent-reactor-core/src/main/java/com/intentreactor/core/config/IntentReactorAutoConfiguration.java` (bean method `defaultToolProvider`, lines 237-241)
- Test: `intent-reactor-core/src/test/java/com/intentreactor/core/config/SpringAiToolBridgeAutoConfigurationTest.java`

**Interfaces:**
- Consumes: `SpringAiToolsCollector` (Task 3), `IntentReactorProperties.getTools().getSpringAi()` (Task 1).
- Produces: `defaultToolProvider` now also takes `IntentReactorProperties properties`, `ObjectMapper objectMapper`, `org.springframework.beans.factory.ListableBeanFactory beanFactory`; when `enabled=false` returns `new DefaultToolProvider(tools)` unchanged; otherwise returns `DefaultToolProvider` over `collector.mergeWithIrTools(tools, collector.collectSpringAiTools(beanFactory, objectMapper, config))`.

- [ ] **Step 1: Write the failing test**

Create `SpringAiToolBridgeAutoConfigurationTest.java`:

```java
package com.intentreactor.core.config;

import com.intentreactor.api.SessionState;
import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolProvider;
import com.intentreactor.api.ToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the Spring AI tool bridge wiring in {@link IntentReactorAutoConfiguration}:
 * SA tool callbacks end up in the auto-configured {@link ToolProvider}.
 */
class SpringAiToolBridgeAutoConfigurationTest {

    private static final ChatClient.Builder MOCK_CHAT_CLIENT_BUILDER = chatClientBuilder();

    private static ChatClient.Builder chatClientBuilder() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        return builder;
    }

    /** Native IR tool used as a baseline in the assertions. */
    private static class NativeIrTool implements Tool {
        @Override
        public String getName() {
            return "native_ir_tool";
        }

        @Override
        public String getDescription() {
            return "A native IntentReactor tool";
        }

        @Override
        public Map<String, Object> getParameterSchema() {
            return Map.of();
        }

        @Override
        public ToolResult execute(ToolInput input) {
            return ToolResult.ok("native");
        }

        @Override
        public boolean isRisky() {
            return false;
        }
    }

    private static class StaticSaProvider implements ToolCallbackProvider {
        private final ToolCallback[] callbacks;

        StaticSaProvider(ToolCallback... callbacks) {
            this.callbacks = callbacks;
        }

        @Override
        public ToolCallback[] getToolCallbacks() {
            return callbacks;
        }
    }

    private static ToolCallback saCallback(String name) {
        ToolCallback cb = mock(ToolCallback.class);
        when(cb.getToolDefinition()).thenReturn(DefaultToolDefinition.builder()
                .name(name)
                .description("Spring AI tool " + name)
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build());
        return cb;
    }

    @Test
    void defaultToolProviderBridgesSpringAiTools() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IntentReactorAutoConfiguration.class))
                .withBean(ChatClient.Builder.class, () -> MOCK_CHAT_CLIENT_BUILDER)
                .withBean(NativeIrTool.class)
                .withBean("directSaTool", ToolCallback.class, () -> saCallback("sa_direct"))
                .withBean("saProvider", ToolCallbackProvider.class,
                        () -> new StaticSaProvider(saCallback("sa_provider")))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ToolProvider.class);
                    ToolProvider provider = context.getBean(ToolProvider.class);
                    assertThat(provider.getAvailableTools(new SessionState("s")))
                            .extracting(Tool::getName)
                            .containsExactlyInAnyOrder("native_ir_tool", "sa_direct", "sa_provider");
                });
    }

    @Test
    void disabledBridgeIgnoresSpringAiTools() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IntentReactorAutoConfiguration.class))
                .withPropertyValues("intent-reactor.tools.spring-ai.enabled=false")
                .withBean(ChatClient.Builder.class, () -> MOCK_CHAT_CLIENT_BUILDER)
                .withBean(NativeIrTool.class)
                .withBean("directSaTool", ToolCallback.class, () -> saCallback("sa_direct"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ToolProvider provider = context.getBean(ToolProvider.class);
                    assertThat(provider.getAvailableTools(new SessionState("s")))
                            .extracting(Tool::getName)
                            .containsExactly("native_ir_tool");
                });
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl intent-reactor-core -am -Dtest=SpringAiToolBridgeAutoConfigurationTest test`
Expected: FAIL — the first test's assertion fails (only `native_ir_tool` is returned; SA callbacks are not collected).

- [ ] **Step 3: Implement the wiring**

In `IntentReactorAutoConfiguration.java`:
- Add import `org.springframework.beans.factory.ListableBeanFactory`.
- Add import `com.intentreactor.core.tool.SpringAiToolsCollector`.
- Replace the `defaultToolProvider` method (lines 237-241):

```java
    @Bean
    @ConditionalOnMissingBean(ToolProvider.class)
    public ToolProvider defaultToolProvider(List<Tool> tools,
                                            IntentReactorProperties properties,
                                            ObjectMapper objectMapper,
                                            ListableBeanFactory beanFactory) {
        if (!properties.getTools().getSpringAi().isEnabled()) {
            return new DefaultToolProvider(tools);
        }
        SpringAiToolsCollector collector = new SpringAiToolsCollector();
        List<Tool> saTools = collector.collectSpringAiTools(beanFactory, objectMapper,
                properties.getTools().getSpringAi());
        return new DefaultToolProvider(collector.mergeWithIrTools(tools, saTools));
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl intent-reactor-core -am -Dtest=SpringAiToolBridgeAutoConfigurationTest test`
Expected: PASS.

- [ ] **Step 5: Run the module's full test set (regression: other auto-config tests still pass)**

Run: `mvn -pl intent-reactor-core -am test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add intent-reactor-core/src/main/java/com/intentreactor/core/config/IntentReactorAutoConfiguration.java intent-reactor-core/src/test/java/com/intentreactor/core/config/SpringAiToolBridgeAutoConfigurationTest.java
git commit -m "Wire Spring AI tool bridge into default ToolProvider"
```

---

### Task 5: Documentation (EN + RU)

**Files:**
- Modify: `README.md`, `README-ru.md`
- Modify: `docs/13-configuration-reference.md`, `docs-ru/13-configuration-reference.md`

**Interfaces:** none (docs only).

- [ ] **Step 1: README (EN) — Spring AI style tooling note**

In `README.md`, between the step-3 code sample (ends with the `OrderLookupTool` class `}` + closing ```) and the heading `### 4. Process user messages` (line 117), insert:

````markdown
Tools can also be authored in the Spring AI style. Any `ToolCallback` bean or
`ToolCallbackProvider` bean (e.g. `MethodToolCallbackProvider` over a `@Tool` class) in
the application context is automatically discovered and exposed to all planners —
no IntentReactor-specific code needed. Execution flows through the same
`[TOOL_RESULT]`/`[TOOL_ERROR]` loop as native tools. Spring AI has no notion of
"risky", so confirmation semantics are configured:

```yaml
intent-reactor:
  tools:
    spring-ai:
      enabled: true                 # false = only native Tool beans
      risky-tool-names: [send_email]    # confirmation gate when autonomous: false
      treat-all-as-risky: false
```

> Native `Tool` beans always win name conflicts against Spring AI tools. MCP server
> tools are consumed through the MCP client module, not this bridge.
````

- [ ] **Step 2: README (RU) — mirror**

In `README-ru.md`, between the step-3 code sample (ends with `}` of `OrderLookupTool` + closing ```) and the heading `### 4. Обрабатывайте сообщения пользователя` (line 117), insert the Russian mirror:

````markdown
Инструменты можно описывать и в стиле Spring AI. Любой бин `ToolCallback` или
`ToolCallbackProvider` (например, `MethodToolCallbackProvider` над классом с методами
`@Tool`) в контексте приложения обнаруживается автоматически и становится доступен
всем планировщикам — без единой строчки кода под IntentReactor. Исполнение идёт через
тот же цикл `[TOOL_RESULT]`/`[TOOL_ERROR]`, что и для обычных инструментов. В Spring AI
нет понятия «рискованный инструмент», поэтому семантика подтверждений задаётся конфигом:

```yaml
intent-reactor:
  tools:
    spring-ai:
      enabled: true                 # false = только собственные бины Tool
      risky-tool-names: [send_email]    # подтверждение при autonomous: false
      treat-all-as-risky: false
```

> Собственные бины `Tool` всегда выигрывают конфликт имён у инструментов Spring AI.
> MCP-инструменты серверов потребляются через модуль MCP client, а не через этот мост.
````

- [ ] **Step 3: Configuration reference (EN)**

In `docs/13-configuration-reference.md`:
1. Insert the YAML block directly BEFORE the line `    # ─── Dynamic JavaScript tools ─────────────────────────────────────────────` (line 203), keeping 4-space indentation under `tools:`:

```yaml
    # ─── Spring AI tool bridge ──────────────────────────────────────────────────
    # Spring AI ToolCallback / ToolCallbackProvider beans are automatically exposed
    # to IntentReactor planners as tools.
    spring-ai:
      enabled: true
      # Spring AI tool names always treated as risky
      # (confirmation gate when planning.autonomous is false).
      risky-tool-names: []
      # Treat every Spring AI-sourced tool as risky.
      treat-all-as-risky: false
```

2. In the "Quick property index" table, insert these three rows directly BEFORE the row `` | `tools.dynamic-scripting.enabled` | boolean | `false` | `` (line 341):

```markdown
| `tools.spring-ai.enabled` | boolean | `true` |
| `tools.spring-ai.risky-tool-names` | List\<String\> | `[]` |
| `tools.spring-ai.treat-all-as-risky` | boolean | `false` |
```

- [ ] **Step 4: Configuration reference (RU) — mirror**

In `docs-ru/13-configuration-reference.md`:
1. Insert the YAML block directly BEFORE the line starting `    # ─── Динамические JavaScript-инструменты ─` (line 188), same indentation:

```yaml
    # ─── Мост инструментов Spring AI ────────────────────────────────────────────
    # Бины Spring AI ToolCallback / ToolCallbackProvider автоматически становятся
    # инструментами IntentReactor.
    spring-ai:
      enabled: true
      # Имена инструментов Spring AI, которые всегда считаются рискованными
      # (подтверждение при planning.autonomous: false).
      risky-tool-names: []
      # Считать все инструменты Spring AI рискованными.
      treat-all-as-risky: false
```

2. In the quick property index table, insert the same three rows (translated comments are not used in this table; keys/values are identical) directly BEFORE the row containing `` | `tools.dynamic-scripting.enabled` `` (line 281):

```markdown
| `tools.spring-ai.enabled` | boolean | `true` |
| `tools.spring-ai.risky-tool-names` | List\<String\> | `[]` |
| `tools.spring-ai.treat-all-as-risky` | boolean | `false` |
```

- [ ] **Step 5: Full reactor verification**

Run: `mvn test` (from the reactor root)
Expected: BUILD SUCCESS — all modules, all tests.

- [ ] **Step 6: Commit**

```bash
git add README.md README-ru.md docs/13-configuration-reference.md docs-ru/13-configuration-reference.md
git commit -m "Document Spring AI tool bridge (EN/RU)"
```

---

## Self-review notes

- Spec coverage: collection semantics + type-name exclusion without instantiation → Task 3; adapter semantics (metadata, JSON round-trip, ToolContext sessionId, exception→error, isRisky config, no generator/simulatable) → Task 2; config surface + defaults → Task 1; wiring into default ToolProvider + enabled=false short-circuit → Task 4; name-conflict rules → Tasks 2 (n/a) and 3 (`mergeWithIrTools`), dedupe inside SA sources → Task 3; error handling (provider failure, blank names) → Task 3; docs EN/RU → Task 5; testing conventions → every task.
- Placeholders: none — every step contains concrete code or exact anchors.
- Type consistency: `SpringAiToolsConfig` is referenced as `IntentReactorProperties.SpringAiToolsConfig` everywhere; `SpringAiToolsCollector.collectSpringAiTools(ListableBeanFactory, ObjectMapper, SpringAiToolsConfig)` and `mergeWithIrTools(List<Tool>, List<Tool>)` signatures match across Tasks 3-4; `SpringAiToolAdapter(ToolCallback, ObjectMapper, SpringAiToolsConfig)` matches Task 2-3 usage.
- RU doc files and `docs/*` are UTF-8; anchor lines given as exact text — if line numbers shifted during implementation, locate by anchor text with grep/read.
