package com.intentreactor.core;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Opt-in live end-to-end test for the built-in {@code react} strategy (the default
 * ReACT planner). It exercises the full core pipeline against a real OpenAI-compatible
 * chat model: intent preprocessor, planner, tool execution, and a Jackson 3 session
 * file round-trip. The scenario itself lives in {@link LiveLlmEndToEndITBase}.
 *
 * <p>It is not part of the regular unit-test suite: the {@code *IT} suffix keeps surefire
 * from picking it up during {@code mvn test} / CI, and it additionally skips itself when
 * the {@code LLM_API_KEY} environment variable is not set. To run it:
 *
 * <pre>{@code
 * mvn -pl intent-reactor-core -am -Dtest=LiveLlmEndToEndIT -Dsurefire.failIfNoSpecifiedTests=false test
 * }</pre>
 */
@SpringBootTest(classes = LiveLlmEndToEndITBase.LiveApplication.class,
        properties = "intent-reactor.planning.strategy=react")
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")
class LiveLlmEndToEndIT extends LiveLlmEndToEndITBase {

    @Override
    protected String strategyKey() {
        return "react";
    }
}
