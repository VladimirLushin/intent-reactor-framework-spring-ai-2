package com.intentreactor.core;

import com.intentreactor.api.IntentReactorService;
import com.intentreactor.api.Message;
import com.intentreactor.api.PlanStatus;
import com.intentreactor.api.ReactorResponse;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.SessionStore;
import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in live end-to-end test that exercises the full core pipeline against a real
 * OpenAI-compatible chat model instead of a Mockito mock: intent preprocessor,
 * ReACT planner, tool execution, and a Jackson 3 session file round-trip.
 *
 * <p>It is not part of the regular unit-test suite: the {@code *IT} suffix keeps surefire
 * from picking it up during {@code mvn test} / CI, and it additionally skips itself when
 * the {@code LLM_API_KEY} environment variable is not set. To run it:
 *
 * <pre>{@code
 * mvn -pl intent-reactor-core -am -Dtest=LiveLlmEndToEndIT -Dsurefire.failIfNoSpecifiedTests=false test
 * }</pre>
 *
 * <p>Defaults point at DeepSeek ({@code https://api.deepseek.com}, model {@code deepseek-chat}),
 * which is OpenAI-compatible; any other OpenAI-compatible endpoint can be used by overriding
 * the {@code spring.ai.openai.*} properties below.
 */
@SpringBootTest(classes = LiveLlmEndToEndIT.LiveApplication.class, properties = {
        "spring.ai.openai.base-url=https://api.deepseek.com",
        "spring.ai.openai.api-key=${LLM_API_KEY}",
        "spring.ai.openai.chat.options.model=deepseek-chat",
        "spring.ai.openai.chat.options.temperature=0.2",
        "intent-reactor.planning.autonomous=true",
        "intent-reactor.session.store=filesystem",
        "intent-reactor.session.filesystem.path=target/deepseek-it-sessions"
})
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")
class LiveLlmEndToEndIT {

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
    private SessionStore sessionStore;

    @AfterAll
    static void cleanUp() throws IOException {
        Path dir = Path.of("target/deepseek-it-sessions");
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        // best effort cleanup
                    }
                });
            }
        }
    }

    @Test
    void endToEndWithSessionFileRoundTrip() {
        String sessionId = "deepseek-e2e";

        // Turn 1: the LLM must plan, call the calculator tool, and finish with the answer.
        ReactorResponse first = service.process(sessionId,
                "Посчитай выражение (17+4)*3 с помощью калькулятора и назови получившееся число.");
        System.out.println("[TURN1] status=" + first.getStatus() + " finalText=" + first.getFinalText());
        System.out.println("[TURN1] actions=" + first.getActions());

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
        System.out.println("[TURN2] status=" + second.getStatus() + " finalText=" + second.getFinalText());
        System.out.println("[TURN2] actions=" + second.getActions());

        assertThat(second.getStatus()).isEqualTo(PlanStatus.COMPLETED);
        assertThat(second.getFinalText()).contains("21");

        SessionState afterSecond = sessionStore.findById(sessionId).orElseThrow();
        assertThat(afterSecond.getMessages().size()).isGreaterThan(historySize);
    }

    /** Simple arithmetic tool used by the live scenario (name is part of the prompt contract). */
    public static class CalculatorTool implements Tool {

        @Override
        public String getName() {
            return "calculator";
        }

        @Override
        public String getDescription() {
            return "Вычисляет значение простого арифметического выражения с операторами + - * / и "
                    + "круглыми скобками, например \"(17+4)*3\". Принимает параметр expression и возвращает число.";
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
