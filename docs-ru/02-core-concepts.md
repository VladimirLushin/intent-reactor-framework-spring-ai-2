# Основные концепции

## Архитектура

```
Запрос пользователя
        │
        ▼
IntentReactorService
        │
        ├──▶ IntentPreprocessor  (анализ намерений)
        │
        ├──▶ Planner  ◀──────────────────────────────┐
        │       │                                    │
        │       ├── REASON  (внутреннее рассуждение) │
        │       ├── ACT  ──▶ ToolProvider            │
        │       │                └── Tool.execute()  │
        │       │                    └── ToolResult ─┘
        │       ├── DONE  ──▶ ответ пользователю
        │       └── FAIL  ──▶ сообщение об ошибке
        │
        └──▶ SessionStateStore  (сохранение состояния)
```

---

## IntentReactorService

Единственная точка входа. Три метода:

```java
// Обработать сообщение (синхронно)
String process(String sessionId, String message);

// Продолжить после подтверждения рискованного действия
ReactorResponse proceedAfterConfirmation(String sessionId, ConfirmationResult result);

// Получить текущее состояние сессии
SessionState getSessionState(String sessionId);
```

---

## Tool

Все инструменты — Spring-бины, реализующие `Tool`. `DefaultToolProvider` обнаруживает их по типу автоматически.

```java
public interface Tool {
    String getName();           // snake_case, уникальное имя для LLM
    String getDescription();    // описание для LLM
    String getSchema();         // JSON Schema параметров
    ToolResult execute(ToolInput input);
    boolean isRisky();          // true = требует подтверждения
    boolean isGenerator();      // true = невидим для LLM (фабрика инструментов)
}
```

Имена инструментов должны быть в формате `snake_case` и однозначно описывать действие. LLM использует имя и описание при выборе инструмента.

---

## SimulatableTool

Расширение `Tool` для стратегии LATS. Позволяет выполнять инструмент в режиме сухого прогона без побочных эффектов:

```java
@Component
public class MyTool implements Tool, SimulatableTool {

    @Override
    public ToolResult execute(ToolInput input) { /* реальное выполнение */ }

    @Override
    public ToolResult simulate(ToolInput input) {
        return ToolResult.ok("Симуляция: операция выполнена успешно.");
    }
}
```

---

## Planner

Планировщик вызывается в цикле — по одному разу за итерацию. Он stateless: вся история хранится в `SessionState.messages`.

```java
public interface Planner {
    PlanStep plan(SessionState session, PlanState planState, List<Tool> tools);
}
```

Типы шагов:

| Тип | Описание |
|---|---|
| `REASON` | Внутреннее рассуждение; не отправляется LLM на следующей итерации |
| `ACT` | Вызов инструмента с параметрами |
| `DONE` | Финальный ответ готов |
| `FAIL` | Задача не может быть выполнена |

---

## IntentPreprocessor

Вызывается перед планировщиком. Классифицирует сообщение пользователя на одно или несколько намерений:

```java
public interface IntentPreprocessor {
    IntentAnalysisResult analyze(String message, SessionState session);
}
```

`IntentAnalysisResult` содержит список `Intent` с полями `name`, `description` и `confidence`. При нескольких намерениях активируется логика мультинамерений.

---

## Сохранение сессий

Движок сохраняет и загружает `SessionState` через фасад `SessionStateStore` из `intent-reactor-core` (пакет `com.intentreactor.core.session`):

```java
public class SessionStateStore {
    Optional<SessionState> findById(String sessionId);
    void save(SessionState sessionState);
    void delete(String sessionId); // no-op, если сессия не найдена
}
```

`SessionStateStore` — внутренний фасад движка, а не точка расширения. Само хранение подключается через `SessionRepository` — persistence-SPI библиотеки spring-ai-session (`org.springframework.ai.session.SessionRepository`, артефакт `org.springaicommunity:spring-ai-session`). Доступны три репозитория: in-memory (по умолчанию), filesystem и JDBC-репозиторий spring-ai-session (опциональная зависимость). Любой кастомный бин `SessionRepository` переопределяет встроенные. Подробнее — в [Хранилищах сессий](05-session-stores.md).

---

## ToolProvider

Определяет, какие инструменты доступны для конкретной сессии:

```java
public interface ToolProvider {
    List<Tool> getTools(SessionState session);
}
```

Пример фильтрации по атрибуту сессии:

```java
@Component
public class RoleBasedToolProvider implements ToolProvider {

    private final List<Tool> allTools;

    @Override
    public List<Tool> getTools(SessionState session) {
        String role = (String) session.getAttributes().get("userRole");
        if ("admin".equals(role)) return allTools;
        return allTools.stream()
            .filter(t -> !t.isRisky())
            .collect(Collectors.toList());
    }
}
```

---

## PromptContextProvider

Инжектирует дополнительные переменные в шаблоны промптов:

```java
@Component
public class TenantContextProvider implements PromptContextProvider {

    @Override
    public Map<String, Object> getContext(SessionState session) {
        return Map.of(
            "tenantName", getTenantName(session),
            "locale", getLocale(session)
        );
    }
}
```

Переменные доступны в шаблоне промпта как `{tenantName}`, `{locale}`.

---

## ConfirmationManager

Управляет логикой «нужно ли подтверждение» для конкретного инструмента и сессии:

```java
public interface ConfirmationManager {
    boolean needsConfirmation(Tool tool, ToolInput input, SessionState session);
    ConfirmationRequest buildRequest(Tool tool, ToolInput input, SessionState session);
}
```

Реализация по умолчанию: `tool.isRisky() && !autonomous`.

---

## Ключевые объекты данных

| Класс | Описание |
|---|---|
| `SessionState` | Всё состояние диалога: сообщения, атрибуты, статус |
| `PlanState` | Состояние текущего планирования: шаги, намерения |
| `PlanStep` | Один шаг: тип + действие или выходные данные |
| `ToolInput` | Параметры для инструмента + идентификатор сессии |
| `ToolResult` | Результат выполнения инструмента: успех или ошибка |
| `IntentAnalysisResult` | Список `Intent` с уровнями уверенности |
| `ReactorResponse` | Финальный ответ: текст + выполненные действия + статус |
