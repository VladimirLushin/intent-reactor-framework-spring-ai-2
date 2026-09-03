# Дизайн: миграция session-слоя IntentReactor на spring-ai-session

Дата: 2026-09-03. Ветка: `master` (работа в отдельной ветке `migration/spring-ai-session`). Статус: утверждён владельцем.

## 1. Контекст и цель

Много-модульный Maven-фреймворк `com.intentreactor:*` (Java 21, Spring Boot 4.0.8,
Spring AI 2.0.1, Jackson 3 `tools.jackson`) хранит диалоговое состояние собственными
силами: публичный интерфейс `api.SessionStore` (`findById/save/delete`) + четыре
реализации — `InMemorySessionStore`/`FileSystemSessionStore` (core) и модули
`intent-reactor-session-jdbc`/`intent-reactor-session-jpa` со своей схемой
(`intent_reactor_sessions`, одна JSON-строка `state` на сессию, upsert целиком).

Цель — заменить собственный session-слой на библиотеку экосистемы Spring AI —
`org.springaicommunity:spring-ai-session:0.8.0` (пакеты `org.springframework.ai.session`,
собрана против Spring AI 2.0.1 / Boot 4.x, Jackson 3 — совместима со стеком проекта).
Драйвер: стандартизация на экосистему Spring AI. Побочный эффект — устранение известных
дефектов собственных хранилищ: собственные «голые» ObjectMapper в jdbc/jpa без
полиморфных маппингов (LATS-несовместимость), свойство `jdbc.table-name`, которое
реализация не читала, отсутствие тестов у jpa-модуля.

Подход (утверждён): **адаптер**. Публичный контракт движка (`SessionState`, `Message`,
`PlanState`, атрибуты, события, SPIs планировщиков) и семантика ReACT-цикла **не
меняются**; заменяется только persistence-слой. Внешние хранилища пользователей отныне
реализуют стандартный SPI `org.springframework.ai.session.SessionRepository`.

## 2. Целевая модель хранения

spring-ai-session 0.8.0 (артефакты: `spring-ai-session` core; `spring-ai-session-jdbc`;
`spring-ai-autoconfigure-session`, `spring-ai-autoconfigure-session-jdbc`;
`spring-ai-starter-session-jdbc`; BOM `spring-ai-session-bom`):

- `Session` — неизменяемые метаданные сессии: `id`, `userId`, `createdAt`, `expiresAt`,
  `Map<String,Object> metadata`. Хранит только идентичность и lifecycle.
- `SessionEvent` — событие-обёртка над Spring AI `Message` (UserMessage/AssistantMessage/
  SystemMessage/ToolResponseMessage): `id`, `sessionId`, `timestamp`, `metadata`, `branch`,
  `archived`, `synthetic`.
- `SessionRepository` — persistence SPI: `save(Session)`, `findById`, `findByUserId`,
  `findExpiredSessionIds`, `delete`, `appendEvent` (идемпотентен по id),
  `compactEvents(sessionId, archived, retained, expectedVersion)` (CAS),
  `getEventVersion`, `findEvents(sessionId, EventFilter)`.
- JDBC-репозиторий: две таблицы `AI_SESSION` + `AI_SESSION_EVENT` (колонка `seq` —
  порядок вставки), диалекты H2/PostgreSQL/MySQL/MariaDB, авто-конфигурация + инициализация
  схемы `schema-@@platform@@.sql` по свойству `spring.ai.session.repository.jdbc.initialize-schema`.
- In-memory: `InMemorySessionRepository`. JPA/filesystem-репозиториев нет — пишутся свои
  под SPI.

### 2.1 Маппинг SessionState ↔ Session/события

Факт, подтверждённый исследованием кода: история в `SessionState.messages` строго
**append-only** — скользящее окно и сжатие применяются только при сборке промпта
(`DefaultReACTPlanner.buildMessages`, `MessageCompressor` как post-processor) и никогда не
мутируют список сообщений сессии. Это делает маппинг на event-log корректным без
компакции.

| SessionState | spring-ai-session |
|---|---|
| `messages` (append-only) | по одному `SessionEvent` на сообщение; роль — тип SA-сообщения: `Role.USER` → `UserMessage`, `Role.ASSISTANT` → `AssistantMessage`, `Role.SYSTEM` → `SystemMessage` (маркеры `[TOOL_RESULT]`/`[TOOL_ERROR]`/`[REFLECTION]` остаются в тексте, семантика движка не меняется). `pinned` и исходный `LocalDateTime` — в `metadata` события (строкой ISO), чтобы round-trip был без потерь. Порядок восстановления — порядок `seq` (append order) |
| `planState`, `attributes`, `createdAt`, `updatedAt` | JSON-envelope в `Session.metadata` под ключом `com.intentreactor.state` в виде **строки** JSON (double-encoding): сериализует наш авто-конфигурированный `ObjectMapper` (полиморфные маппинги `SearchTree→DefaultSearchTree`, `PlanStep→SimplePlanStep`, `Action→SimpleAction`), репозиторий же видит только `Map<String,String>` — их дефолтный `JsonMapper` подходит без модификации; полиморфные атрибуты (LATS-дерево, `MultiIntentContext`, `IntentAnalysisResult`) переживают round-trip и в JDBC |
| `id` | `Session.id`; `userId = sessionId` (их API требует non-blank; своих пользователей у фреймворка нет) |
| TTL | `expiresAt = null` — сохраняется прежняя семантика «без истечения» (дефолт их builder-а 60 дней не применяется); TTL-sweeping вне объёма |

Чтение событий, созданных не нами (чистая spring-ai-session сессия в той же БД), — без
падений: тип SA-сообщения маппится в нашу `Message` симметрично; при отсутствии
envelope-ключа атрибуты/план пустые.

`save(SessionState)` = upsert строки `Session` (metadata-envelope целиком) + append только
новых событий. Дельта считается по экземпляру `SessionState`: счётчик в
`WeakHashMap<SessionState,Integer>`, инициализируется при `findById`/создании; повторные
`save` того же экземпляра не дублируют события. `delete(sessionId)` → `repository.delete`.

Ограничение (наследуется от статус-кво): конкурентные запросы к одной сессии не
поддерживаются; семантика теперь append вместо last-write-wins.

## 3. Классы и модули

### 3.1 Новое в core (пакет `com.intentreactor.core.session`)

- `SessionStateStore` — фасад поверх SPI, центральный бин ядра: `findById(String) →
  Optional<SessionState>`, `save(SessionState)`, `delete(String)`. Конструктор
  `(SessionRepository, ObjectMapper)`. Внутри — маппинг 2.1: кодек `Message ↔ SessionEvent`
  (роль/pinned/timestamp), envelope-кодирование/декодирование состояния, счётчик дельты.
  Заменяет `api.SessionStore` для внутренних потребителей (service, multi-intent,
  sand-train). Хранилища пользователей реализуют стандартный `SessionRepository`
  (их SPI) — именно он становится публичным extension point'ом; фасад им не является.
- `FileSystemSessionRepository implements SessionRepository` — файловый репозиторий:
  JSON-файл на сессию под `intent-reactor.session.filesystem.path`, envelope
  `{session, events}`; наследует механику текущего `FileSystemSessionStore` (атомарная
  запись через tmp + `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`, per-session locks,
  инъекция нашего `ObjectMapper`). При чтении legacy-файла v0.1.x (полный
  `SessionState`-JSON прошлого формата) — авто-конверсия в новый формат при первом
  сохранении.
- Удаляются: `InMemorySessionStore`, `FileSystemSessionStore`.

### 3.2 Авто-конфигурация (`IntentReactorAutoConfiguration`)

Замена трёх бинов store (235-248) на:

- `sessionStateStore` (`@ConditionalOnMissingBean`) ← `SessionRepository` + `ObjectMapper`;
- `inMemorySessionRepository` — `@ConditionalOnMissingBean(SessionRepository.class)` +
  `@ConditionalOnProperty(store=in-memory, matchIfMissing=true)` → их
  `InMemorySessionRepository.builder().build()`;
- `fileSystemSessionRepository` — `@ConditionalOnMissingBean(SessionRepository.class)` +
  `@ConditionalOnProperty(store=filesystem)` → `FileSystemSessionRepository`.

Правило приоритета (как было у `SessionStore`): **любой внешний `SessionRepository`
(их JDBC из starter, кастомный пользователя) автоматически побеждает** fallback-бины.
Одновременное наличие внешнего репозитория и `store=filesystem` — внешний выигрывает
(документируется). Конфликт «два репозитория» невозможен со стороны нашей
авто-конфигурации: оба наших бина — `@ConditionalOnMissingBean(SessionRepository.class)`.

`IntentReactorServiceImpl` и `SequentialMultiIntentStrategy` инжектят `SessionStateStore`
вместо `SessionStore`. `ObjectMapper`-бин (с полиморфными маппингами) не меняется.

### 3.3 Свойства

- `intent-reactor.session.store`: значения `in-memory` (default) | `filesystem`.
  Значения `jdbc`/`jpa` не поддерживаются: при `store=jdbc`/`jpa` и отсутствии внешнего
  `SessionRepository`-бина Spring-контекст падает со стандартной ошибкой
  «No qualifying bean of type SessionRepository» — документируется в миграционном гайде
  («удалите свойство, добавьте starter»).
- Удаляется `intent-reactor.session.jdbc.table-name` (мёртвое: реализация его не читала;
  `JdbcSessionConfig` целиком).
- `intent-reactor.session.filesystem.path` остаётся.

### 3.4 Модули и pom

- Корневой pom: из `<modules>` и `dependencyManagement` удаляются
  `intent-reactor-session-jdbc` и `intent-reactor-session-jpa`; в `dependencyManagement`
  добавляется import BOM `org.springaicommunity:spring-ai-session-bom:0.8.0`.
- Каталоги `intent-reactor-session-jdbc/` и `intent-reactor-session-jpa/` удаляются из
  репозитория целиком (включая `schema.sql`, JPA-сущности, тесты JDBC).
- core: `+ org.springaicommunity:spring-ai-session` (compile; тянет
  `spring-ai-model:2.0.1` + `spring-ai-client-chat:2.0.1` — совпадает со стеком проекта).
- api: удаляется `SessionStore.java`; pom не меняется (никаких новых зависимостей —
  `SessionState`/`Message` остаются чистыми). Правки javadoc-перекрёстных ссылок в
  `SessionState`, `IntentReactorService`.
- sand-train: `+ com.intentreactor:intent-reactor-core` (прямая зависимость; транзитивно
  и так была через strategies); `SandDataCollector` и `SandTrainAutoConfiguration`
  переходят с `api.SessionStore` на `SessionStateStore`
  (`@ConditionalOnClass(SessionStateStore.class)`).

## 4. Тестирование

Все тесты офлайн (LLM/tool-провайдеры — моки Mockito), как принято в проекте.

- `SessionStateStoreTest` (core, поверх их `InMemorySessionRepository`): round-trip
  сообщений (роли, pinned, порядок), мульти-save без дублей событий, полиморфные
  атрибуты (`MultiIntentContext`, LATS-дерево, `PlanStep`) через наш mapper, planState,
  delete, отсутствующая сессия, чтение «чужого» события.
- `FileSystemSessionRepositoryTest` (core): round-trip, delete, атомарность/locks
  (наследие), авто-конверсия legacy-файла v0.1.x, событийный append.
- Один интеграционный тест связки: их `JdbcSessionRepository` на H2 (test-scope
  зависимости `spring-ai-session-jdbc` + `h2` в core) + инициализация схемы их
  `schema-h2.sql` + наш `SessionStateStore` + полиморфные атрибуты — проверка, что
  envelope-строки проходят их дефолтный `JsonMapper`.
- Правки моков: `IntentReactorServiceImplTest`, `MultiIntentTest` (core),
  `SandDataCollectorTest` (sand-train) — тип мока `api.SessionStore` → `SessionStateStore`
  либо реальный `SessionStateStore` поверх in-memory репозитория.
- `LiveLlmEndToEndIT`: остаётся на `store=filesystem` (свойство живо), тип поля store —
  `SessionStateStore`.
- Удаляются тесты удаляемых модулей (`JdbcSessionStoreTest`, `TestConfig` и т.п.).

## 5. Документация (EN/RU зеркально)

- `docs/05-session-stores.md` + `docs-ru/05-session-stores.md` — переписываются: новая
  модель (Session + SessionEvent + envelope в metadata), подключение
  `spring-ai-starter-session-jdbc` + yaml, схема БД (их `AI_SESSION`/`AI_SESSION_EVENT`,
  инициализация `spring.ai.session.repository.jdbc.initialize-schema`), правило
  приоритета бинов, кастомный репозиторий на их SPI (вместо примера кастомного
  `SessionStore`), filesystem-стор, **раздел «Миграция с 0.1.x»**: удаление модулей и
  свойства `store=jdbc/jpa`/`jdbc.table-name`, примечание, что старые таблицы
  `intent_reactor_sessions` новым кодом не читаются, файловые сессии v0.1.x
  конвертируются автоматически.
- `docs/02-core-concepts.md`, `docs/03-request-lifecycle.md`, `docs/07-confirmation-flow.md`,
  `docs/13-configuration-reference.md` (+ RU) — секции SessionStore → SessionStateStore /
  SessionRepository, таблицы свойств.
- `README.md`/`README-ru.md` — модульная карта (удалить session-jdbc/jpa), таблица
  session-хранилищ, extension point (SessionStore → SessionRepository), версии артефактов
  в сниппетах.
- AGENTS.md — модульная карта и gotchas (session-модули удалены, spring-ai-session как
  внешняя зависимость).

## 6. Версия и выпуск

- Версия поднимается до **0.2.0** (breaking: удаление публичного `api.SessionStore`,
  модулей и значений свойства). README-сниппеты версий синхронизируются (в docs/05
  также исправляется устаревшая 0.1.6 → 0.2.0).
- Работа в ветке `migration/spring-ai-session`; CI-релиз (тег `v*`) не затрагивается.

## 7. Риски и открытые проверки на старте реализации

1. Их авто-конфигурация (`spring-ai-autoconfigure-session-jdbc`) собрана против
   Boot 4.1.1, наш parent — 4.0.8 (`DatabaseInitializationProperties`,
   `OnDatabaseInitializationCondition`). Если инициализация схемы потребует API 4.1 —
   fallback: документируем ручное создание таблиц по их `schema-<db>.sql` (ресурсы
   поставляются артефактом). Проверить первым шагом реализации.
2. Интеграция их авто-конфигурации (starter) с нашим parent: их артефакты зависят от
   Boot 4.1.1-эпохи, но runtime-классы `org.springframework.ai.session.*` и jdbc-слой
   должны работать на 4.0.8 — покрывается интеграционным тестом §4.
3. Дельта-счётчик по экземпляру: проверить все потоки, клонирующие `SessionState`
   (`ParallelMultiIntentStrategy.cloneForIntent` и т.п.) — сохранения выполняются только
   через единый `SessionStateStore.save`; дубли событий исключаются счётчиком.
4. Порядок «внешний бин побеждает» при `store=filesystem`: документируется в доке 05.
5. Jackson 3 (`tools.jackson`), `EventFilter`/`MessageType`-контракты 0.8.0 — сверяются
   по реальным jar при реализации (как в прошлой миграции).

## 8. Вне объёма

- Менять модель движка, `SessionState`/`Message`, промпты, семантику ReACT-цикла.
- Внедрять компакцию/архивирование событий и TTL-sweeping spring-ai-session (движок
  продолжает сжимать контекст при сборке промпта; событийный лог растёт, как росла и
  JSON-история в `state`).
- `SessionMemoryAdvisor` и их ChatClient-интеграции.
- Модули `intent-reactor-ui-testing-*` (внешний приватный проект).
