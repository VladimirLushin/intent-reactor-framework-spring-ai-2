package com.intentreactor.strategies.live;

import com.intentreactor.api.IntentReactorService;
import com.intentreactor.api.Message;
import com.intentreactor.api.PlanStatus;
import com.intentreactor.api.ReactorResponse;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import com.intentreactor.core.session.SessionStateStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Abstract base for the opt-in live end-to-end integration tests of the extended
 * strategies module: one concrete subclass per strategy. Each subclass boots the full
 * auto-configuration (core + {@code StrategiesAutoConfiguration}) for its strategy and
 * inherits a single canonical scenario that drives a real OpenAI-compatible chat model
 * through the whole framework: intent preprocessor, planner, tool execution, and a
 * Jackson 3 filesystem-session round-trip across two dialog turns.
 *
 * <p>These tests are not part of the regular unit-test suite: the {@code *IT} suffix
 * keeps surefire from picking them up during {@code mvn test} / CI, and each subclass
 * additionally skips itself when the {@code LLM_API_KEY} environment variable is unset.
 *
 * <p>Defaults point at DeepSeek ({@code https://api.deepseek.com}, model
 * {@code deepseek-chat}), which is OpenAI-compatible; the endpoint and the model can
 * be overridden with {@code -Dlive.llm.base-url=...} and {@code -Dlive.llm.model=...}.
 *
 * <p>To run a single strategy test, e.g. the SAND one:
 *
 * <pre>{@code
 * mvn -pl intent-reactor-strategies -am -Dtest=SandEndToEndIT -Dsurefire.failIfNoSpecifiedTests=false test
 * }</pre>
 *
 * To run all live tests of the module (16 strategies; with {@code -am} the core live
 * tests for react/reflexion/lats run first):
 *
 * <pre>{@code
 * mvn -pl intent-reactor-strategies -am -Dtest='*EndToEndIT' -Dsurefire.failIfNoSpecifiedTests=false test
 * }</pre>
 *
 * <p>Contract for a concrete subclass:
 * <ul>
 *   <li>implement {@link #strategyKey()} returning the value of the
 *       {@code intent-reactor.planning.strategy} property it activates;</li>
 *   <li>declare {@code @SpringBootTest(classes = LiveLlmEndToEndITBase.LiveApplication.class,
 *       properties = "intent-reactor.planning.strategy=&lt;strategy&gt;")};</li>
 *   <li>declare {@code @EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")}.</li>
 * </ul>
 */
abstract class LiveLlmEndToEndITBase {

    /** Module-relative directory for the filesystem session store (kept inside {@code target}). */
    private static final String SESSION_DIR = "target/live-it-sessions";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class LiveApplication {

        @Bean
        CalculatorTool calculatorTool() {
            return new CalculatorTool();
        }
    }

    @Autowired
    private IntentReactorService service;

    @Autowired
    private SessionStateStore sessionStore;

    @DynamicPropertySource
    static void liveLlmProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url",
                () -> System.getProperty("live.llm.base-url", "https://api.deepseek.com"));
        registry.add("spring.ai.openai.api-key", () -> System.getenv("LLM_API_KEY"));
        registry.add("spring.ai.openai.chat.options.model",
                () -> System.getProperty("live.llm.model", "deepseek-chat"));
        registry.add("spring.ai.openai.chat.options.temperature", () -> "0.2");
        registry.add("intent-reactor.planning.autonomous", () -> "true");
        registry.add("intent-reactor.session.store", () -> "filesystem");
        registry.add("intent-reactor.session.filesystem.path", () -> SESSION_DIR);
    }

    /** Strategy value under {@code intent-reactor.planning.strategy}, e.g. {@code "cot"}. */
    protected abstract String strategyKey();

    @Test
    void calculatorScenario_twoTurnsWithSessionRoundTrip() {
        String sessionId = "live-" + strategyKey();
        String prefix = "[" + strategyKey() + "]";
        deleteOwnSessionFiles(sessionId);

        // Turn 1: the LLM must plan, call the calculator tool, and finish with the answer.
        ReactorResponse first = service.process(sessionId,
                "Посчитай выражение (17+4)*3 с помощью калькулятора и назови получившееся число.");
        System.out.println(prefix + " TURN1 status=" + first.getStatus() + " finalText=" + first.getFinalText());
        System.out.println(prefix + " TURN1 actions=" + first.getActions());

        assertThat(first.getStatus()).isEqualTo(PlanStatus.COMPLETED);
        assertThat(first.getActions())
                .isNotEmpty()
                .allSatisfy(a -> assertThat(a.getToolName()).isEqualTo("calculator"));
        assertThat(first.getFinalText()).contains("63");

        // Turn 1 verification: the session was saved to JSON and read back (Jackson 3 round-trip).
        Optional<SessionState> loaded = sessionStore.findById(sessionId);
        assertThat(loaded).isPresent();
        SessionState state = loaded.get();
        assertThat(state.getPlanState().getStatus()).isEqualTo(PlanStatus.COMPLETED);
        assertThat(state.getMessages())
                .anyMatch(m -> m.getRole() == Message.Role.SYSTEM
                        && m.getContent().contains("[TOOL_RESULT]")
                        && m.getContent().contains("63"));

        int historySize = state.getMessages().size();

        // Turn 2: the planner must continue on top of the deserialized history.
        ReactorResponse second = service.process(sessionId,
                "Раздели то число, которое ты уже вычислил в этой сессии, на 3 и назови итог.");
        System.out.println(prefix + " TURN2 status=" + second.getStatus() + " finalText=" + second.getFinalText());
        System.out.println(prefix + " TURN2 actions=" + second.getActions());

        assertThat(second.getStatus()).isEqualTo(PlanStatus.COMPLETED);
        assertThat(second.getFinalText()).contains("21");

        SessionState afterSecond = sessionStore.findById(sessionId).orElseThrow();
        assertThat(afterSecond.getMessages().size()).isGreaterThan(historySize);
    }

    /**
     * Drops the session files of this scenario from a previous (possibly crashed) run, so the
     * scenario always starts from a fresh session. Only this session's files are removed — the
     * session-store directory itself must stay in place because {@link
     * com.intentreactor.core.session.FileSystemSessionRepository} creates it once, at startup.
     */
    private static void deleteOwnSessionFiles(String sessionId) {
        try {
            Files.deleteIfExists(Path.of(SESSION_DIR, sessionId + ".json"));
            Files.deleteIfExists(Path.of(SESSION_DIR, sessionId + ".json.tmp"));
        } catch (IOException e) {
            // best effort cleanup
        }
    }

    /** Simple arithmetic tool used by the live scenario (name is part of the prompt contract). */
    public static class CalculatorTool implements Tool {

        @Override
        public String getName() {
            return "calculator";
        }

        @Override
        public String getDescription() {
            return "Калькулятор арифметики. ВЫЧИСЛЕНИЯ ВЫПОЛНЯЙ ТОЛЬКО ИМ: если задача содержит арифметическое "
                    + "выражение (даже простое) или пользователь просит посчитать — обязательно вызови calculator и "
                    + "передай выражение целиком, без пояснений, в параметр expression, например "
                    + "expression=\"(17+4)*3\". Никогда не вычисляй арифметику в уме и не называй результат, пока не "
                    + "получишь его от этого инструмента. Инструмент возвращает число — результат вычисления.";
        }

        @Override
        public Map<String, Object> getParameterSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "expression", Map.of(
                                    "type", "string",
                                    "description", "Арифметическое выражение для вычисления")),
                    "required", List.of("expression"));
        }

        @Override
        public ToolResult execute(ToolInput input) {
            Object raw = input.getParameters().get("expression");
            if (!(raw instanceof String expression) || expression.isBlank()) {
                return ToolResult.error("Параметр expression должен быть непустой строкой");
            }
            try {
                double value = new Evaluator(expression).parse();
                return ToolResult.ok(format(value));
            } catch (IllegalArgumentException e) {
                return ToolResult.error("Не удалось вычислить выражение: " + e.getMessage());
            }
        }

        @Override
        public boolean isRisky() {
            return false;
        }

        private static String format(double value) {
            return value == Math.rint(value) && !Double.isInfinite(value)
                    ? Long.toString(Math.round(value))
                    : Double.toString(value);
        }

        private static final class Evaluator {
            private final String source;
            private int pos;

            Evaluator(String source) {
                this.source = source.replaceAll("\\s+", "");
            }

            double parse() {
                double value = parseExpression();
                if (pos != source.length()) {
                    throw new IllegalArgumentException("Неожиданный символ на позиции " + pos);
                }
                return value;
            }

            private double parseExpression() {
                double value = parseTerm();
                while (pos < source.length() && (source.charAt(pos) == '+' || source.charAt(pos) == '-')) {
                    char op = source.charAt(pos++);
                    double right = parseTerm();
                    value = op == '+' ? value + right : value - right;
                }
                return value;
            }

            private double parseTerm() {
                double value = parseFactor();
                while (pos < source.length() && (source.charAt(pos) == '*' || source.charAt(pos) == '/')) {
                    char op = source.charAt(pos++);
                    double right = parseFactor();
                    value = op == '*' ? value * right : value / right;
                }
                return value;
            }

            private double parseFactor() {
                if (pos >= source.length()) {
                    throw new IllegalArgumentException("Выражение обрывается");
                }
                char c = source.charAt(pos);
                if (c == '(') {
                    pos++;
                    double value = parseExpression();
                    if (pos >= source.length() || source.charAt(pos) != ')') {
                        throw new IllegalArgumentException("Нет закрывающей скобки");
                    }
                    pos++;
                    return value;
                }
                if (c == '-') {
                    pos++;
                    return -parseFactor();
                }
                if (c == '+') {
                    pos++;
                    return parseFactor();
                }
                return parseNumber();
            }

            private double parseNumber() {
                int start = pos;
                while (pos < source.length()
                        && (Character.isDigit(source.charAt(pos)) || source.charAt(pos) == '.')) {
                    pos++;
                }
                if (start == pos) {
                    throw new IllegalArgumentException("Ожидалось число на позиции " + pos);
                }
                try {
                    return Double.parseDouble(source.substring(start, pos));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Некорректное число: " + source.substring(start, pos));
                }
            }
        }
    }
}
