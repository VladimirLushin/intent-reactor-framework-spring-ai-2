# Хранилища сессий

`SessionState` содержит всё изменяемое состояние разговора — историю сообщений, прогресс планирования и произвольные атрибуты. Движок сохраняет его между вызовами через `SessionStateStore` — фасад из `intent-reactor-core` (пакет `com.intentreactor.core.session`), построенный поверх persistence-SPI **spring-ai-session** — интерфейса `org.springframework.ai.session.SessionRepository` из артефакта `org.springaicommunity:spring-ai-session` 0.8.0.

---

## Модель хранения

spring-ai-session разделяет сессию на данные идентичности/жизненного цикла (`Session`) и append-only историю событий (`SessionEvent`). IntentReactor проецирует свой `SessionState` на эту модель без изменения семантики движка:

| `SessionState` | spring-ai-session |
|---|---|
| История сообщений (`messages`; append-only) | По одному `SessionEvent` на сообщение. Событие оборачивает Spring AI-сообщение: роль `USER` → `UserMessage`, `ASSISTANT` → `AssistantMessage`, `SYSTEM` → `SystemMessage` (маркеры результатов инструментов вида `[TOOL_RESULT]` остаются обычным текстом). Флаг «закреплено» и исходная метка времени сохраняются в metadata события, так что round-trip без потерь. Порядок истории = порядок событий. |
| `planState`, атрибуты, `createdAt`, `updatedAt` | Один вложенный envelope-`Map` в `Session.metadata` под ключом `com.intentreactor.state` |
| `id` | `Session.id`; `userId` = `sessionId` (своих пользователей у фреймворка нет) |
| Истечение | Нет: `expiresAt` равен `null`, сессии не протухают (дефолтный 60-дневный TTL spring-ai-session не применяется) |

### Envelope состояния

Состояние планировщика и атрибуты хранятся как обычный вложенный map — не JSON-строка. Сериализующие репозитории (filesystem, JDBC) пишут envelope как JSON; при загрузке `SessionStateStore` реидратирует типизированные части авто-конфигурируемым `ObjectMapper` (Jackson 3): `planState` восстанавливается из map через `convertValue`, известные типизированные атрибуты тоже пересоздаются (`multiIntentState` → `MultiIntentContext`, `originalIntent` → `IntentAnalysisResult`, `searchTree` → `DefaultSearchTree`). In-memory репозиторий хранит живые объекты как есть, поэтому реидратация там не нужна. Приложение может складывать в атрибуты любые Jackson-сериализуемые значения; после JSON-round-trip они вернутся как сырые map, если для них нет одного из правил реидратации выше.

### Сохранение

`save()` — это upsert строки `Session`/envelope плюс добавление только *новых* событий (дельта считается по числу событий в репозитории). Повторные сохранения одного и того же экземпляра — или сохранения клонов, создаваемых диспетчеризацией мультинамерений, — не дублируют историю. `delete(sessionId)` удаляет сессию вместе с событиями.

### Внутренние ключи атрибутов

Фреймворк записывает в `session.attributes` следующие ключи:

| Ключ | Тип | Назначение |
|---|---|---|
| `"originalIntent"` | `IntentAnalysisResult` | Кэшированное намерение из `analyze()`; сохраняется между итерациями планирования |
| `"pendingStep"` | `PlanStep` (сериализуется как Map) | Шаг, приостановленный в ожидании подтверждения |
| `"confirmationRequestedAt"` | `String` (LocalDateTime) | Когда запрошено подтверждение; используется для проверки таймаута |
| `"pendingModifiedParameters"` | `Map<String, Object>` | Изменённые пользователем параметры из `ConfirmationResult` |
| `"multiIntentState"` | `MultiIntentContext` | Состояние оркестрации при мультинамеренной обработке |
| `"searchTree"` | `SearchTree` | MCTS-дерево LATS; сохраняется между итерациями планирования |
| `"thoughts"` | `List<String>` | Содержимое шагов REASON (в историю сообщений не пишется) |

---

## Сравнение хранилищ

| Хранилище | Как включается | Переживает перезапуск | Где лежат данные | Когда использовать |
|---|---|---|---|---|
| In-memory | По умолчанию (без настройки) | Нет | Куча JVM (`InMemorySessionRepository`) | Разработка, тесты, один stateless-инстанс |
| Filesystem | `intent-reactor.session.store: filesystem` | Да | JSON-файл на сессию в `intent-reactor.session.filesystem.path` | Простая персистентность без БД |
| JDBC | Добавить `spring-ai-starter-session-jdbc` (spring-ai-session) | Да | Таблицы `AI_SESSION` / `AI_SESSION_EVENT` в реляционной БД | Продакшн, несколько инстансов |

---

## Выбор хранилища

Бэкенд выбирается свойством `intent-reactor.session.store`:

```yaml
intent-reactor:
  session:
    store: filesystem      # in-memory (по умолчанию) | filesystem
```

JDBC — это **не** значение свойства: он подключается зависимостью — стартером spring-ai-session (см. [JDBC](#jdbc-аддон-spring-ai-session)). Авто-конфигурация регистрирует ровно один fallback-`SessionRepository`:

- `InMemorySessionRepository` (spring-ai-session) при `store=in-memory` — значение по умолчанию;
- `FileSystemSessionRepository` (IntentReactor) при `store=filesystem`.

Оба бина объявлены с `@ConditionalOnMissingBean(SessionRepository.class)`, поэтому **любой** внешний бин `SessionRepository` — ваш собственный или авто-конфигурируемый JDBC-стартером spring-ai-session — автоматически побеждает fallback-репозитории. Отсюда же: если JDBC-репозиторий присутствует, а `store: filesystem` тоже задан, внешний бин всё равно выигрывает.

---

## In-memory (по умолчанию)

Никакой настройки не требуется — `InMemorySessionRepository` из spring-ai-session держит всё в памяти JVM, поэтому при перезапуске сессии теряются.

```yaml
intent-reactor:
  session:
    store: in-memory
```

---

## Filesystem

Сессии сохраняются как JSON-файлы — по одному на идентификатор сессии — в настраиваемой директории:

```yaml
intent-reactor:
  session:
    store: filesystem
    filesystem:
      path: ./sessions   # относительно рабочей директории; создаётся автоматически
```

Имена файлов — `{sessionId}.json` внутри этой директории. Каждый файл — небольшой envelope: заголовок сессии (`id`, `userId`, `createdAt`, `expiresAt`, `metadata` — включая envelope `com.intentreactor.state`), список `SessionEvent` (роль, содержимое, metadata) и монотонно растущий счётчик версии.

Запись атомарная: пишется временный файл, который затем перемещается поверх целевого (`Files.move` c `ATOMIC_MOVE`; при отсутствии поддержки атомарного перемещения — `REPLACE_EXISTING`). Конкурентные записи в **одну и ту же** сессию сериализуются per-session блокировками в пределах JVM. Файлы сериализуются авто-конфигурируемым `ObjectMapper` (Jackson 3).

**Legacy-файлы 0.1.x конвертируются автоматически.** Файл, записанный версией 0.1.x (целиком сериализованный `SessionState` в корне JSON), распознаётся при первом чтении и переписывается в текущий envelope-формат — конфигурация или ручная миграция не нужны.

---

## JDBC (аддон spring-ai-session)

JDBC-персистентность предоставляет экосистема spring-ai-session, а не отдельный модуль IntentReactor:

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-starter-session-jdbc</artifactId>
    <version>0.8.0</version>
</dependency>
```

При наличии `DataSource` spring-ai-session авто-конфигурирует бин `JdbcSessionRepository`, который имеет приоритет над встроенными fallback-ами. Свойства `intent-reactor.session.*` не нужны — `store` можно оставить по умолчанию.

Используются две таблицы:

- `AI_SESSION` — по строке на сессию: `id`, `user_id`, `created_at`, `expires_at`, `metadata` (JSON-envelope, включая `com.intentreactor.state`), `event_version`.
- `AI_SESSION_EVENT` — append-only история: `seq` (порядок вставки), `id`, `session_id`, `timestamp`, `message_type`, `message_content`, `message_data`, `synthetic`, `archived`, `branch`, `metadata`; внешний ключ на `AI_SESSION` с `ON DELETE CASCADE`.

**Инициализация схемы** управляется свойством spring-ai-session `spring.ai.session.repository.jdbc.initialize-schema` (значения: `embedded` — по умолчанию, только встраиваемые БД; `always`; `never`). При значении по умолчанию и встраиваемой БД (H2) стартер создаёт схему сам. Для внешних БД поставьте `always`, либо отключите инициализацию (`never`) и создайте таблицы вручную — DDL поставляется в артефакте `spring-ai-session-jdbc` как `org/springframework/ai/session/jdbc/schema-{h2,mysql,postgresql}.sql`.

Поддерживаемые БД: PostgreSQL, MySQL, MariaDB и H2.

---

## Кастомное хранилище

Кастомные хранилища реализуют SPI spring-ai-session напрямую — собственного интерфейса хранилищ в IntentReactor больше нет:

```java
public interface SessionRepository {    // org.springframework.ai.session

    Session save(Session session);

    Session findById(String sessionId);

    List<Session> findByUserId(String userId);

    List<String> findExpiredSessionIds(Instant before);

    void delete(String sessionId);

    void appendEvent(SessionEvent event);   // идемпотентно по id события

    boolean compactEvents(String sessionId, List<SessionEvent> archivedEvents,
                          List<SessionEvent> retainedEvents, long expectedVersion);

    long getEventVersion(String sessionId);

    List<SessionEvent> findEvents(String sessionId, EventFilter filter);
}
```

Достаточно объявить реализацию обычным бином — `@Primary` и переключение свойств не нужны, потому что встроенные fallback-бины помечены `@ConditionalOnMissingBean(SessionRepository.class)`:

```java
@Bean
public SessionRepository mySessionRepository() {
    return new MySessionRepository(...);
}
```

Для сессий IntentReactor важны два контрактных момента:

- **Round-trip envelope.** `Session.metadata` (под ключом `com.intentreactor.state`) и события сообщений должны сериализоваться как обычный JSON; типизированные данные движка реидратируются при загрузке автоматически. In-memory кастомный репозиторий может хранить живые объекты как есть.
- **Учёт событий.** События хранятся в порядке добавления, `appendEvent` идемпотентен по id, текущее число событий отдаётся через `getEventVersion` — `SessionStateStore` полагается на него, добавляя при каждом save только новые события.

---

## Миграция с 0.1.x

Версия 0.2.0 заменила собственный persistence-слой на spring-ai-session. Изменение ломающее — для конфигурации хранения:

| Было (0.1.x) | Стало (0.2.0) | Что делать |
|---|---|---|
| Модуль `intent-reactor-session-jdbc` | Удалён | Убрать зависимость; при необходимости добавить `org.springaicommunity:spring-ai-starter-session-jdbc:0.8.0` |
| Модуль `intent-reactor-session-jpa` | Удалён | Использовать filesystem-хранилище или JDBC-стартер spring-ai-session |
| Интерфейс `api.SessionStore` | Удалён. Движок использует фасад `SessionStateStore`; точка расширения — SPI `SessionRepository` | Кастомные хранилища переписать как бины `SessionRepository` |
| Значения `store: jdbc` / `store: jpa` | Не поддерживаются. Без внешнего бина `SessionRepository` контекст падает с `No qualifying bean of type 'SessionRepository'` | Удалить свойство; если были на JDBC — добавить JDBC-стартер spring-ai-session |
| `intent-reactor.session.jdbc.table-name` | Удалено (старая JDBC-реализация его не читала) | Удалить свойство |
| Таблица `intent_reactor_sessions` (JDBC/JPA) | Новым кодом не читается — используются `AI_SESSION` / `AI_SESSION_EVENT` | Перенести данные вручную, если они нужны; иначе таблицу можно удалить |
| Файлы сессий filesystem (целиком `SessionState`-JSON) | При первом чтении автоматически конвертируются в envelope-формат | Ничего делать не нужно |

Если вы оставались на in-memory по умолчанию — действий не требуется: там сессии и раньше были эфемерными.

---

## Совместимость с LATS

Планировщик LATS хранит своё MCTS-дерево в `session.attributes["searchTree"]` между итерациями планирования (см. [strategies/03-lats.md](strategies/03-lats.md)). Так как атрибуты лежат в envelope состояния, поведение зависит от хранилища:

- **In-memory** — дерево остаётся живым объектом между вызовами в рамках JVM, как и раньше.
- **Filesystem / JDBC (JSON-сериализующие репозитории)** — дерево сохраняется как JSON внутри envelope и при каждой загрузке реидратируется в типизированный `DefaultSearchTree` через `ObjectMapper` фреймворка. Частично исследованное дерево переживает перезапуск, и планировщик продолжает работу с ним.

Та же реидратация применяется к остальным типизированным частям envelope: `planState`, `multiIntentState` и `originalIntent`.
