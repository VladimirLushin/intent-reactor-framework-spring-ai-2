package com.intentreactor.strategies.live;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Opt-in live end-to-end test for the {@code sand} strategy (SAND - Search and
 * Deliberation). The scenario itself lives in {@link LiveLlmEndToEndITBase}.
 *
 * <pre>{@code
 * mvn -pl intent-reactor-strategies -am -Dtest=SandEndToEndIT -Dsurefire.failIfNoSpecifiedTests=false test
 * }</pre>
 */
@SpringBootTest(classes = LiveLlmEndToEndITBase.LiveApplication.class,
        properties = "intent-reactor.planning.strategy=sand")
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")
class SandEndToEndIT extends LiveLlmEndToEndITBase {

    @Override
    protected String strategyKey() {
        return "sand";
    }
}
