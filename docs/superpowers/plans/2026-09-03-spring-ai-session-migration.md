# IntentReactor: миграция session-слоя на spring-ai-session — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Заменить собственный session-слой IntentReactor (модули `intent-reactor-session-jdbc`/`-jpa`, `InMemorySessionStore`/`FileSystemSessionStore`, интерфейс `api.SessionStore`) на адаптер поверх `org.springaicommunity:spring-ai-session:0.8.0` без изменения публичного контракта движка (`SessionState`, `Message`, SPIs планировщиков), с удалением старых модулей, версией 0.2.0 и зеркальной правкой документации EN/RU.

**Architecture:** Движок сохраняет контракт `SessionState` (messages — строго append-only, planState+attributes). Persistence — через стандартный SPI `org.springframework.ai.session.SessionRepository`: история — по одному `SessionEvent` на сообщение; `planState`/`attributes`/`createdAt`/`updatedAt` — одним вложенным envelope-объектом в `Session.metadata` под ключом `com.intentreactor.state`. Новый фасад core `SessionStateStore` (findById/save/delete) выполняет маппинг; `FileSystemSessionRepository` (наш кастомный `SessionRepository`) заменяет файловый стор с авто-конверсией legacy-файлов. Сохранение новых сообщений — append по дельте от `getEventVersion(sessionId)` (без дублей при повторных save того же экземпляра).

**Tech Stack:** Maven (без wrapper), JDK 25 локально (target 21), Spring Boot 4.0.8, Spring AI 2.0.1, Jackson 3 (`tools.jackson`), `org.springaicommunity:spring-ai-session:0.8.0` (+ BOM `spring-ai-session-bom`), их `spring-ai-session-jdbc` в test-scope core, H2. Команды из корня реактора; CRLF рабочей копии сохранять (git нормализует при коммите).

**Spec:** `docs/superpowers/specs/2026-09-03-spring-ai-session-migration-design.md` (читать вместе с планом).

## Global Constraints

- **Публичный контракт движка не меняется**: классы `api.SessionState`, `api.Message`, `api.PlanState`, события, интерфейсы планировщиков — без изменений семантики. Меняются только: удаление `api.SessionStore` и хранилищ; тип session-фасада, который инжектят core/sand-train; свойства.
- **spring-ai-session 0.8.0**: BOM импортируется в корневой `dependencyManagement`; core получает compile-зависимость `org.springaicommunity:spring-ai-session`; в test-scope core добавляются `spring-ai-session-jdbc` + `com.h2database:h2`. Их артефакты в версиях не фиксировать вручную (кроме BOM) — версии из BOM.
- **Свойства**: `intent-reactor.session.store` → только `in-memory` (default) | `filesystem`; удалить `intent-reactor.session.jdbc.*`; `intent-reactor.session.filesystem.path` остаётся.
- **Правило приоритета бинов**: любой внешний `SessionRepository` (их JDBC из starter, кастомный пользователя) побеждает наши fallback-бины (`@ConditionalOnMissingBean(SessionRepository.class)` на обоих наших).
- **userId = sessionId**, `expiresAt = null` у создаваемых `Session` (семантика «без истечения» сохранена).
- Сообщения движка строго append-only — события никогда не удаляются и не архивируются (компакция вне объёма; движок сжимает контекст только при сборке промпта).
- Механика правок: файлы менять инструментом edit (точные замены), не переписывая файл целиком; `Message`-и и остальные api-классы не трогать кроме javadoc-правок, перечисленных в Task 4.
- Пакеты модулей: core — `com.intentreactor.core.session`; sand-train — как есть.
- Полный прогон: `mvn test`. Один модуль: `mvn -pl <module> -am -Dtest=<ClassName> test`. Локально JDK 25: при необходимости `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot"`, `export PATH="/c/Users/lusha/tools/apache-maven-3.9.16/bin:$PATH"`.
- **Источник истины по API 0.8.0 — Task 1 (javap)**; если фактическая сигнатура отличается от приведённой в задачах — применить минимальную адаптацию и **записать фактическую сигнатуру в секцию «Фактические API 0.8.0» в конце этого файла**. При сомнении в выборе — остановиться и спросить владельца.
- Ветка работы: `migration/spring-ai-session`. Git identity настроен локально (Лушин Владимир).

---

### Task 0: Тулчейн и зелёный baseline

**Files:** нет изменений.

**Interfaces:** — (подготовительная)

- [ ] **Step 1: Проверить тулчейн**

Run: `java -version`, `mvn -v`
Expected: OpenJDK 25 (Temurin), Maven 3.9.16. Если не подхватились — выполнить экспорт из Global Constraints.

- [ ] **Step 2: Baseline**

Run: `mvn test`
Expected: `BUILD SUCCESS`. Падения до начала работ разобрать (ошибки окружения).

- [ ] **Step 3: Создать рабочую ветку**

```bash
git switch -c migration/spring-ai-session
```

---

### Task 1: Pom-слой (BOM + зависимость core) и прозвон API 0.8.0

**Files:**
- Modify: `pom.xml` (dependencyManagement + modules — только добавление BOM)
- Modify: `intent-reactor-core/pom.xml` (compile-зависимость)
- Modify: `docs/superpowers/plans/2026-09-03-spring-ai-session-migration.md` (секция «Фактические API 0.8.0», в конец)

**Interfaces:** после задачи резолвится `org.springframework.ai.session.*` на classpath core; компиляция реактора остаётся зелёной. Дальнейшие задачи используют фактические сигнатуры из секции «Фактические API 0.8.0».

- [ ] **Step 1: Импорт BOM в корневой dependencyManagement**

Edit `pom.xml`: внутри `<dependencyManagement><dependencies>` добавить (после import BOM Boot):

```xml
        <dependency>
            <groupId>org.springaicommunity</groupId>
            <artifactId>spring-ai-session-bom</artifactId>
            <version>0.8.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
```

- [ ] **Step 2: Compile-зависимость core**

Edit `intent-reactor-core/pom.xml`: в `<dependencies>` (рядом с spring-ai зависимостями) добавить:

```xml
        <dependency>
            <groupId>org.springaicommunity</groupId>
            <artifactId>spring-ai-session</artifactId>
        </dependency>
```

- [ ] **Step 3: Проверить, что реактор собирается**

Run: `mvn -pl intent-reactor-core -am -DskipTests compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Прозвон фактических сигнатур API 0.8.0 (javap)**

Jar лежит в локальном репозитории: `$HOME/.m2/repository/org/springaicommunity/spring-ai-session/0.8.0/spring-ai-session-0.8.0.jar`. Выполнить и зафиксировать вывод:

```bash
javap -cp "$HOME/.m2/repository/org/springaicommunity/spring-ai-session/0.8.0/spring-ai-session-0.8.0.jar" org.springframework.ai.session.Session org.springframework.ai.session.SessionEvent org.springframework.ai.session.SessionRepository org.springframework.ai.session.InMemorySessionRepository org.springframework.ai.session.EventFilter
```

Дополнительно проверить конструкторы Spring AI Message (уже на classpath core):
`javap org.springframework.ai.chat.messages.UserMessage org.springframework.ai.chat.messages.AssistantMessage org.springframework.ai.chat.messages.SystemMessage` (класс-пат core через `mvn -pl intent-reactor-core -am dependency:build-classpath -Dmdep.outputFile=target/cp.txt` или javap из jar spring-ai-model 2.0.1 в `~/.m2`).

Expected (по исследованию 0.8.0; сверить факт):
- `SessionEvent.builder()` c полями `id/sessionId/timestamp/message/metadata/branch/archived` и `isArchived()/getId()/getSessionId()/getTimestamp()/getMessage()/getMetadata()`.
- `Session.builder()` c `id/userId/createdAt/expiresAt/metadata` и `id()/userId()/createdAt()/expiresAt()/metadata()`.
- `SessionRepository`: `save/findById/findByUserId/findExpiredSessionIds/delete/appendEvent/compactEvents/getEventVersion/findEvents`.
- `InMemorySessionRepository.builder().build()`.
- `EventFilter.all()`.

Если расхождения — минимальная адаптация в последующих задачах.

- [ ] **Step 5: Проверка совместимости их авто-конфигурации с Boot 4.0.8 (для Task 7 и доков)**

Зависимость `spring-boot-autoconfigure` 4.0.8 уже в reactor. Проверить наличие классов инициализации схемы:

```bash
javap -cp "$HOME/.m2/repository/org/springframework/boot/spring-boot/4.0.8/spring-boot-4.0.8.jar" org.springframework.boot.sql.autoconfigure.init.OnDatabaseInitializationCondition 2>&1 | head -5
javap -cp "$HOME/.m2/repository/org/springframework/boot/spring-boot/4.0.8/spring-boot-4.0.8.jar" org.springframework.boot.sql.autoconfigure.init.DatabaseInitializationProperties 2>&1 | head -5
```

Expected (решение для доков Task 9): классы **есть** → в доках описываем штатную инициализацию схемы через их starter; **нет** → в доках описываем ручное создание таблиц по их `schema-@@platform@@.sql` (ресурсы внутри их jdbc-артефакта), в Task 7 инициализация всегда ручная (см. шаг задачи).

- [ ] **Step 6: Записать фактические сигнатуры в конец плана и закоммитить**

Дописать в конец файла `docs/superpowers/plans/2026-09-03-spring-ai-session-migration.md` секцию:

```markdown
## Фактические API spring-ai-session 0.8.0 (Task 1 probe)

(вывод javap + выводы по Boot 4.0.8: что доступно, что нет; решения по адаптации)
```

```bash
git add pom.xml intent-reactor-core/pom.xml "docs/superpowers/plans/2026-09-03-spring-ai-session-migration.md"
git commit -m "Add spring-ai-session BOM and core dependency (0.8.0)"
```

---

### Task 2: `SessionEventCodec` + `SessionStateStore` (маппинг поверх их in-memory репозитория)

**Files:**
- Create: `intent-reactor-core/src/main/java/com/intentreactor/core/session/SessionEventCodec.java`
- Create: `intent-reactor-core/src/main/java/com/intentreactor/core/session/SessionStateStore.java`
- Test: `intent-reactor-core/src/test/java/com/intentreactor/core/session/SessionStateStoreTest.java`

**Interfaces:**
- Consumes: `api.SessionState`, `api.Message`, `api.PlanState`; `org.springframework.ai.session.{Session,SessionEvent,SessionRepository,InMemorySessionRepository,EventFilter}`; `tools.jackson.databind.ObjectMapper`.
- Produces: `SessionStateStore` — публичный класс core, бин:
  - `Optional<SessionState> findById(String sessionId)`
  - `void save(SessionState sessionState)`
  - `void delete(String sessionId)`
  - ctor `SessionStateStore(SessionRepository sessionRepository, ObjectMapper objectMapper)`
  - константа `public static final String METADATA_KEY = "com.intentreactor.state"`
  - package-private статические методы для `FileSystemSessionRepository` (Task 3): `SessionEventCodec.messageToEvent(String sessionId, Message message)` и `SessionEventCodec.eventToMessage(SessionEvent event)`; `SessionStateStore.encodeState(SessionState)` → `Map<String,Object>`; `SessionStateStore.applyDecodedState(SessionState target, Object rawEnvelope)`.
- Ключи реидратации (package-private константы в `SessionStateStore`): `"multiIntentState"`, `"originalIntent"`, `"searchTree"`, `"sand_training_log"` — см. код ниже.

**Модель данных (зафиксировать, это контракт для Task 3/7):**
- Envelope — **обычный вложенный `Map<String,Object>`** под `METADATA_KEY` в `Session.metadata` (НЕ строка): ключи `"planState"` (объект `PlanState`), `"attributes"` (та же карта, что `SessionState.getAttributes()`), `"createdAt"`, `"updatedAt"` (значения `LocalDateTime`).
- Для in-memory репозитория envelope хранится как есть (живые объекты — сохраняется прежняя семантика in-memory стора). Для сериализующих репозиториев (JDBC, filesystem) значения пройдут через их/наш JSON: при чтении `planState` и типизированные значения атрибутов приходят картами — `applyDecodedState` восстанавливает типы через наш `ObjectMapper.convertValue`.
- Событие на сообщение: SA-тип по роли (`USER`→`UserMessage`, `ASSISTANT`→`AssistantMessage`, `SYSTEM`→`SystemMessage`); в `metadata` события: `"com.intentreactor.pinned"` (boolean) и `"com.intentreactor.ts"` (ISO-строка `LocalDateTime` исходного сообщения). Восстановление роли — по типу SA-сообщения (`ToolResponseMessage` и прочие → `SYSTEM`).

- [ ] **Step 1: Написать падающий тест `SessionStateStoreTest`**

Файл теста (пакет `com.intentreactor.core.session`, тот же, что и классы — доступ к package-private):

```java
package com.intentreactor.core.session;

import com.intentreactor.api.Message;
import com.intentreactor.api.PlanState;
import com.intentreactor.api.PlanStatus;
import com.intentreactor.api.PlanStep;
import com.intentreactor.api.SimpleAction;
import com.intentreactor.api.SimplePlanStep;
import com.intentreactor.api.SessionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionEvent;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SessionStateStoreTest {

    private SessionStateStore store;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = JsonMapper.builder()
                .addModule(new SimpleModule()
                        .addAbstractTypeMapping(PlanStep.class, SimplePlanStep.class))
                .build();
        store = new SessionStateStore(InMemorySessionRepository.builder().build(), mapper);
    }

    private SessionState sessionWith(String id, Message... messages) {
        SessionState s = new SessionState(id);
        for (Message m : messages) s.addMessage(m);
        return s;
    }

    @Test
    void saveAndFindRoundTripsMessagesStateAndAttributes() {
        SessionState s = sessionWith("s1",
                Message.pinnedUser("first goal"),
                Message.system("[TOOL_RESULT] tool: {\"ok\":true}"),
                Message.assistant("done"));
        s.getAttributes().put("appKey", "appValue");
        s.getAttributes().put("count", 42);
        PlanState plan = new PlanState("goal-1");
        plan.setStatus(PlanStatus.COMPLETED);
        s.setPlanState(plan);
        s.getPlanState().getCompletedSteps().add(
                SimplePlanStep.act(new SimpleAction("t1", Map.of()), "desc", false));

        store.save(s);

        Optional<SessionState> loaded = store.findById("s1");
        assertThat(loaded).isPresent();
        SessionState r = loaded.get();
        assertThat(r.getMessages()).extracting(Message::getRole)
                .containsExactly(Message.Role.USER, Message.Role.SYSTEM, Message.Role.ASSISTANT);
        assertThat(r.getMessages().get(0).isPinned()).isTrue();
        assertThat(r.getMessages().get(0).getTimestamp()).isEqualTo(s.getMessages().get(0).getTimestamp());
        assertThat(r.getMessages()).extracting(Message::getContent)
                .containsExactly("first goal", "[TOOL_RESULT] tool: {\"ok\":true}", "done");
        assertThat(r.getAttributes().get("appKey")).isEqualTo("appValue");
        assertThat(r.getAttributes().get("count")).isEqualTo(42);
        assertThat(r.getPlanState().getGoalDescription()).isEqualTo("goal-1");
        assertThat(r.getPlanState().getStatus()).isEqualTo(PlanStatus.COMPLETED);
        assertThat(r.getPlanState().getCompletedSteps()).hasSize(1);
        assertThat(r.getPlanState().getCompletedSteps().get(0)).isInstanceOf(PlanStep.class);
        assertThat(r.getCreatedAt()).isEqualTo(s.getCreatedAt());
        assertThat(r.getUpdatedAt()).isEqualTo(s.getUpdatedAt());
    }

    @Test
    void repeatedSavesDoNotDuplicateMessages() {
        SessionState s = sessionWith("s2", Message.user("hi"));
        store.save(s);
        store.save(s);
        s.addMessage(Message.assistant("answer"));
        store.save(s);
        store.save(s);
        assertThat(store.findById("s2").orElseThrow().getMessages()).hasSize(2);
    }

    @Test
    void freshStateFromMissingSessionAppendsAllMessagesOnFirstSave() {
        SessionState s = new SessionState("s3"); // not loaded via findById
        s.addMessage(Message.user("q"));
        store.save(s);
        s.addMessage(Message.assistant("a"));
        store.save(s);
        assertThat(store.findById("s3").orElseThrow().getMessages()).hasSize(2);
    }

    @Test
    void missingSessionReturnsEmpty() {
        assertThat(store.findById("nope")).isEmpty();
    }

    @Test
    void deleteRemovesSession() {
        SessionState s = sessionWith("s4", Message.user("x"));
        store.save(s);
        store.delete("s4");
        assertThat(store.findById("s4")).isEmpty();
    }

    @Test
    void foreignEventDecodesByMessageType() {
        InMemorySessionRepository repo = InMemorySessionRepository.builder().build();
        SessionStateStore st = new SessionStateStore(repo, mapper);
        st.save(sessionWith("s5", Message.user("seed")));
        // simulated foreign (spring-ai-session native) event:
        repo.appendEvent(SessionEvent.builder()
                .id("foreign-1")
                .sessionId("s5")
                .message(new UserMessage("hello from ecosystem"))
                .metadata(Map.of())
                .build());
        List<Message> msgs = st.findById("s5").orElseThrow().getMessages();
        assertThat(msgs).hasSize(2);
        assertThat(msgs.get(1).getRole()).isEqualTo(Message.Role.USER);
        assertThat(msgs.get(1).getContent()).isEqualTo("hello from ecosystem");
    }

    @Test
    void typedAttributeValuesSurviveInMemoryRoundTrip() {
        SessionState s = sessionWith("s6", Message.user("multi"));
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("currentIntent", "it1");
        s.getAttributes().put("multiIntentState", ctx);
        store.save(s);
        // In-memory path keeps live objects: rehydrate must not replace the map
        Object v = store.findById("s6").orElseThrow().getAttributes().get("multiIntentState");
        assertThat(v).isEqualTo(ctx);
    }

    @Test
    void sessionWithNoMessagesAndAttributesOnly() {
        SessionState s = new SessionState("s7");
        s.getAttributes().put("only", "attr");
        store.save(s);
        SessionState r = store.findById("s7").orElseThrow();
        assertThat(r.getMessages()).isEmpty();
        assertThat(r.getAttributes().get("only")).isEqualTo("attr");
    }
}
```

Примечание: тест `typedAttributeValuesSurviveInMemoryRoundTrip` отражает решение «in-memory = живые объекты» (а не JSON-копию). `saveAndFindRoundTripsMessagesStateAndAttributes` использует `LocalDateTime`-сравнения — таймстемпы сообщений и createdAt/updatedAt должны восстанавливаться точно.

- [ ] **Step 2: Убедиться, что тест не компилируется/падает**

Run: `mvn -pl intent-reactor-core -am -Dtest=SessionStateStoreTest test`
Expected: FAIL (классы `SessionStateStore`/`SessionEventCodec` не существуют).

- [ ] **Step 3: Реализовать `SessionEventCodec`**

Create `intent-reactor-core/src/main/java/com/intentreactor/core/session/SessionEventCodec.java`:

```java
package com.intentreactor.core.session;

import com.intentreactor.api.Message;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.SessionEvent;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mapping between the framework's {@link Message} history model and spring-ai-session
 * {@link SessionEvent}s. Kept in the same package so both {@link SessionStateStore} and
 * {@link FileSystemSessionRepository} (legacy file conversion) can reuse it.
 */
final class SessionEventCodec {

    static final String PINNED_KEY = "com.intentreactor.pinned";
    static final String TIMESTAMP_KEY = "com.intentreactor.ts";

    private SessionEventCodec() {
    }

    static SessionEvent messageToEvent(String sessionId, Message message) {
        org.springframework.ai.chat.messages.Message aiMessage = switch (message.getRole()) {
            case USER -> new UserMessage(message.getContent());
            case ASSISTANT -> new AssistantMessage(message.getContent());
            case SYSTEM -> new SystemMessage(message.getContent());
        };
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(PINNED_KEY, message.isPinned());
        LocalDateTime ts = message.getTimestamp();
        metadata.put(TIMESTAMP_KEY, ts.toString());
        return SessionEvent.builder()
                .id(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .timestamp(toInstant(ts))
                .message(aiMessage)
                .metadata(metadata)
                .build();
    }

    static Message eventToMessage(SessionEvent event) {
        org.springframework.ai.chat.messages.Message ai = event.getMessage();
        Message message;
        if (ai instanceof UserMessage) {
            message = Boolean.TRUE.equals(event.getMetadata().get(PINNED_KEY))
                    ? Message.pinnedUser(ai.getText())
                    : Message.user(ai.getText());
        } else if (ai instanceof AssistantMessage) {
            message = Message.assistant(ai.getText());
        } else {
            // SystemMessage, ToolResponseMessage and any other type map to our SYSTEM role
            message = Message.system(ai.getText());
        }
        Object ts = event.getMetadata().get(TIMESTAMP_KEY);
        if (ts instanceof String s) {
            try {
                message.setTimestamp(LocalDateTime.parse(s));
            } catch (Exception ignored) {
                // keep the factory timestamp
            }
        }
        return message;
    }

    static Instant toInstant(LocalDateTime localDateTime) {
        return localDateTime.atZone(ZoneId.systemDefault()).toInstant();
    }

    static LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
```

- [ ] **Step 4: Реализовать `SessionStateStore`**

Create `intent-reactor-core/src/main/java/com/intentreactor/core/session/SessionStateStore.java`:

```java
package com.intentreactor.core.session;

import com.intentreactor.api.Message;
import com.intentreactor.api.PlanState;
import com.intentreactor.api.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Session facade of the execution engine over the spring-ai-session SPI.
 * <p>
 * History messages are stored as {@link SessionEvent}s (append-only; the engine never
 * removes messages). Planning state and attributes travel as one nested map under
 * {@value #METADATA_KEY} in {@link Session#metadata()}: in-memory repositories keep the
 * live objects (same semantics as the former in-memory store), serializing repositories
 * persist plain JSON which {@link #applyDecodedState} rehydrates with the configured
 * {@link ObjectMapper}.
 * <p>
 * Users plug custom persistence by providing their own {@link SessionRepository} bean —
 * it wins over the built-in in-memory/filesystem fallbacks (see auto-configuration).
 * <p>
 * Concurrency note: concurrent requests mutating the same session id are not supported
 * (same limitation as the previous whole-row save).
 */
public class SessionStateStore {

    public static final String METADATA_KEY = "com.intentreactor.state";

    private static final Logger log = LoggerFactory.getLogger(SessionStateStore.class);

    static final String PLAN_STATE_KEY = "planState";
    static final String ATTRIBUTES_KEY = "attributes";
    static final String CREATED_AT_KEY = "createdAt";
    static final String UPDATED_AT_KEY = "updatedAt";

    /** Framework attribute keys whose values are rehydrated from maps after a JSON round-trip. */
    private static final Map<String, Class<?>> REHYDRATABLE = new HashMap<>();

    static {
        REHYDRATABLE.put("multiIntentState", com.intentreactor.api.MultiIntentContext.class);
        REHYDRATABLE.put("originalIntent", com.intentreactor.api.IntentAnalysisResult.class);
        REHYDRATABLE.put("searchTree", com.intentreactor.core.planner.search.DefaultSearchTree.class);
    }

    private final SessionRepository sessionRepository;
    private final ObjectMapper objectMapper;

    public SessionStateStore(SessionRepository sessionRepository, ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
    }

    public Optional<SessionState> findById(String sessionId) {
        Session session = sessionRepository.findById(sessionId);
        if (session == null) return Optional.empty();
        List<SessionEvent> events = sessionRepository.findEvents(sessionId, EventFilter.all());
        SessionState state = new SessionState(sessionId);
        for (SessionEvent event : events) {
            state.addMessage(SessionEventCodec.eventToMessage(event));
        }
        applyDecodedState(state, session.metadata().get(METADATA_KEY));
        return Optional.of(state);
    }

    public void save(SessionState sessionState) {
        sessionState.touch();
        long version = sessionRepository.getEventVersion(sessionState.getId());
        Map<String, Object> metadata = Map.of(METADATA_KEY, encodeState(sessionState));
        Session existing = sessionRepository.findById(sessionState.getId());
        Session session = existing == null
                ? Session.builder().id(sessionState.getId()).userId(sessionState.getId())
                        .createdAt(Instant.now()).expiresAt(null).metadata(metadata).build()
                : Session.builder().id(existing.id()).userId(existing.userId())
                        .createdAt(existing.createdAt()).expiresAt(existing.expiresAt())
                        .metadata(metadata).build();
        sessionRepository.save(session);
        List<Message> messages = sessionState.getMessages();
        if (messages.size() > version) {
            for (int i = (int) version; i < messages.size(); i++) {
                sessionRepository.appendEvent(
                        SessionEventCodec.messageToEvent(sessionState.getId(), messages.get(i)));
            }
        }
    }

    public void delete(String sessionId) {
        sessionRepository.delete(sessionId);
    }

    /** Encodes the non-message part of a session as the envelope map stored in Session.metadata. */
    static Map<String, Object> encodeState(SessionState state) {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put(PLAN_STATE_KEY, state.getPlanState());
        envelope.put(ATTRIBUTES_KEY, state.getAttributes());
        envelope.put(CREATED_AT_KEY, state.getCreatedAt());
        envelope.put(UPDATED_AT_KEY, state.getUpdatedAt());
        return envelope;
    }

    /** Applies a decoded envelope (raw map from the repository) onto a freshly loaded state. */
    @SuppressWarnings("unchecked")
    private void applyDecodedState(SessionState state, Object rawEnvelope) {
        if (!(rawEnvelope instanceof Map<?, ?> envelope)) return;
        Object rawPlan = envelope.get(PLAN_STATE_KEY);
        if (rawPlan instanceof PlanState planState) {
            state.setPlanState(planState);
        } else if (rawPlan instanceof Map<?, ?>) {
            try {
                state.setPlanState(objectMapper.convertValue(rawPlan, PlanState.class));
            } catch (Exception e) {
                log.warn("Failed to rehydrate planState for session {}", state.getId(), e);
            }
        }
        Object rawAttrs = envelope.get(ATTRIBUTES_KEY);
        if (rawAttrs instanceof Map<?, ?> attrs) {
            for (Map.Entry<?, ?> entry : attrs.entrySet()) {
                if (!(entry.getKey() instanceof String key)) continue;
                Object value = entry.getValue();
                if (value instanceof Map<?, ?> && REHYDRATABLE.containsKey(key)) {
                    try {
                        value = objectMapper.convertValue(value, REHYDRATABLE.get(key));
                    } catch (Exception e) {
                        log.warn("Failed to rehydrate attribute {} for session {}", key, state.getId(), e);
                    }
                }
                state.getAttributes().put(key, value);
            }
        }
        state.setCreatedAt(coerceTimestamp(envelope.get(CREATED_AT_KEY), state.getCreatedAt()));
        state.setUpdatedAt(coerceTimestamp(envelope.get(UPDATED_AT_KEY), state.getUpdatedAt()));
    }

    private static LocalDateTime coerceTimestamp(Object raw, LocalDateTime fallback) {
        if (raw instanceof LocalDateTime ldt) return ldt;
        if (raw instanceof String s) {
            try {
                return LocalDateTime.parse(s);
            } catch (Exception ignored) {
                // fall through
            }
        }
        return fallback;
    }
}
```

- [ ] **Step 5: Прогнать тесты**

Run: `mvn -pl intent-reactor-core -am -Dtest=SessionStateStoreTest test`
Expected: PASS (все 8 тестов). При расхождении API 0.8.0 (например, имя метода билдера) — адаптировать по секции «Фактические API».

- [ ] **Step 6: Закоммитить**

```bash
git add intent-reactor-core/src/main/java/com/intentreactor/core/session/SessionEventCodec.java intent-reactor-core/src/main/java/com/intentreactor/core/session/SessionStateStore.java intent-reactor-core/src/test/java/com/intentreactor/core/session/SessionStateStoreTest.java
git commit -m "Add SessionStateStore facade over spring-ai-session SessionRepository"
```

---

### Task 3: `FileSystemSessionRepository` (кастомный `SessionRepository`, JSON-файлы) + legacy-конверсия

**Files:**
- Create: `intent-reactor-core/src/main/java/com/intentreactor/core/session/FileSystemSessionRepository.java`
- Create: `intent-reactor-core/src/test/java/com/intentreactor/core/session/FileSystemSessionRepositoryTest.java`
- Test: `intent-reactor-core/src/test/java/com/intentreactor/core/session/SessionStateStoreTest.java` (добавить 1 тест — см. Step 4)

**Interfaces:**
- Consumes: `SessionStateStore.encodeState`/`METADATA_KEY`, `SessionEventCodec`; `org.springframework.ai.session.SessionRepository` (реализуется полностью: `save/findById/findByUserId/findExpiredSessionIds/delete/appendEvent/compactEvents/getEventVersion/findEvents`); `SessionEvent`, `Session`; `IntentReactorProperties.SessionConfig.FileSystemSessionConfig` (`getPath()`); `ObjectMapper`.
- Produces: `FileSystemSessionRepository(IntentReactorProperties properties, ObjectMapper mapper)` — бин авто-конфигурации (Task 4); файловый формат (см. ниже) — контракт для тестов и доков.

**Формат файла** `<basePath>/<sessionId>.json` (JSON, пишется нашим `ObjectMapper`):

```json
{
  "session": { "id": "...", "userId": "...", "createdAt": "...", "expiresAt": null, "metadata": { "com.intentreactor.state": { "planState": {...}, "attributes": {...}, "createdAt": "...", "updatedAt": "..." } } },
  "events": [ { "id": "...", "sessionId": "...", "timestamp": "...", "messageType": "USER", "content": "...", "metadata": { "com.intentreactor.pinned": true, "com.intentreactor.ts": "..." }, "branch": null, "archived": false, "synthetic": false } ],
  "version": 2
}
```

`messageType` — имя нашего `Message.Role` (USER/ASSISTANT/SYSTEM). Legacy-файл v0.1.x = сериализованный `SessionState` (корень содержит `"messages"` и НЕ содержит `"session"`) — при первом `findById` конвертируется в новый формат на диске.

- [ ] **Step 1: Написать падающий тест `FileSystemSessionRepositoryTest`**

```java
package com.intentreactor.core.session;

import com.intentreactor.api.Message;
import com.intentreactor.api.SessionState;
import com.intentreactor.core.config.IntentReactorProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FileSystemSessionRepositoryTest {

    @TempDir
    Path tempDir;

    private FileSystemSessionRepository repo;
    private IntentReactorProperties properties;

    @BeforeEach
    void setUp() {
        properties = new IntentReactorProperties();
        properties.getSession().getFilesystem().setPath(tempDir.toString());
        repo = new FileSystemSessionRepository(properties, JsonMapper.builder().build());
    }

    private Session newSession(String id) {
        return Session.builder().id(id).userId(id).createdAt(java.time.Instant.now())
                .expiresAt(null).metadata(Map.of()).build();
    }

    @Test
    void saveAndFindByIdRoundTrip() {
        repo.save(newSession("s1"));
        Session found = repo.findById("s1");
        assertThat(found).isNotNull();
        assertThat(found.id()).isEqualTo("s1");
        assertThat(found.userId()).isEqualTo("s1");
    }

    @Test
    void appendAndFindEventsPreserveOrderAndVersion() {
        repo.save(newSession("s2"));
        repo.appendEvent(SessionEvent.builder().id(UUID.randomUUID().toString()).sessionId("s2")
                .message(new UserMessage("one")).metadata(Map.of()).build());
        repo.appendEvent(SessionEvent.builder().id(UUID.randomUUID().toString()).sessionId("s2")
                .message(new UserMessage("two")).metadata(Map.of()).build());
        assertThat(repo.getEventVersion("s2")).isEqualTo(2);
        List<SessionEvent> events = repo.findEvents("s2", EventFilter.all());
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getMessage().getText()).isEqualTo("one");
        assertThat(events.get(1).getMessage().getText()).isEqualTo("two");
    }

    @Test
    void appendEventIsIdempotentById() {
        repo.save(newSession("s3"));
        SessionEvent e = SessionEvent.builder().id("fixed-id").sessionId("s3")
                .message(new UserMessage("x")).metadata(Map.of()).build();
        repo.appendEvent(e);
        repo.appendEvent(e);
        assertThat(repo.getEventVersion("s3")).isEqualTo(1);
        assertThat(repo.findEvents("s3", EventFilter.all())).hasSize(1);
    }

    @Test
    void deleteRemovesFile() {
        repo.save(newSession("s4"));
        repo.delete("s4");
        assertThat(repo.findById("s4")).isNull();
        assertThat(tempDir.resolve("s4.json")).doesNotExist();
    }

    @Test
    void legacySessionStateFileIsConvertedOnFirstRead() throws Exception {
        // legacy v0.1.x format: a full SessionState JSON
        SessionState legacy = new SessionState("s5");
        legacy.addMessage(Message.user("old question"));
        legacy.addMessage(Message.system("[TOOL_RESULT] tool: ok"));
        legacy.getAttributes().put("keep", "me");
        Path file = tempDir.resolve("s5.json");
        new ObjectMapper().writeValue(file.toFile(), legacy);

        Session session = repo.findById("s5");
        assertThat(session).isNotNull();
        assertThat(session.id()).isEqualTo("s5");
        List<SessionEvent> events = repo.findEvents("s5", EventFilter.all());
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getMessage().getText()).isEqualTo("old question");
    }

    @Test
    void compactEventsUsesExpectedVersionCas() {
        repo.save(newSession("s6"));
        repo.appendEvent(SessionEvent.builder().id(UUID.randomUUID().toString()).sessionId("s6")
                .message(new UserMessage("a")).metadata(Map.of()).build());
        boolean ok = repo.compactEvents("s6", List.of(), List.of(), 1L);
        assertThat(ok).isTrue();
        assertThat(repo.getEventVersion("s6")).isEqualTo(2);
        assertThat(repo.compactEvents("s6", List.of(), List.of(), 1L)).isFalse();
    }
}
```

Примечание: `LegacySessionStateFileIsConvertedOnFirstRead` использует `new ObjectMapper()` (tools.jackson) — это ок, legacy-файл писался голым маппером без полиморфных маппингов (формат старого файлового стора читался авто-конфиг маппером, но для простых сообщений/атрибутов идентичен).

- [ ] **Step 2: Убедиться, что тест падает**

Run: `mvn -pl intent-reactor-core -am -Dtest=FileSystemSessionRepositoryTest test`
Expected: FAIL (класс не существует).

- [ ] **Step 3: Реализовать `FileSystemSessionRepository`**

Create `intent-reactor-core/src/main/java/com/intentreactor/core/session/FileSystemSessionRepository.java`:

```java
package com.intentreactor.core.session;

import com.intentreactor.api.Message;
import com.intentreactor.api.SessionState;
import com.intentreactor.core.config.IntentReactorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-backed {@link SessionRepository}: one JSON file per session under
 * {@code intent-reactor.session.filesystem.path}. Writes are atomic (temp file + rename),
 * per-session locks prevent concurrent corruption within the JVM.
 * <p>Legacy v0.1.x files (a whole serialized {@link SessionState}) are converted to the
 * current format on first read.
 */
public class FileSystemSessionRepository implements SessionRepository {

    private static final Logger log = LoggerFactory.getLogger(FileSystemSessionRepository.class);

    private final Path basePath;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();

    public FileSystemSessionRepository(IntentReactorProperties properties, ObjectMapper mapper) {
        String pathStr = properties.getSession().getFilesystem().getPath();
        this.basePath = Paths.get(pathStr);
        this.mapper = mapper;
        try {
            Files.createDirectories(this.basePath);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create session store directory: " + pathStr, e);
        }
    }

    @Override
    public Session save(Session session) {
        synchronized (lockFor(session.id())) {
            SessionFile file = readOrInit(session.id());
            file.session = toSessionDto(session);
            write(file, session.id());
        }
        return session;
    }

    @Override
    public Session findById(String sessionId) {
        synchronized (lockFor(sessionId)) {
            File f = sessionFile(sessionId);
            if (!f.exists()) return null;
            try {
                if (isLegacyFormat(f)) {
                    convertLegacy(f, sessionId);
                }
                SessionFile file = mapper.readValue(f, SessionFile.class);
                return toSession(file.session);
            } catch (Exception e) {
                log.error("Failed to read session {}", sessionId, e);
                return null;
            }
        }
    }

    @Override
    public List<Session> findByUserId(String userId) {
        List<Session> result = new ArrayList<>();
        File[] files = basePath.toFile().listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return result;
        for (File f : files) {
            String id = f.getName().substring(0, f.getName().length() - 5);
            Session s = findById(id);
            if (s != null && userId.equals(s.userId())) result.add(s);
        }
        return result;
    }

    @Override
    public List<String> findExpiredSessionIds(Instant before) {
        List<String> result = new ArrayList<>();
        File[] files = basePath.toFile().listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return result;
        for (File f : files) {
            String id = f.getName().substring(0, f.getName().length() - 5);
            Session s = findById(id);
            if (s != null && s.expiresAt() != null && s.expiresAt().isBefore(before)) result.add(id);
        }
        return result;
    }

    @Override
    public void delete(String sessionId) {
        synchronized (lockFor(sessionId)) {
            File file = sessionFile(sessionId);
            if (file.exists() && !file.delete()) {
                log.warn("Could not delete session file for {}", sessionId);
            }
        }
    }

    @Override
    public void appendEvent(SessionEvent event) {
        synchronized (lockFor(event.getSessionId())) {
            SessionFile file = readOrInit(event.getSessionId());
            for (EventDto dto : file.events) {
                if (dto.id.equals(event.getId())) return; // idempotent by id
            }
            file.events.add(toEventDto(event));
            file.version++;
            write(file, event.getSessionId());
        }
    }

    @Override
    public boolean compactEvents(String sessionId, List<SessionEvent> archivedEvents,
                                 List<SessionEvent> retainedEvents, long expectedVersion) {
        synchronized (lockFor(sessionId)) {
            SessionFile file = readOrInit(sessionId);
            if (file.version != expectedVersion) return false;
            file.events.clear();
            for (SessionEvent e : retainedEvents) file.events.add(toEventDto(e));
            for (SessionEvent e : archivedEvents) file.events.add(toEventDto(e));
            file.version = expectedVersion + 1;
            write(file, sessionId);
            return true;
        }
    }

    @Override
    public long getEventVersion(String sessionId) {
        synchronized (lockFor(sessionId)) {
            File f = sessionFile(sessionId);
            if (!f.exists()) return 0;
            try {
                return mapper.readValue(f, SessionFile.class).version;
            } catch (Exception e) {
                log.error("Failed to read event version for {}", sessionId, e);
                return 0;
            }
        }
    }

    @Override
    public List<SessionEvent> findEvents(String sessionId, EventFilter filter) {
        synchronized (lockFor(sessionId)) {
            File f = sessionFile(sessionId);
            if (!f.exists()) return List.of();
            try {
                if (isLegacyFormat(f)) {
                    convertLegacy(f, sessionId);
                }
                SessionFile file = mapper.readValue(f, SessionFile.class);
                List<SessionEvent> events = new ArrayList<>();
                for (EventDto dto : file.events) events.add(toEvent(dto));
                return events;
            } catch (Exception e) {
                log.error("Failed to read events for {}", sessionId, e);
                return List.of();
            }
        }
    }

    // ---- internals ----

    private boolean isLegacyFormat(File file) throws IOException {
        return mapper.readTree(file).has("messages");
    }

    private void convertLegacy(File file, String sessionId) throws IOException {
        SessionState legacy = mapper.readValue(file, SessionState.class);
        SessionState fresh = new SessionState(sessionId);
        for (Message m : legacy.getMessages()) fresh.addMessage(m);
        fresh.getAttributes().putAll(legacy.getAttributes());
        fresh.setPlanState(legacy.getPlanState() != null ? legacy.getPlanState() : fresh.getPlanState());
        fresh.setCreatedAt(legacy.getCreatedAt());
        fresh.setUpdatedAt(legacy.getUpdatedAt());

        SessionFile converted = new SessionFile();
        converted.session = new SessionDto();
        converted.session.id = sessionId;
        converted.session.userId = sessionId;
        converted.session.createdAt = toInstant(legacy.getCreatedAt());
        converted.session.expiresAt = null;
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(SessionStateStore.METADATA_KEY, SessionStateStore.encodeState(fresh));
        converted.session.metadata = metadata;
        converted.version = fresh.getMessages().size();
        for (Message m : fresh.getMessages()) {
            converted.events.add(toEventDto(SessionEventCodec.messageToEvent(sessionId, m)));
        }
        write(converted, sessionId);
        log.info("Converted legacy session file {} to the spring-ai-session format", file.getName());
    }

    private SessionFile readOrInit(String sessionId) {
        File f = sessionFile(sessionId);
        if (!f.exists()) return new SessionFile();
        try {
            return mapper.readValue(f, SessionFile.class);
        } catch (Exception e) {
            log.error("Failed to read session file for {}, starting fresh", sessionId, e);
            return new SessionFile();
        }
    }

    private void write(SessionFile file, String sessionId) {
        try {
            Path tmp = basePath.resolve(sessionId + ".json.tmp");
            Path dest = basePath.resolve(sessionId + ".json");
            mapper.writeValue(tmp.toFile(), file);
            try {
                Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                log.warn("Atomic move not supported, falling back to regular move: {}", ex.getMessage());
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("Failed to save session file for {}", sessionId, e);
        }
    }

    private Session toSession(SessionDto dto) {
        if (dto == null) return null;
        return Session.builder().id(dto.id).userId(dto.userId)
                .createdAt(dto.createdAt).expiresAt(dto.expiresAt)
                .metadata(dto.metadata == null ? Map.of() : dto.metadata)
                .build();
    }

    private SessionDto toSessionDto(Session s) {
        SessionDto dto = new SessionDto();
        dto.id = s.id();
        dto.userId = s.userId();
        dto.createdAt = s.createdAt();
        dto.expiresAt = s.expiresAt();
        dto.metadata = s.metadata();
        return dto;
    }

    private SessionEvent toEvent(EventDto dto) {
        return SessionEvent.builder()
                .id(dto.id)
                .sessionId(dto.sessionId)
                .timestamp(dto.timestamp)
                .message(SessionEventCodec.messageForType(dto.messageType, dto.content))
                .metadata(dto.metadata == null ? Map.of() : dto.metadata)
                .branch(dto.branch)
                .archived(dto.archived)
                .build();
    }

    private EventDto toEventDto(SessionEvent e) {
        EventDto dto = new EventDto();
        dto.id = e.getId();
        dto.sessionId = e.getSessionId();
        dto.timestamp = e.getTimestamp();
        dto.messageType = e.getMessage() instanceof org.springframework.ai.chat.messages.UserMessage ? "USER"
                : e.getMessage() instanceof org.springframework.ai.chat.messages.AssistantMessage ? "ASSISTANT" : "SYSTEM";
        dto.content = e.getMessage().getText();
        dto.metadata = e.getMetadata();
        dto.branch = e.getBranch();
        dto.archived = e.isArchived();
        dto.synthetic = e.isSynthetic();
        return dto;
    }

    private static Instant toInstant(LocalDateTime ldt) {
        return ldt.atZone(ZoneId.systemDefault()).toInstant();
    }

    private Object lockFor(String id) {
        return sessionLocks.computeIfAbsent(id, k -> new Object());
    }

    private File sessionFile(String sessionId) {
        return basePath.resolve(sessionId + ".json").toFile();
    }

    // ---- Jackson DTOs (mutable beans; the framework avoids records for Jackson 3) ----

    static class SessionFile {
        public SessionDto session;
        public List<EventDto> events = new ArrayList<>();
        public long version;
    }

    static class SessionDto {
        public String id;
        public String userId;
        public Instant createdAt;
        public Instant expiresAt;
        public Map<String, Object> metadata;
    }

    static class EventDto {
        public String id;
        public String sessionId;
        public Instant timestamp;
        public String messageType;
        public String content;
        public Map<String, Object> metadata;
        public String branch;
        public boolean archived;
        public boolean synthetic;
    }
}
```

Внимание: код использует `SessionEventCodec.messageForType(String, String)` — его нужно **добавить в `SessionEventCodec`** (package-private static):

```java
    /** Rebuilds a Spring AI message from the persisted role name and text. */
    static org.springframework.ai.chat.messages.Message messageForType(String roleName, String content) {
        return switch (roleName == null ? "SYSTEM" : roleName) {
            case "USER" -> new UserMessage(content == null ? "" : content);
            case "ASSISTANT" -> new AssistantMessage(content == null ? "" : content);
            default -> new SystemMessage(content == null ? "" : content);
        };
    }
```

(импорты `UserMessage`/`AssistantMessage`/`SystemMessage` уже есть в кодец-классе). Дополнительно: DTO с public-полями в `FileSystemSessionRepository` десериализуются Jackson по умолчанию (public field visibility); если тесты покажут иное — добавить в DTO геттеры/сеттеры. Если javap покажет иное имя метода (`getBranch()` vs `branch()` и т.п.) — адаптировать по секции «Фактические API».

- [ ] **Step 4: Добавить сквозной тест store+filesystem**

В `SessionStateStoreTest` (класс из Task 2) добавить тест (использует `@TempDir`; в `setUp` заменить `InMemorySessionRepository` на новый `FileSystemSessionRepository` с temp-путём — сделать отдельный тестовый метод с собственным store):

```java
    @Test
    void storeRoundTripOverFileSystemRepository(@TempDir java.nio.file.Path tempDir) {
        IntentReactorProperties props = new IntentReactorProperties();
        props.getSession().getFilesystem().setPath(tempDir.toString());
        FileSystemSessionRepository fileRepo = new FileSystemSessionRepository(props, mapper);
        SessionStateStore fileStore = new SessionStateStore(fileRepo, mapper);

        SessionState s = sessionWith("fs1", Message.user("q"), Message.assistant("a"));
        fileStore.save(s);
        s.addMessage(Message.user("q2"));
        fileStore.save(s);

        SessionState r = fileStore.findById("fs1").orElseThrow();
        assertThat(r.getMessages()).hasSize(3);
        assertThat(r.getMessages().get(1).getContent()).isEqualTo("a");
    }
```

- [ ] **Step 5: Прогнать тесты**

Run: `mvn -pl intent-reactor-core -am -Dtest=SessionStateStoreTest,FileSystemSessionRepositoryTest test`
Expected: PASS.

- [ ] **Step 6: Закоммитить**

```bash
git add intent-reactor-core/src/main/java/com/intentreactor/core/session/FileSystemSessionRepository.java intent-reactor-core/src/test/java/com/intentreactor/core/session/FileSystemSessionRepositoryTest.java intent-reactor-core/src/test/java/com/intentreactor/core/session/SessionStateStoreTest.java
git commit -m "Add file-based SessionRepository with legacy session file conversion"
```

---

### Task 4: Удаление `api.SessionStore`, старых сториджей; переключение core на `SessionStateStore`

**Files:**
- Delete: `intent-reactor-api/src/main/java/com/intentreactor/api/SessionStore.java`
- Modify: `intent-reactor-api/src/main/java/com/intentreactor/api/SessionState.java` (javadoc)
- Modify: `intent-reactor-api/src/main/java/com/intentreactor/api/IntentReactorService.java` (javadoc)
- Delete: `intent-reactor-core/src/main/java/com/intentreactor/core/session/InMemorySessionStore.java`, `intent-reactor-core/src/main/java/com/intentreactor/core/session/FileSystemSessionStore.java`
- Modify: `intent-reactor-core/src/main/java/com/intentreactor/core/config/IntentReactorProperties.java`
- Modify: `intent-reactor-core/src/main/java/com/intentreactor/core/config/IntentReactorAutoConfiguration.java`
- Modify: `intent-reactor-core/src/main/java/com/intentreactor/core/service/IntentReactorServiceImpl.java`
- Modify: `intent-reactor-core/src/main/java/com/intentreactor/core/service/multiintent/SequentialMultiIntentStrategy.java`
- Modify: `intent-reactor-core/src/test/java/com/intentreactor/core/service/IntentReactorServiceImplTest.java`
- Modify: `intent-reactor-core/src/test/java/com/intentreactor/core/service/MultiIntentTest.java`
- Modify: `intent-reactor-core/src/test/java/com/intentreactor/core/LiveLlmEndToEndIT.java`
- Delete: `intent-reactor-core/src/test/java/com/intentreactor/core/session/InMemorySessionStoreTest.java`

**Interfaces:**
- Consumes: `SessionStateStore`, `FileSystemSessionRepository` (Task 2-3); `org.springframework.ai.session.InMemorySessionRepository`, `SessionRepository`.
- Produces: новый бин-граф авто-конфигурации; `IntentReactorServiceImpl(..., SessionStateStore sessionStore, ...)`; `SequentialMultiIntentStrategy(SessionStateStore)`; свойство `intent-reactor.session.store` = `in-memory|filesystem`.

- [ ] **Step 1: Удалить `SessionStore` из api и поправить javadoc-ссылки**

Delete файл `intent-reactor-api/src/main/java/com/intentreactor/api/SessionStore.java`.

Edit `SessionState.java`:
- строка 17: `It is persisted by {@link SessionStore} and restored at the start of each` → `It is persisted by the configured session store and restored at the start of each`
- строку 57 (`* @see SessionStore`) удалить
- строки 89-90 (`* and by {@link SessionStore} implementations before persisting.`) → `* and before persisting.`

Edit `IntentReactorService.java` строка 79: `* {@link SessionStore}, a new one is created and persisted. Subsequent calls` → `* no session exists in the configured session store yet, a new one is created and persisted. Subsequent calls`

- [ ] **Step 2: Обновить `IntentReactorProperties`**

Edit `IntentReactorProperties.java`:
- в `SessionConfig` (строки 117-121) удалить поле `private JdbcSessionConfig jdbc = new JdbcSessionConfig();`
- удалить весь класс `JdbcSessionConfig` (строки 123-127)

- [ ] **Step 3: Обновить авто-конфигурацию**

Edit `IntentReactorAutoConfiguration.java`:
- удалить импорты `com.intentreactor.api.SessionStore`, `com.intentreactor.core.session.FileSystemSessionStore`, `com.intentreactor.core.session.InMemorySessionStore`
- добавить импорты:
```java
import com.intentreactor.core.session.FileSystemSessionRepository;
import com.intentreactor.core.session.SessionStateStore;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionRepository;
```
- строку 181: метод `sequentialMultiIntentStrategy(SessionStore sessionStore)` → `sequentialMultiIntentStrategy(SessionStateStore sessionStore)` (тело без изменений)
- строки 205-216: параметр `SessionStore sessionStore` в `intentReactorService(...)` и передача в конструктор → `SessionStateStore`
- заменить три бина store (строки 235-248) на:

```java
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
```

- [ ] **Step 4: Обновить service и sequential-стратегию**

Edit `IntentReactorServiceImpl.java`:
- импорт `com.intentreactor.api.SessionStore` → `com.intentreactor.core.session.SessionStateStore`
- поле `private final SessionStore sessionStore;` (строка 61) и параметр конструктора (строка 71) → `SessionStateStore`

Edit `intent-reactor-core/src/main/java/com/intentreactor/core/service/multiintent/SequentialMultiIntentStrategy.java`:
- импорт и поле/конструктор `SessionStore` → `SessionStateStore` (строки 12, 25, 27)

- [ ] **Step 5: Удалить старые стораджи и их тест**

Delete: `InMemorySessionStore.java`, `FileSystemSessionStore.java`, тест `InMemorySessionStoreTest.java`.

- [ ] **Step 6: Обновить тесты core**

Edit `IntentReactorServiceImplTest.java` (строки 14, 53): тип мока `SessionStore` → `SessionStateStore` (Mockito мокает классы; `when(sessionStore.findById(...)).thenReturn(...)` без изменений). То же в `MultiIntentTest.java`.

Edit `LiveLlmEndToEndIT.java`: поле (строка 74) `SessionStore sessionStore` → `SessionStateStore sessionStore`; импорт поменять на `com.intentreactor.core.session.SessionStateStore`. Остальной код теста (findById/round-trip) без изменений.

- [ ] **Step 7: Скомпилировать и прогнать тесты core**

Run: `mvn -pl intent-reactor-core -am test`
Expected: BUILD SUCCESS (старые тесты зелёные; `InMemorySessionStoreTest` удалён, `SessionStateStoreTest` на месте).

- [ ] **Step 8: Закоммитить**

```bash
git add -A intent-reactor-api intent-reactor-core
git commit -m "Replace SessionStore API and in-memory/filesystem stores with SessionStateStore over spring-ai-session"
```

---

### Task 5: sand-train на `SessionStateStore`

**Files:**
- Modify: `intent-reactor-sand-train/pom.xml`
- Modify: `intent-reactor-sand-train/src/main/java/com/intentreactor/sandtrain/SandDataCollector.java`
- Modify: `intent-reactor-sand-train/src/main/java/com/intentreactor/sandtrain/config/SandTrainAutoConfiguration.java`
- Modify: `intent-reactor-sand-train/src/test/java/com/intentreactor/sandtrain/SandDataCollectorTest.java`

**Interfaces:**
- Consumes: `SessionStateStore` (core); производит: `SandDataCollector(SessionStateStore)`.

- [ ] **Step 1: Зависимость на core**

Edit `intent-reactor-sand-train/pom.xml`: в `<dependencies>` перед зависимостью strategies добавить:

```xml
        <dependency>
            <groupId>com.intentreactor</groupId>
            <artifactId>intent-reactor-core</artifactId>
            <version>${project.version}</version>
        </dependency>
```

- [ ] **Step 2: Правки кода**

Edit `SandDataCollector.java`: импорт `com.intentreactor.api.SessionStore` → `com.intentreactor.core.session.SessionStateStore`; поле (строка 30) и конструктор (строки 34-36) — тип `SessionStateStore`. Обновить javadoc-предупреждение (строки 23-24): «NOTE: Reliable only with the in-memory session repository — with serializing stores the session lookup after event may return stale data since the event fires before `sessionStore.save()`».

Edit `SandTrainAutoConfiguration.java`:
- импорт `com.intentreactor.api.SessionStore` → `com.intentreactor.core.session.SessionStateStore`
- строка 21: `@ConditionalOnClass(SessionStore.class)` → `@ConditionalOnClass(SessionStateStore.class)`
- строка 27: параметр `SessionStore sessionStore` → `SessionStateStore sessionStore`

- [ ] **Step 3: Обновить тест**

Edit `SandDataCollectorTest.java`: импорт (строка 4) и тип мока (строка 33) `SessionStore` → `SessionStateStore`.

- [ ] **Step 4: Прогнать тесты модуля**

Run: `mvn -pl intent-reactor-sand-train -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Закоммитить**

```bash
git add intent-reactor-sand-train
git commit -m "Switch sand-train to SessionStateStore"
```

---

### Task 6: Удаление модулей `session-jdbc`/`session-jpa`

**Files:**
- Delete: каталоги `intent-reactor-session-jdbc/`, `intent-reactor-session-jpa/`
- Modify: `pom.xml` (корневой: `<modules>`, `dependencyManagement`)

**Interfaces:** — (удаление)

- [ ] **Step 1: Убрать модули из корневого pom**

Edit `pom.xml`:
- в `<modules>` удалить строки с `intent-reactor-session-jdbc` и `intent-reactor-session-jpa`
- в `<dependencyManagement><dependencies>` удалить `<dependency>`-блоки для артефактов `intent-reactor-session-jdbc`/`intent-reactor-session-jpa` (записи версии 0.1.16 проекта)

- [ ] **Step 2: Удалить каталоги модулей**

```bash
git rm -r intent-reactor-session-jdbc intent-reactor-session-jpa
```

- [ ] **Step 3: Проверить, что реактор компилируется и тесты зелёные**

Run: `mvn test`
Expected: BUILD SUCCESS (число тестов уменьшилось на JdbcSessionStoreTest и т.п.).

- [ ] **Step 4: Закоммитить**

```bash
git add -A
git commit -m "Remove intent-reactor-session-jdbc and intent-reactor-session-jpa modules"
```

---

### Task 7: Интеграционный тест связки с их JDBC-репозиторием (H2)

**Files:**
- Modify: `intent-reactor-core/pom.xml` (test-scope зависимости)
- Create: `intent-reactor-core/src/test/java/com/intentreactor/core/session/SessionStateStoreJdbcIntegrationTest.java`

**Interfaces:**
- Consumes: `JdbcSessionRepository` (их), `ScriptUtils`/`DataSource` (spring-jdbc — транзитивно от их jdbc-артефакта), H2.

- [ ] **Step 1: Test-зависимости**

Edit `intent-reactor-core/pom.xml`: в блок тестовых зависимостей добавить:

```xml
        <dependency>
            <groupId>org.springaicommunity</groupId>
            <artifactId>spring-ai-session-jdbc</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: Написать интеграционный тест**

Create `intent-reactor-core/src/test/java/com/intentreactor/core/session/SessionStateStoreJdbcIntegrationTest.java`:

```java
package com.intentreactor.core.session;

import com.intentreactor.api.Message;
import com.intentreactor.api.SimpleAction;
import com.intentreactor.api.SimplePlanStep;
import com.intentreactor.api.SessionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.session.jdbc.JdbcSessionRepository;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the SessionStateStore envelope (plain nested map) survives a real JSON
 * round-trip through the spring-ai-session JDBC repository with its default JsonMapper:
 * polymorphic planState data is rehydrated by SessionStateStore on load.
 */
class SessionStateStoreJdbcIntegrationTest {

    private SessionStateStore store;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:sstest;DB_CLOSE_DELAY=-1", "sa", "");
        ScriptUtils.executeSqlScript(ds.getConnection(), new org.springframework.core.io.ClassPathResource(
                "org/springframework/ai/session/jdbc/schema-h2.sql"));
        JdbcSessionRepository repo = JdbcSessionRepository.builder().dataSource(ds).build();
        store = new SessionStateStore(repo, mapper());
    }

    private static ObjectMapper mapper() {
        return JsonMapper.builder()
                .addModule(new SimpleModule()
                        .addAbstractTypeMapping(PlanStep.class, SimplePlanStep.class))
                .build();
    }

    @Test
    void fullRoundTripThroughJdbcRepository() {
        SessionState s = new SessionState("j1");
        s.addMessage(Message.pinnedUser("goal"));
        s.addMessage(Message.system("[TOOL_RESULT] tool: ok"));
        s.addMessage(Message.assistant("answer"));
        s.getAttributes().put("text", "value");
        s.getPlanState().getCompletedSteps().add(
                SimplePlanStep.act(new SimpleAction("toolA", Map.of("k", "v")), "desc", false));

        store.save(s);
        store.save(s); // second save must not duplicate events
        s.addMessage(Message.user("follow-up"));
        store.save(s);

        SessionState r = store.findById("j1").orElseThrow();
        assertThat(r.getMessages()).hasSize(4);
        assertThat(r.getMessages().get(0).isPinned()).isTrue();
        assertThat(r.getMessages().get(3).getContent()).isEqualTo("follow-up");
        assertThat(r.getAttributes().get("text")).isEqualTo("value");
        assertThat(r.getPlanState().getCompletedSteps()).hasSize(1);
        assertThat(r.getPlanState().getCompletedSteps().get(0).action().toolName()).isEqualTo("toolA");
    }
}
```

Замечания: если имя ресурса схемы внутри артефакта отличается (`schema-h2.sql` — проверить `jar tf` в Task 1/здесь), использовать фактическое. Если `JdbcSessionRepository.builder().dataSource(...)` требует также `transactionManager` — javap в Task 1 покажет; при необходимости добавить `.transactionManager(new DataSourceTransactionManager(ds))` (org.springframework.jdbc.datasource.DataSourceTransactionManager).

- [ ] **Step 3: Прогнать тест**

Run: `mvn -pl intent-reactor-core -am -Dtest=SessionStateStoreJdbcIntegrationTest test`
Expected: PASS (проверяет в т.ч. риск №1/№2 спеки — работу их jdbc-слоя на нашем стеке).

- [ ] **Step 4: Закоммитить**

```bash
git add intent-reactor-core/pom.xml intent-reactor-core/src/test/java/com/intentreactor/core/session/SessionStateStoreJdbcIntegrationTest.java
git commit -m "Add JDBC repository integration test for SessionStateStore (H2)"
```

---

### Task 8: Версия 0.2.0 по всем pom

**Files:** все `pom.xml` (корневой + модули)

- [ ] **Step 1: Поднять версию**

Заменить во всех pom.xml `0.1.16` → `0.2.0` (родительский `<version>` + `<version>` в `<parent>` модулей; dependencyManagement-записи модулей `com.intentreactor`). Проверить отсутствие пропусков:

```bash
git grep -l "0.1.16" | ForEach-Object { $_ }
```

Expected: пусто после замены (кроме, возможно, README/docs — их чинит Task 9/10; если остались — добавить в список правок Task 9/10).

- [ ] **Step 2: Прогнать сборку**

Run: `mvn test`
Expected: BUILD SUCCESS (в отчётах версия 0.2.0).

- [ ] **Step 3: Закоммитить**

```bash
git add -A
git commit -m "Bump version to 0.2.0"
```

---

### Task 9: Документация — session-гайд 05 и конфигурационный справочник (EN/RU)

**Files:**
- Rewrite: `docs/05-session-stores.md`
- Rewrite: `docs-ru/05-session-stores.md`
- Modify: `docs/13-configuration-reference.md`, `docs-ru/13-configuration-reference.md`
- Modify: `docs/02-core-concepts.md`, `docs-ru/02-core-concepts.md`
- Modify: `docs/03-request-lifecycle.md`, `docs-ru/03-request-lifecycle.md`
- Modify: `docs/07-confirmation-flow.md`, `docs-ru/07-confirmation-flow.md`

**Interfaces:** — (документация; билингва-конвенция: EN и RU зеркальны)

- [ ] **Step 1: Переписать `docs/05-session-stores.md` и RU-зеркало**

Содержание (оба файла, структура по главам как раньше):
1. Модель: история = события (`SessionEvent` поверх Spring AI `Message`), состояние планировщика = envelope в `Session.metadata` (`com.intentreactor.state`); userId=sessionId; без TTL.
2. Хранилища: таблица-сравнение in-memory / filesystem / JDBC (spring-ai-session), что теряется при перезапуске, где лежит.
3. Выбор хранилища: свойство `intent-reactor.session.store` = `in-memory` (default) | `filesystem`; JDBC — зависимостью + **правило приоритета бинов** (любой внешний `SessionRepository` побеждает; пример yaml для jdbc: добавить `spring-ai-starter-session-jdbc:0.8.0`, убрать `intent-reactor.session.store`).
4. JDBC-раздел: их таблицы `AI_SESSION`/`AI_SESSION_EVENT`, инициализация схемы (`spring.ai.session.repository.jdbc.initialize-schema` — по результату Task 1 Step 5: штатная ИЛИ ручная по `schema-<db>.sql` из их артефакта), поддержанные БД (PostgreSQL/MySQL/MariaDB/H2).
5. filesystem-раздел: путь (`intent-reactor.session.filesystem.path`), атомарная запись, формат файла, авто-конверсия legacy-файлов 0.1.x.
6. Кастомное хранилище: реализация `SessionRepository` (SPI spring-ai-session) вместо бывшего `SessionStore`; приоритет бина.
7. **Миграция с 0.1.x**: удалённые модули и артефакты (`intent-reactor-session-jdbc`/`-jpa`), удалённое свойство `intent-reactor.session.jdbc.table-name`, значения `store=jdbc/jpa` → действия (добавить их starter / удалить свойство), старые таблицы `intent_reactor_sessions` новым кодом не читаются (данные перенести вручную), файловые сессии конвертируются автоматически при первом чтении.
8. Раздел LATS-совместимости: файлы/БД (JSON-репозитории) — LATS-дерево и типизированные атрибуты реидратируются при загрузке; in-memory хранит живые объекты.

В доке 05 поправить также устаревшую версию в снипетах (0.1.6 → 0.2.0) и замечание про Jackson 3 upgrade (перенести в историю изменений, если осталось).

- [ ] **Step 2: Поправить конфигурационный справочник (13) и концепты/циклы (02/03/07)**

`docs/13-configuration-reference.md` + RU (строки yaml-блока session ~153-167 и таблица свойств ~338-340):
- yaml: убрать `jdbc.table-name`/значения jdbc/jpa; показать `store: filesystem` c `filesystem.path`;
- таблица: `intent-reactor.session.store` (in-memory|filesystem, default in-memory), `intent-reactor.session.filesystem.path`; строку `intent-reactor.session.jdbc.table-name` удалить; добавить примечание: `spring.ai.session.repository.jdbc.initialize-schema` и `spring.ai.session.time-to-live` управляются spring-ai-session (если задействован их starter).

`docs/02-core-concepts.md` + RU: секция SessionStore (пример интерфейса + список реализаций) → секция «Session persistence»: `SessionStateStore` (фасад движка, findById/save/delete) + хранилища через SPI `SessionRepository`; четыре реализации → in-memory/filesystem/JDBC(add-on).

`docs/03-request-lifecycle.md` + RU (фаза 1): `SessionStore.findById` → `SessionStateStore.findById`; упоминание save там же.
`docs/07-confirmation-flow.md` + RU: `SessionStore.save()` → `SessionStateStore.save()`.

- [ ] **Step 3: Проверить отсутствие устаревших ссылок в docs**

```bash
git grep -n "SessionStore" -- docs docs-ru intent-reactor-api intent-reactor-core intent-reactor-sand-train intent-reactor-spring-boot-starter | Select-String -NotMatch "SessionStateStore"
```

Expected: пусто (допустимы упоминания `SessionStateStore`/`SessionRepository`).

- [ ] **Step 4: Закоммитить**

```bash
git add docs docs-ru
git commit -m "Update docs (EN/RU): spring-ai-session stores, config reference, lifecycle"
```

---

### Task 10: README (EN/RU) и AGENTS.md

**Files:**
- Modify: `README.md`, `README-ru.md`
- Modify: `AGENTS.md`

- [ ] **Step 1: README/README-ru**

Правки (зеркально):
- модульная карта (EN ~152-153, RU ~152-153): удалить строки `session-jdbc`/`session-jpa`; добавить строку про внешнюю зависимость spring-ai-session в раздел «Optional add-ons» или в описание core (по факту структуры);
- раздел «Session Stores» (EN 191-206 / RU 197-205): таблица значений → `in-memory | filesystem` (+ «JDBC — через spring-ai-starter-session-jdbc»), yaml-пример обновить;
- таблица extension points (EN 293 / RU 293): `SessionStore` → `SessionRepository` (spring-ai-session SPI) + `SessionStateStore` (facade);
- конфиг-пример session (EN 336-341 / RU 336-341): убрать jdbc;
- версии в снипетах зависимостей: актуальные (0.2.0 для `com.intentreactor`, 0.8.0 для spring-ai-session).

- [ ] **Step 2: AGENTS.md**

- модульная карта: удалить `session-jdbc`, `session-jpa` из «Optional add-ons»; добавить в Gotchas/Module map: persistence — адаптер `SessionStateStore` над `org.springframework.ai.session.SessionRepository` (артефакт `spring-ai-session` 0.8.0, BOM в корне); свойство `store` = in-memory|filesystem; кастомные хранилища = реализация их SPI;
- строка про sand-train literal `"sand_training_log"` — оставить (атрибут жив);
- раздел про session attribute keys: оставить, дополнить упоминанием, что `SessionState` по-прежнему публичный контракт.

- [ ] **Step 3: Проверить остатки**

```bash
git grep -n "session-jdbc\|session-jpa\|SessionStore\|store=jdbc\|store=jpa\|table-name" -- README.md README-ru.md AGENTS.md
```

Expected: пусто (кроме допустимых упоминаний `SessionStateStore`/`spring-ai-session`).

- [ ] **Step 4: Закоммитить**

```bash
git add README.md README-ru.md AGENTS.md
git commit -m "Update README (EN/RU) and AGENTS.md for spring-ai-session migration"
```

---

### Task 11: Финальная верификация и вычитка

**Files:** нет (правки только при обнаружении проблем)

- [ ] **Step 1: Полный прогон**

Run: `mvn test`
Expected: BUILD SUCCESS.

- [ ] **Step 2: Свип остатков**

```bash
git grep -n "SessionStore\|intent-reactor-session\|store=jdbc\|store=jpa\|JdbcSessionStore\|JpaSessionStore\|intent_reactor_sessions\|0\.1\.16"
```

Expected: пусто или только документированные упоминания (например, «Migrating from 0.1.x» в доках, ссылки на старые таблицы в миграционном разделе — допустимы).

- [ ] **Step 3: Проверить git status и историю**

Run: `git status --short`, `git log --oneline -15`
Expected: только ожидаемые файлы; коммиты по задачам.

- [ ] **Step 4: Обновить статус спеки и плана**

В `docs/superpowers/specs/2026-09-03-spring-ai-session-migration-design.md` и в этом плане — при необходимости отметить статус выполнения; при расхождениях реализации со спекой (например, механизм счётчика — фактически `getEventVersion`, а не WeakHashMap) — внести правку в спеку, чтобы она оставалась источником истины, и закоммитить:

```bash
git add docs/superpowers
git commit -m "Align migration spec with implementation details"
```

---

## Фактические API spring-ai-session 0.8.0 (Task 1 probe)

(Заполнено в Task 1, javap-прозвон от 2026-09-03, jar скачан с Maven Central; классы ядра резолвятся из `spring-ai-session-0.8.0.jar`, SA-сообщения — из `spring-ai-model-2.0.1.jar`.)

Итог: все «Expected»-сигнатуры из брифа подтверждены фактически, расхождений нет — код последующих задач (SessionEventCodec, SessionStateStore, FileSystemSessionRepository, тесты) совместим без адаптации.

### Session (org.springframework.ai.session)

```java
public final class org.springframework.ai.session.Session {
  public java.lang.String id();
  public java.lang.String userId();
  public java.time.Instant createdAt();
  public java.time.Instant expiresAt();
  public java.util.Map<java.lang.String, java.lang.Object> metadata();
  public static org.springframework.ai.session.Session$Builder builder();
}
// Session$Builder:
//   id(String) userId(String) createdAt(Instant) expiresAt(Instant)
//   metadata(Map<String,Object>) build()
```

### SessionEvent (org.springframework.ai.session)

```java
public final class org.springframework.ai.session.SessionEvent {
  public static final java.lang.String METADATA_SYNTHETIC;
  public static final java.lang.String METADATA_COMPACTION_SOURCE;
  public java.lang.String getId();
  public java.lang.String getSessionId();
  public java.time.Instant getTimestamp();
  public org.springframework.ai.chat.messages.Message getMessage();
  public java.util.Map<java.lang.String, java.lang.Object> getMetadata();
  public java.lang.String getBranch();
  public boolean isRootEvent();
  public boolean isArchived();
  public org.springframework.ai.session.SessionEvent asArchived();
  public org.springframework.ai.chat.messages.MessageType getMessageType();
  public boolean isSynthetic();
  public boolean hasToolCalls();
  public boolean equals(java.lang.Object);
  public int hashCode();
  public static org.springframework.ai.session.SessionEvent$Builder builder();
}
// SessionEvent$Builder:
//   id(String) sessionId(String) timestamp(Instant) message(Message)
//   metadata(Map<String,Object>) metadata(String,Object) branch(String)
//   archived(boolean) build()
```

Замечание: поля билдера `id/sessionId/timestamp/message/metadata/branch/archived` — как ожидалось; признак `synthetic` задаётся НЕ билдером, а через metadata-ключ `METADATA_SYNTHETIC` (в плане не используется).

### SessionRepository (org.springframework.ai.session)

```java
public interface org.springframework.ai.session.SessionRepository {
  public abstract org.springframework.ai.session.Session save(org.springframework.ai.session.Session);
  public abstract org.springframework.ai.session.Session findById(java.lang.String);
  public abstract java.util.List<org.springframework.ai.session.Session> findByUserId(java.lang.String);
  public abstract java.util.List<java.lang.String> findExpiredSessionIds(java.time.Instant);
  public abstract void delete(java.lang.String);
  public abstract void appendEvent(org.springframework.ai.session.SessionEvent);
  public abstract boolean compactEvents(java.lang.String, java.util.List<org.springframework.ai.session.SessionEvent>, java.util.List<org.springframework.ai.session.SessionEvent>, long);
  public abstract long getEventVersion(java.lang.String);
  public abstract java.util.List<org.springframework.ai.session.SessionEvent> findEvents(java.lang.String, org.springframework.ai.session.EventFilter);
}
```

### InMemorySessionRepository и EventFilter (org.springframework.ai.session)

```java
public final class org.springframework.ai.session.InMemorySessionRepository
    implements org.springframework.ai.session.SessionRepository {
  public static org.springframework.ai.session.InMemorySessionRepository$Builder builder();
  // ... все методы SessionRepository ...
}
// InMemorySessionRepository$Builder: build() — параметров нет (как ожидалось)

public final class org.springframework.ai.session.EventFilter extends java.lang.Record {
  public static final int DEFAULT_PAGE_SIZE;
  public static org.springframework.ai.session.EventFilter all();
  public static org.springframework.ai.session.EventFilter active();
  public static org.springframework.ai.session.EventFilter lastN(int);
  public static org.springframework.ai.session.EventFilter realOnly();
  // + keywordSearch/keywordsSearch/patternSearch/forBranch/merge/builder()/matches(SessionEvent)
  // рекорд-аксессоры: from() to() messageTypes() excludeSynthetic() lastN() keyword()
  // keywords() matchMode() pattern() page() pageSize() branch() excludeArchived()
}
```

`EventFilter.all()` присутствует — код `sessionRepository.findEvents(sessionId, EventFilter.all())` валиден.

### Конструкторы SA-сообщений (spring-ai-model 2.0.1)

```java
// все три класса — extends AbstractMessage (getText()/getMetadata()/getMessageType() там)
public class org.springframework.ai.chat.messages.UserMessage extends ...AbstractMessage {
  public org.springframework.ai.chat.messages.UserMessage(java.lang.String);       // есть
  // + UserMessage(Resource), builder(), mutate()
}
public class org.springframework.ai.chat.messages.AssistantMessage extends ...AbstractMessage {
  public org.springframework.ai.chat.messages.AssistantMessage(java.lang.String);  // есть
  // полный ctor protected (String, Map, List<ToolCall>, List<Media>)
}
public class org.springframework.ai.chat.messages.SystemMessage extends ...AbstractMessage {
  public org.springframework.ai.chat.messages.SystemMessage(java.lang.String);     // есть
  // + SystemMessage(Resource), builder(), mutate()
}
public class org.springframework.ai.chat.messages.ToolResponseMessage extends ...AbstractMessage {  // есть (маппится в SYSTEM)
  // ctor protected (List<ToolResponse>, Map); builder() — только через builder()
}
// MessageType enum: USER, ASSISTANT, SYSTEM, TOOL
```

Конструкторы `new UserMessage(String)` / `new AssistantMessage(String)` / `new SystemMessage(String)` публичны — код codec-классов валиден.

### Boot 4.0.8: классы инициализации схемы (Step 5)

Классы **присутствуют**, но в Boot 4 код разнесён по модульным артефактам — в `spring-boot-4.0.8.jar` их нет:

```java
// spring-boot-sql-4.0.8.jar:
public abstract class org.springframework.boot.sql.autoconfigure.init.OnDatabaseInitializationCondition
    extends org.springframework.boot.autoconfigure.condition.SpringBootCondition {
  protected OnDatabaseInitializationCondition(java.lang.String, java.lang.String...);
  public ConditionOutcome getMatchOutcome(ConditionContext, AnnotatedTypeMetadata);
}
public class org.springframework.boot.sql.autoconfigure.init.SqlInitializationProperties { ... }  // schema/dataLocations, platform, mode ...
// spring-boot-jdbc-4.0.8.jar:
public abstract class org.springframework.boot.jdbc.init.DatabaseInitializationProperties { ... }
//   getSchema/setSchema, getPlatform/setPlatform, getInitializeSchema/setInitializeSchema,
//   isContinueOnError, getDefaultSchemaLocation (abstract)
public class org.springframework.boot.jdbc.init.PropertiesBasedDataSourceScriptDatabaseInitializer<T extends DatabaseInitializationProperties>
    extends DataSourceScriptDatabaseInitializer { ctor(DataSource, T); ... }
```

Их авто-конфигурация `spring-ai-autoconfigure-session-jdbc:0.8.0` скомпилирована против Boot 4.0-классов (`JdbcSessionRepositorySchemaInitializer extends org.springframework.boot.jdbc.init.PropertiesBasedDataSourceScriptDatabaseInitializer<JdbcSessionRepositoryProperties>`; условие `...$OnJdbcSessionRepositoryDatasourceInitializationCondition extends org.springframework.boot.sql.autoconfigure.init.OnDatabaseInitializationCondition`) — при подключении их starter-а схема создаётся штатным механизмом Boot 4 из ресурсов jdbc-артефакта.

Ресурсы схемы в `spring-ai-session-jdbc-0.8.0.jar` (имена без `@@platform@@`, выбираются их кодом по платформе):
`org/springframework/ai/session/jdbc/schema-h2.sql`, `schema-mysql.sql`, `schema-postgresql.sql`. `schema-h2.sql` создаёт `AI_SESSION` (id, user_id, created_at, expires_at, metadata LONGVARCHAR, event_version BIGINT) и `AI_SESSION_EVENT` (seq IDENTITY, id, session_id, timestamp, message_type VARCHAR(20), message_content LONGVARCHAR, message_data LONGVARCHAR, synthetic BOOLEAN, archived BOOLEAN, branch VARCHAR(500), metadata LONGVARCHAR, FK → AI_SESSION ON DELETE CASCADE) + индексы.

Прочее: их `spring-ai-session:0.8.0` зависит от `spring-ai-model:2.0.1` + `spring-ai-client-chat:2.0.1` (ровно версии реактора; конфликтов нет, см. dependency:tree core). BOM `spring-ai-session-bom:0.8.0` управляет: `spring-ai-session`, `spring-ai-session-jdbc`, `spring-ai-autoconfigure-session`, `spring-ai-autoconfigure-session-jdbc`, `spring-ai-starter-session-jdbc`.

### Решения по адаптации

- Расхождений с «Expected» брифа нет — код задач 2+ используется как написан.
- SessionEvent/билдер события: `.metadata(Map)` + `.metadata(String,Object)` — используем `Map` (как в коде задач).
- В доки (Task 9): инициализация схемы их jdbc-репозитория — штатная через их starter (классы Boot 4.0.8 есть; путь classpath: `spring-boot-sql`/`spring-boot-jdbc` подтягиваются их `spring-boot-starter-jdbc`). Наш core их не тянет — авто-конфигурация наших fallback-бинов не требует их jdbc-классов.
- В тестах core (Task 2/3/7) инициализация схемы остаётся ручной (тестовая БД H2, выполнение `schema-h2.sql` вручную) — их авто-конфигурация в test-scope не подключается.
