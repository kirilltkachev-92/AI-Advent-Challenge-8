# День 16. Подключение MCP

Минимальный клиент, который **устанавливает MCP-соединение**, **получает список
инструментов** (`tools/list`) и умеет **вызывать инструменты** (`tools/call`) из
интерактивного REPL.

По заданию свой MCP-сервер писать не нужно — берём готовый публичный и дёргаем его.
По умолчанию это **публичный MCP Microsoft Learn** (поиск по документации
Microsoft/Azure/.NET): бесплатный, без ключа, размещён самой Microsoft. Клиент написан
вручную поверх `java.net.http.HttpClient` (без MCP SDK), чтобы был виден сам протокол:
JSON-RPC 2.0 поверх транспорта **Streamable HTTP**.

## Что делает

1. **initialize** — договаривается о версии протокола и возможностях, узнаёт имя/версию сервера.
2. **notifications/initialized** — подтверждает готовность (уведомление, ответа нет).
3. **tools/list** — получает список инструментов и печатает их в консоль.
4. **tools/call** — вызывает выбранный инструмент с аргументами и печатает результат.

## Команды REPL

После подключения открывается консоль `mcp>`:

| Команда                   | Что делает                                              |
| ------------------------- | ------------------------------------------------------- |
| `list`                    | показать список инструментов                            |
| `docs <запрос>`           | поиск по документации (`microsoft_docs_search`)         |
| `call <tool> <json-args>` | вызвать любой инструмент напрямую                       |
| `help`                    | справка                                                 |
| `quit` / `exit`           | выход                                                   |

`docs` заточена под инструмент Microsoft Learn; `list` и `call` универсальны и работают
с любым MCP-сервером на Streamable HTTP.

## Запуск

```bash
cd "day 16"
./run.sh
```

Нужна Java 17 (`brew install openjdk@17`). `run.sh` сам находит JDK; альтернатива —
скопировать `local.properties.example` → `local.properties`.

### Пример вывода

```
→ Подключаюсь к MCP-серверу: https://learn.microsoft.com/api/mcp
✓ Соединение установлено: Microsoft Learn MCP Server 1.0.0 (протокол 2025-06-18)

✓ Доступно инструментов: 3
────────────────────────────────────────────────────────────
1. microsoft_docs_search
   Search official Microsoft/Azure documentation ...
2. microsoft_code_sample_search
   обязательные параметры: query
3. microsoft_docs_fetch
   обязательные параметры: url
────────────────────────────────────────────────────────────

mcp> docs how do azure functions scale
→ Ищу в документации: «how do azure functions scale»

✓ Найдено результатов: 10
────────────────────────────────────────────────────────────
1. Event-driven scaling in Azure Functions
   https://learn.microsoft.com/azure/azure-functions/event-driven-scaling
   # Event-driven scaling in Azure Functions In the Consumption, Flex Consumption ...
...
────────────────────────────────────────────────────────────
```

## Какой сервер используется

Сервер и (при необходимости) токен задаются в `.env` (см. `.env.example`). Без `.env`
используется Microsoft Learn:

| Переменная        | Назначение                                              |
| ----------------- | ------------------------------------------------------- |
| `MCP_SERVER_URL`  | эндпоинт MCP-сервера (Streamable HTTP)                  |
| `MCP_AUTH_TOKEN`  | Bearer-токен (нужен только серверам с авторизацией)     |

Код от сервера не зависит — рукопожатие, `tools/list` и `tools/call` одинаковы для
любого MCP-сервера на Streamable HTTP. Другие публичные серверы без ключа, которые
тоже проверены и работают (поменяйте `MCP_SERVER_URL` в `.env`):

```dotenv
MCP_SERVER_URL=https://knowledge-mcp.global.api.aws   # документация AWS
MCP_SERVER_URL=https://gitmcp.io/facebook/react       # docs Q&A по GitHub-репозиторию
```

### Заметки по реализации

- транспорт **Streamable HTTP**: ответ приходит либо обычным `application/json`, либо
  потоком `text/event-stream` (SSE) — клиент понимает оба формата;
- сервер выдаёт идентификатор сессии в заголовке `Mcp-Session-Id` — клиент возвращает
  его во всех последующих запросах;
- после `initialize` строгие серверы (в т.ч. Microsoft Learn) требуют заголовок
  `MCP-Protocol-Version` с согласованной версией — клиент его шлёт.

## Тесты

```bash
./gradlew test
```

`McpClientTest` без сети проверяет разбор транспорта: что клиент достаёт JSON-RPC-сообщение
и из обычного `application/json`, и из потока `text/event-stream` (SSE).

## Файлы

| Файл                              | Назначение                                              |
| --------------------------------- | ------------------------------------------------------- |
| `src/main/kotlin/McpClient.kt`    | MCP-клиент: рукопожатие, `tools/list`, `tools/call`, SSE |
| `src/main/kotlin/Main.kt`         | REPL: подключение, список инструментов и их вызов       |
| `src/main/kotlin/Config.kt`       | выбор сервера/токена из env и `.env`                    |
| `src/test/kotlin/McpClientTest.kt`| тесты разбора ответа (JSON и SSE)                       |
