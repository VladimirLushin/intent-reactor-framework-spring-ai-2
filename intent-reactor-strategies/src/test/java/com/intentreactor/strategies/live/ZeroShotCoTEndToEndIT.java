package com.intentreactor.strategies.live;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Opt-in live end-to-end test for the {@code zero-shot-cot} strategy.
 * The scenario itself lives in {@link LiveLlmEndToEndITBase}.
 *
 * <pre>{@code
 * mvn -pl intent-reactor-strategies -am -Dtest=ZeroShotCoTEndToEndIT -Dsurefire.failIfNoSpecifiedTests=false test
 * }</pre>
 */
@SpringBootTest(classes = LiveLlmEndToEndITBase.LiveApplication.class,
        properties = "intent-reactor.planning.strategy=zero-shot-cot")
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")
class ZeroShotCoTEndToEndIT extends LiveLlmEndToEndITBase {

    @Override
    protected String strategyKey() {
        return "zero-shot-cot";
    }
}
