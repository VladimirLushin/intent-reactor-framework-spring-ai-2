package com.intentreactor.core.config;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import com.intentreactor.api.Action;
import com.intentreactor.api.ConfirmationManager;
import com.intentreactor.api.IntentPreprocessor;
import com.intentreactor.api.IntentReactorService;
import com.intentreactor.api.MessageContextPostProcessor;
import com.intentreactor.api.MessageContextPreProcessor;
import com.intentreactor.api.MultiIntentStrategy;
import com.intentreactor.api.PlanStep;
import com.intentreactor.api.Planner;
import com.intentreactor.api.PromptContextProvider;
import com.intentreactor.api.SimpleAction;
import com.intentreactor.api.SimplePlanStep;
import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolProvider;
import com.intentreactor.core.event.IntentReactorEventLogger;
import com.intentreactor.core.planner.DefaultReACTPlanner;
import com.intentreactor.core.planner.LATSPlanner;
import com.intentreactor.core.planner.MessageCompressor;
import com.intentreactor.core.planner.ReflexionPlanner;
import com.intentreactor.core.planner.search.DefaultSearchTree;
import com.intentreactor.core.planner.search.SearchTree;
import com.intentreactor.core.preprocessor.DefaultIntentPreprocessor;
import com.intentreactor.core.service.DefaultConfirmationManager;
import com.intentreactor.core.service.IntentReactorServiceImpl;
import com.intentreactor.core.service.multiintent.LlmDrivenMultiIntentStrategy;
import com.intentreactor.core.service.multiintent.ParallelMultiIntentStrategy;
import com.intentreactor.core.service.multiintent.SequentialMultiIntentStrategy;
import com.intentreactor.core.session.FileSystemSessionRepository;
import com.intentreactor.core.session.SessionStateStore;
import com.intentreactor.core.tool.DefaultToolProvider;
import com.intentreactor.core.tool.SpringAiToolsCollector;
import com.intentreactor.core.util.PromptLoader;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionRepository;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Spring Boot auto-configuration that wires the core IntentReactor beans: service, planners
 * (react/reflexion/lats), preprocessor, session store, tool provider, confirmation manager,
 * event logger, and optional Micrometer decorator.
 */
@AutoConfiguration
// Name-based on purpose (no compile dependency on their artifact): the spring-ai-session
// JDBC auto-configuration declares an unconditional JdbcSessionRepository bean
// (@ConditionalOnMissingBean derived from the return type, so it never backs off for our
// in-memory/filesystem fallbacks). Parsing us first would yield two SessionRepository beans
// and a NoUniqueBeanDefinitionException; Boot ignores the name when the class is absent.
@AutoConfigureAfter(name = "org.springaicommunity.session.jdbc.autoconfigure.JdbcSessionRepositoryAutoConfiguration")
@EnableConfigurationProperties(IntentReactorProperties.class)
public class IntentReactorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper intentReactorObjectMapper() {
        // java.time is handled natively by Jackson 3 databind (no JavaTimeModule needed).
        // Register SearchTree polymorphic type info for session serialization.
        return JsonMapper.builder()
                .addModule(new SimpleModule()
                        .addAbstractTypeMapping(SearchTree.class, DefaultSearchTree.class)
                        .addAbstractTypeMapping(PlanStep.class, SimplePlanStep.class)
                        .addAbstractTypeMapping(Action.class, SimpleAction.class))
                .build();
    }

    @Bean
    @ConditionalOnClass(name = "org.apache.hc.client5.http.impl.classic.CloseableHttpClient")
    @ConditionalOnMissingBean(RestClientCustomizer.class)
    public RestClientCustomizer apacheHttp5ClientCustomizer() {
        return builder -> builder.requestFactory(new HttpComponentsClientHttpRequestFactory());
    }

    @Bean
    @ConditionalOnMissingBean(ChatClient.class)
    public ChatClient intentReactorChatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder.build();
    }

    @Bean
    @ConditionalOnMissingBean(ConfirmationManager.class)
    public ConfirmationManager confirmationManager(IntentReactorProperties properties) {
        return new DefaultConfirmationManager(properties);
    }

    // ---- Context compression (opt-in) ----

    @Bean
    @ConditionalOnMissingBean(MessageCompressor.class)
    @ConditionalOnProperty(
            prefix = "intent-reactor.planning.context-window.compression",
            name = "enabled",
            havingValue = "true")
    public MessageCompressor messageCompressor(ChatClient chatClient,
                                               IntentReactorProperties properties,
                                               ApplicationEventPublisher eventPublisher) {
        return new MessageCompressor(chatClient, properties, new PromptLoader(), eventPublisher);
    }

    // ---- ReACT planner (default) ----

    @Bean
    @ConditionalOnMissingBean(Planner.class)
    @ConditionalOnProperty(prefix = "intent-reactor.planning", name = "strategy",
            havingValue = "react", matchIfMissing = true)
    public Planner reactPlanner(ChatClient chatClient,
                                ToolProvider toolProvider,
                                IntentReactorProperties properties,
                                ObjectMapper objectMapper,
                                @Autowired(required = false) List<PromptContextProvider> promptContextProviders,
                                @Autowired(required = false) List<MessageContextPreProcessor> preProcessors,
                                @Autowired(required = false) List<MessageContextPostProcessor> postProcessors,
                                @Autowired(required = false) io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        List<PromptContextProvider> providers = promptContextProviders != null ? promptContextProviders : List.of();
        List<MessageContextPreProcessor> pre = preProcessors != null ? preProcessors : List.of();
        List<MessageContextPostProcessor> post = postProcessors != null ? postProcessors : List.of();
        Planner p = new DefaultReACTPlanner(chatClient, toolProvider, properties, objectMapper, providers, pre, post);
        return meterRegistry != null ? wrapWithMetrics(p, "react", meterRegistry) : p;
    }

    // ---- Reflexion planner ----

    @Bean
    @ConditionalOnMissingBean(Planner.class)
    @ConditionalOnProperty(prefix = "intent-reactor.planning", name = "strategy", havingValue = "reflexion")
    public Planner reflexionPlanner(ChatClient chatClient,
                                    ToolProvider toolProvider,
                                    IntentReactorProperties properties,
                                    ObjectMapper objectMapper,
                                    @Autowired(required = false) List<PromptContextProvider> promptContextProviders,
                                    @Autowired(required = false) List<MessageContextPreProcessor> preProcessors,
                                    @Autowired(required = false) List<MessageContextPostProcessor> postProcessors,
                                    @Autowired(required = false) io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        List<PromptContextProvider> providers = promptContextProviders != null ? promptContextProviders : List.of();
        List<MessageContextPreProcessor> pre = preProcessors != null ? preProcessors : List.of();
        List<MessageContextPostProcessor> post = postProcessors != null ? postProcessors : List.of();
        Planner delegate = new DefaultReACTPlanner(chatClient, toolProvider, properties, objectMapper, providers, pre, post);
        Planner p = new ReflexionPlanner(delegate, chatClient, properties);
        return meterRegistry != null ? wrapWithMetrics(p, "reflexion", meterRegistry) : p;
    }

    // ---- LATS planner (does not use buildMessages, no processors needed) ----

    @Bean
    @ConditionalOnMissingBean(Planner.class)
    @ConditionalOnProperty(prefix = "intent-reactor.planning", name = "strategy", havingValue = "lats")
    public Planner latsPlanner(ChatClient chatClient,
                               ToolProvider toolProvider,
                               IntentReactorProperties properties,
                               ObjectMapper objectMapper,
                               @Autowired(required = false) List<PromptContextProvider> promptContextProviders,
                               @Autowired(required = false) io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        List<PromptContextProvider> providers = promptContextProviders != null ? promptContextProviders : List.of();
        Planner p = new LATSPlanner(chatClient, toolProvider, properties, objectMapper, providers);
        return meterRegistry != null ? wrapWithMetrics(p, "lats", meterRegistry) : p;
    }

    // ---- Multi-intent strategies ----

    @Bean
    @ConditionalOnMissingBean(name = "intentReactorParallelExecutor")
    public ExecutorService intentReactorParallelExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "intent-reactor-parallel-" + UUID.randomUUID().toString().substring(0, 8));
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    public SequentialMultiIntentStrategy sequentialMultiIntentStrategy(SessionStateStore sessionStore) {
        return new SequentialMultiIntentStrategy(sessionStore);
    }

    @Bean
    public LlmDrivenMultiIntentStrategy llmDrivenMultiIntentStrategy(
            SequentialMultiIntentStrategy sequentialMultiIntentStrategy,
            ChatClient chatClient,
            IntentReactorProperties properties,
            ObjectMapper objectMapper) {
        return new LlmDrivenMultiIntentStrategy(sequentialMultiIntentStrategy, chatClient, properties, objectMapper);
    }

    @Bean
    public ParallelMultiIntentStrategy parallelMultiIntentStrategy(
            ExecutorService intentReactorParallelExecutor,
            IntentReactorProperties properties) {
        return new ParallelMultiIntentStrategy(intentReactorParallelExecutor, properties);
    }

    // ---- Main service ----

    @Bean
    @ConditionalOnMissingBean(IntentReactorService.class)
    public IntentReactorService intentReactorService(IntentPreprocessor preprocessor,
                                                     Planner planner,
                                                     SessionStateStore sessionStore,
                                                     ToolProvider toolProvider,
                                                     ApplicationEventPublisher eventPublisher,
                                                     IntentReactorProperties properties,
                                                     ConfirmationManager confirmationManager,
                                                     ObjectMapper objectMapper,
                                                     List<MultiIntentStrategy> multiIntentStrategies) {
        return new IntentReactorServiceImpl(preprocessor, planner, sessionStore,
                toolProvider, eventPublisher, properties, confirmationManager,
                objectMapper, multiIntentStrategies);
    }

    // ---- Core infrastructure beans (declared explicitly — @ComponentScan is not allowed on @AutoConfiguration) ----

    @Bean
    @ConditionalOnMissingBean(IntentPreprocessor.class)
    public IntentPreprocessor defaultIntentPreprocessor(ChatClient chatClient,
                                                        ObjectMapper objectMapper,
                                                        IntentReactorProperties properties) {
        return new DefaultIntentPreprocessor(chatClient, objectMapper, properties);
    }

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

    @Bean
    @ConditionalOnMissingBean(SessionStateStore.class)
    public SessionStateStore sessionStateStore(SessionRepository sessionRepository,
                                               ObjectMapper objectMapper) {
        return new SessionStateStore(sessionRepository, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(SessionRepository.class)
    @ConditionalOnProperty(prefix = "intent-reactor.session", name = "store",
            havingValue = "in-memory", matchIfMissing = true)
    public SessionRepository inMemorySessionRepository() {
        return InMemorySessionRepository.builder().build();
    }

    @Bean
    @ConditionalOnMissingBean(SessionRepository.class)
    @ConditionalOnProperty(prefix = "intent-reactor.session", name = "store",
            havingValue = "filesystem")
    public SessionRepository fileSystemSessionRepository(IntentReactorProperties properties,
                                                         ObjectMapper objectMapper) {
        return new FileSystemSessionRepository(properties, objectMapper);
    }

    // ---- Default event logger ----

    @Bean
    @ConditionalOnMissingBean(IntentReactorEventLogger.class)
    @ConditionalOnProperty(prefix = "intent-reactor.logging", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public IntentReactorEventLogger intentReactorEventLogger() {
        return new IntentReactorEventLogger();
    }

    // ---- Micrometer helper ----

    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    private Planner wrapWithMetrics(Planner planner, String strategy,
                                    io.micrometer.core.instrument.MeterRegistry registry) {
        return new com.intentreactor.core.planner.MicrometerPlannerDecorator(planner, registry, strategy);
    }
}
