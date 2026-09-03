package com.intentreactor.strategies.decomposition;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.intentreactor.api.Action;
import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.Message;
import com.intentreactor.api.Plan;
import com.intentreactor.api.Planner;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.SimpleAction;
import com.intentreactor.api.SimplePlan;
import com.intentreactor.api.SimplePlanStep;
import com.intentreactor.api.StepType;
import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolProvider;
import com.intentreactor.core.MessageMarkers;
import com.intentreactor.core.config.IntentReactorProperties;
import com.intentreactor.core.util.PromptLoader;
import com.intentreactor.strategies.config.StrategiesProperties;
import com.intentreactor.strategies.config.StrategySessionKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Least-to-Most planner: decomposes the goal into ordered subproblems (simplest first),
 * then solves each one in order, using previous solutions as context for subsequent ones.
 * <p>
 * Prompt files configured via intent-reactor.planning.strategies.prompts.*
 * <p>
 * Activate with: intent-reactor.planning.strategy: least-to-most
 */
public class LeastToMostPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(LeastToMostPlanner.class);

    private static final String PHASE_KEY = StrategySessionKeys.LTM_PHASE;
    private static final String TASKS_KEY = StrategySessionKeys.LTM_TASKS;
    private static final String RESULTS_KEY = StrategySessionKeys.LTM_RESULTS;
    private static final String INDEX_KEY = StrategySessionKeys.LTM_INDEX;
    private static final String PENDING_TOOL_KEY = StrategySessionKeys.LTM_PENDING_TOOL;

    private final ChatClient chatClient;
    private final ToolProvider toolProvider;
    private final ObjectMapper objectMapper;
    private final int maxSubproblems;
    private final boolean autonomous;
    private final PromptLoader promptLoader = new PromptLoader();
    private final String decomposePromptPath;
    private final String solvePromptPath;
    private final String synthesizePromptPath;
    private final StrategiesProperties.LabelsConfig labels;

    public LeastToMostPlanner(ChatClient chatClient, ToolProvider toolProvider,
                              ObjectMapper objectMapper, StrategiesProperties props,
                              IntentReactorProperties intentReactorProperties) {
        this.chatClient = chatClient;
        this.toolProvider = toolProvider;
        this.objectMapper = objectMapper;
        this.maxSubproblems = props.getLeastToMost().getMaxSubproblems();
        this.autonomous = intentReactorProperties.getPlanning().isAutonomous();
        this.decomposePromptPath = props.getPrompts().getLeastToMostDecompose();
        this.solvePromptPath = props.getPrompts().getLeastToMostSolve();
        this.synthesizePromptPath = props.getPrompts().getLeastToMostSynthesize();
        this.labels = props.getLabels();
    }

    @Override
    public Plan plan(SessionState session, IntentAnalysisResult intent) {
        String phase = (String) session.getAttributes().getOrDefault(PHASE_KEY, "DECOMPOSE");
        String goal = getGoal(session);

        return switch (phase) {
            case "DECOMPOSE" -> decompose(session, goal);
            case "SOLVE" -> solveNext(session, goal);
            default -> decompose(session, goal);
        };
    }

    private Plan decompose(SessionState session, String goal) {
        try {
            String systemPrompt = promptLoader.load(decomposePromptPath,
                    Map.of("max", maxSubproblems));
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(labels.getTask() + goal)
            ))).call().content();

            List<Map<String, Object>> tasks = parseTasks(response);
            if (tasks.size() > maxSubproblems) tasks = tasks.subList(0, maxSubproblems);

            if (tasks.isEmpty()) {
                log.info("[LeastToMost] No sub-problems for session {}, answering directly", session.getId());
                session.getAttributes().put(TASKS_KEY, tasks);
                session.getAttributes().put(RESULTS_KEY, new LinkedHashMap<>());
                session.getAttributes().put(INDEX_KEY, 0);
                session.getAttributes().put(PHASE_KEY, "SOLVE");
                return synthesizeFinal(session, goal, new LinkedHashMap<>());
            }

            session.getAttributes().put(TASKS_KEY, tasks);
            session.getAttributes().put(RESULTS_KEY, new LinkedHashMap<>());
            session.getAttributes().put(INDEX_KEY, 0);
            session.getAttributes().put(PHASE_KEY, "SOLVE");

            log.debug("[LeastToMost] Decomposed into {} tasks for session {}", tasks.size(), session.getId());
            return solveNext(session, goal);

        } catch (Exception e) {
            log.warn("[LeastToMost] Decomposition failed: {}", e.getMessage());
            return new SimplePlan(List.of(SimplePlanStep.fail("Decomposition failed: " + e.getMessage())));
        }
    }

    @SuppressWarnings("unchecked")
    private Plan solveNext(SessionState session, String goal) {
        List<Map<String, Object>> tasks =
                (List<Map<String, Object>>) session.getAttributes().get(TASKS_KEY);
        Map<String, String> results =
                (Map<String, String>) session.getAttributes().get(RESULTS_KEY);
        int index = (int) session.getAttributes().getOrDefault(INDEX_KEY, 0);

        // A tool ACT for the current sub-problem was executed by the engine: collect its result.
        Object pendingTool = session.getAttributes().remove(PENDING_TOOL_KEY);
        if (pendingTool != null) {
            if (index >= tasks.size()) {
                return synthesizeFinal(session, goal, results);
            }
            Map<String, Object> task = tasks.get(index);
            String taskDesc = (String) task.get("task");
            String toolAnswer = lastToolResultMessage(session);
            results.put(String.valueOf(index), toolAnswer);
            session.getAttributes().put(RESULTS_KEY, results);
            int nextIndex = index + 1;
            session.getAttributes().put(INDEX_KEY, nextIndex);
            log.debug("[LeastToMost] Tool result recorded for task {}/{} for session {}", index + 1,
                    tasks.size(), session.getId());
            if (nextIndex >= tasks.size()) {
                return synthesizeFinal(session, goal, results);
            }
            return new SimplePlan(List.of(new SimplePlanStep(StepType.REASON, null,
                    "Solved: " + taskDesc, false)));
        }

        if (index >= tasks.size()) {
            return synthesizeFinal(session, goal, results);
        }

        Map<String, Object> task = tasks.get(index);
        String taskDesc = (String) task.get("task");

        StringBuilder context = new StringBuilder();
        if (!results.isEmpty()) {
            context.append(labels.getPreviousResults());
            String subLabel = labels.getSubtask().replaceFirst("^\\[", "").replaceFirst("\\s*$", " ");
            results.forEach((k, v) -> context.append(subLabel).append(k).append(": ").append(v).append("\n"));
        }

        String userMsg = labels.getSubtask() + (index + 1) + "/" + tasks.size() + "] " + taskDesc + context;
        SolveDecision decision = decide(session, userMsg);
        if (decision.requiresTool && decision.toolName != null && !decision.toolName.isBlank()) {
            List<Tool> tools = toolProvider.getAvailableTools(session);
            Tool tool = tools.stream()
                    .filter(t -> t.getName().equals(decision.toolName))
                    .findFirst()
                    .orElse(null);
            if (tool != null) {
                boolean needsConfirmation = tool.isRisky() && !autonomous;
                Action action = new SimpleAction(tool.getName(), effectiveParameters(tool, taskDesc, decision.parameters));
                session.getAttributes().put(PENDING_TOOL_KEY, true);
                log.debug("[LeastToMost] Sub-problem {}/{} requires tool {} for session {}", index + 1,
                        tasks.size(), tool.getName(), session.getId());
                return new SimplePlan(List.of(SimplePlanStep.act(action,
                        "Solve sub-problem: " + taskDesc, needsConfirmation)));
            }
            log.warn("[LeastToMost] Requested tool '{}' unavailable for sub-problem {}, answering directly",
                    decision.toolName, index);
        }

        String answer = decision.answer != null ? decision.answer : "";
        results.put(String.valueOf(index), answer);
        session.getAttributes().put(RESULTS_KEY, results);
        session.getAttributes().put(INDEX_KEY, index + 1);
        log.debug("[LeastToMost] Solved task {}/{} for session {}", index + 1, tasks.size(), session.getId());

        return new SimplePlan(List.of(new SimplePlanStep(StepType.REASON, null,
                "Solved: " + taskDesc, false)));
    }

    private SolveDecision decide(SessionState session, String userMsg) {
        List<Tool> tools = toolProvider.getAvailableTools(session);
        String toolsList = tools.stream()
                .map(t -> "- " + t.getName() + ": " + t.getDescription())
                .collect(Collectors.joining("\n"));
        try {
            String solveSystem = promptLoader.load(solvePromptPath, Map.of("tools", toolsList));
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(solveSystem),
                    new UserMessage(userMsg)
            ))).call().content();
            return parseSolveDecision(response);
        } catch (Exception e) {
            log.warn("[LeastToMost] Solve failed: {}", e.getMessage());
            SolveDecision fallback = new SolveDecision();
            fallback.answer = "";
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private SolveDecision parseSolveDecision(String response) {
        SolveDecision decision = new SolveDecision();
        try {
            String cleaned = stripMarkdownFences(response.strip());
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start < 0 || end <= start) {
                decision.answer = cleaned;
                return decision;
            }
            Map<String, Object> map = objectMapper.readValue(cleaned.substring(start, end + 1), Map.class);
            decision.requiresTool = Boolean.TRUE.equals(map.get("requires_tool"));
            decision.toolName = (String) map.get("tool_name");
            decision.answer = (String) map.get("answer");
            Object params = map.get("parameters");
            if (params instanceof Map) {
                decision.parameters = (Map<String, Object>) params;
            }
            return decision;
        } catch (Exception e) {
            decision.answer = response;
            return decision;
        }
    }

    private Map<String, Object> effectiveParameters(Tool tool, String taskDesc,
                                                    Map<String, Object> llmParameters) {
        if (llmParameters != null && !llmParameters.isEmpty()) {
            return llmParameters;
        }
        String paramKey = firstParameterKey(tool);
        return Map.of(paramKey, taskDesc);
    }

    private String lastToolResultMessage(SessionState session) {
        List<Message> messages = session.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m.getRole() != Message.Role.SYSTEM || m.getContent() == null) continue;
            if (m.getContent().startsWith(MessageMarkers.TOOL_RESULT)
                    || m.getContent().startsWith(MessageMarkers.TOOL_ERROR)) {
                return m.getContent();
            }
        }
        return "";
    }

    /** Name of the first required parameter of the tool schema, falling back to the first one. */
    private static String firstParameterKey(Tool tool) {
        Object rawSchema = tool.getParameterSchema();
        if (rawSchema instanceof Map<?, ?> schema) {
            Object properties = schema.get("properties");
            if (properties instanceof Map<?, ?> props && !props.isEmpty()) {
                List<?> required = schema.get("required") instanceof List<?> list ? list : List.of();
                for (Object key : required) {
                    if (props.containsKey(key)) return key.toString();
                }
                Object firstKey = props.keySet().iterator().next();
                return firstKey.toString();
            }
        }
        return "query";
    }

    private static final class SolveDecision {
        boolean requiresTool;
        String toolName;
        String answer;
        Map<String, Object> parameters;
    }

    private Plan synthesizeFinal(SessionState session, String goal, Map<String, String> results) {
        String combined = results.values().stream()
                .collect(Collectors.joining("\n---\n"));
        try {
            String synthesizeSystem = promptLoader.load(synthesizePromptPath, Map.of());
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(synthesizeSystem),
                    new UserMessage(labels.getOriginalTask() + goal + labels.getSubtaskResults() + combined)
            ))).call().content();
            return new SimplePlan(List.of(SimplePlanStep.done(response)));
        } catch (Exception e) {
            return new SimplePlan(List.of(SimplePlanStep.done(combined)));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseTasks(String response) {
        try {
            String cleaned = stripMarkdownFences(response.strip());
            int start = cleaned.indexOf('[');
            int end = cleaned.lastIndexOf(']');
            if (start >= 0 && end > start) cleaned = cleaned.substring(start, end + 1);
            return objectMapper.readValue(cleaned, new TypeReference<>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String stripMarkdownFences(String s) {
        if (!s.startsWith("```")) return s;
        int newline = s.indexOf('\n');
        if (newline < 0) return s;
        s = s.substring(newline + 1);
        int fence = s.lastIndexOf("```");
        if (fence >= 0) s = s.substring(0, fence);
        return s.strip();
    }

    private String getGoal(SessionState session) {
        if (session.getPlanState() != null) {
            String g = session.getPlanState().getGoalDescription();
            if (g != null && !g.isBlank()) return g;
        }
        List<Message> msgs = session.getMessages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i).getRole() == Message.Role.USER) return msgs.get(i).getContent();
        }
        return "unknown";
    }
}
