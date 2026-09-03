package com.intentreactor.strategies;

import tools.jackson.databind.ObjectMapper;
import com.intentreactor.api.Intent;
import com.intentreactor.api.IntentAnalysisResult;
import com.intentreactor.api.Plan;
import com.intentreactor.api.SessionState;
import com.intentreactor.api.StepType;
import com.intentreactor.api.ToolProvider;
import com.intentreactor.core.config.IntentReactorProperties;
import com.intentreactor.strategies.config.StrategiesProperties;
import com.intentreactor.strategies.search.GraphOfThoughtsPlanner;
import com.intentreactor.strategies.search.ThoughtGraph;
import com.intentreactor.strategies.search.TreeOfThoughtsPlanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SearchPlannersTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ChatClient chatClient;
    @Mock
    private ToolProvider toolProvider;

    private SessionState session;
    private IntentAnalysisResult intent;
    private StrategiesProperties props;
    private IntentReactorProperties intentReactorProperties;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        session = new SessionState("test-session");
        props = new StrategiesProperties();
        intentReactorProperties = new IntentReactorProperties();
        objectMapper = new ObjectMapper();
        when(toolProvider.getAvailableTools(any())).thenReturn(List.of());
        Intent i = new Intent("solve the problem", 1.0, Map.of());
        intent = new IntentAnalysisResult();
        intent.setIntents(List.of(i));
    }

    @Test
    void tot_createsTreeOnFirstCall() {
        when(chatClient.prompt(any(Prompt.class)).call().content()).thenReturn(
                "[\"First thought\", \"Second thought\"]",
                "{\"score\": 0.6, \"reason\": \"ok\", \"done\": false, \"final_answer\": null}",
                "{\"score\": 0.7, \"reason\": \"better\", \"done\": false, \"final_answer\": null}"
        );

        TreeOfThoughtsPlanner planner = new TreeOfThoughtsPlanner(chatClient, toolProvider,
                objectMapper, props, intentReactorProperties);
        Plan plan = planner.plan(session, intent);

        assertThat(session.getAttributes()).containsKey("tot_tree");
        assertThat(plan.steps()).isNotEmpty();
    }

    @Test
    void tot_returnsDoneWhenThoughtIsTerminal() {
        when(chatClient.prompt(any(Prompt.class)).call().content()).thenReturn(
                "[\"The answer is 42\"]",
                "{\"score\": 0.99, \"reason\": \"perfect\", \"done\": true, \"final_answer\": \"The answer is 42\"}"
        );

        TreeOfThoughtsPlanner planner = new TreeOfThoughtsPlanner(chatClient, toolProvider,
                objectMapper, props, intentReactorProperties);
        Plan plan = planner.plan(session, intent);

        assertThat(plan.steps().get(0).type()).isEqualTo(StepType.DONE);
        assertThat(plan.steps().get(0).description()).contains("42");
    }

    @Test
    void got_initialGraphContainsRoot() {
        when(chatClient.prompt(any(Prompt.class)).call().content()).thenReturn(
                "{\"operation\": \"GENERATE\", \"source_ids\": [], \"content\": \"First idea\", " +
                        "\"score\": null, \"done\": false, \"final_answer\": null}"
        );

        GraphOfThoughtsPlanner planner = new GraphOfThoughtsPlanner(chatClient, toolProvider,
                objectMapper, props, intentReactorProperties);
        planner.plan(session, intent);

        assertThat(session.getAttributes()).containsKey("got_graph");
    }

    @Test
    void got_returnsDoneWhenFinalAnswerPresent() {
        when(chatClient.prompt(any(Prompt.class)).call().content()).thenReturn(
                "{\"operation\": \"GENERATE\", \"source_ids\": [], \"content\": \"Solution\", " +
                        "\"score\": null, \"done\": true, \"final_answer\": \"Final solution found\"}"
        );

        GraphOfThoughtsPlanner planner = new GraphOfThoughtsPlanner(chatClient, toolProvider,
                objectMapper, props, intentReactorProperties);
        Plan plan = planner.plan(session, intent);

        assertThat(plan.steps().get(0).type()).isEqualTo(StepType.DONE);
        assertThat(plan.steps().get(0).description()).contains("Final solution");
    }

    @Test
    void got_stopsAtMaxOperations() {
        props.getGot().setMaxOperations(0);
        when(chatClient.prompt(any(Prompt.class)).call().content()).thenReturn(
                "{\"operation\": \"GENERATE\", \"source_ids\": [], \"content\": \"idea\", " +
                        "\"score\": null, \"done\": false, \"final_answer\": null}"
        );

        // Pre-seed graph with operationCount at limit
        ThoughtGraph graph = ThoughtGraph.withRoot("solve the problem");
        graph.setOperationCount(0); // already at max
        session.getAttributes().put("got_graph", graph);

        GraphOfThoughtsPlanner planner = new GraphOfThoughtsPlanner(chatClient, toolProvider,
                objectMapper, props, intentReactorProperties);
        Plan plan = planner.plan(session, intent);

        assertThat(plan.steps().get(0).type()).isEqualTo(StepType.DONE);
    }
}
