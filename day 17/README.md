# День 17. Первый инструмент MCP

Свой **MCP-сервер** вокруг публичного API погоды [Open-Meteo](https://open-meteo.com/)
(бесплатный, без ключа) + **агент** на DeepSeek, который сам решает вызвать инструмент
и использует результат в ответе.

Сервер написан **вручную** поверх JSON-RPC 2.0 и транспорта Streamable HTTP —
без MCP SDK и без оберток (mcpany/openmcp), чтобы протокол был виден целиком.
Это зеркало Дня 16, где так же вручную был написан MCP-**клиент**.

## Что сделано по заданию

| Требование задания | Где в коде |
|---|---|
| 👉 Регистрация инструмента | `McpServer.register(...)` + `McpServer.weatherServer()` в [`McpServer.kt`](src/main/kotlin/McpServer.kt) |
| 👉 Описание входных параметров | поле `inputSchema` (JSON Schema: `properties`, `required`) каждого `McpToolDef` |
| 👉 Возврат результата | `tools/call` → блок `content: [{type:"text", text:...}]` + флаг `isError` |
| 👉 Подключение к агенту | [`WeatherAgent.kt`](src/main/kotlin/WeatherAgent.kt): `tools/list` → функции DeepSeek |
| 👉 Вызов из приложения | [`Main.kt`](src/main/kotlin/Main.kt) — REPL, вопрос про погоду |
| 👉 Получение и использование результата | агент кладёт ответ инструмента в контекст и формулирует финальный ответ |

## Инструменты сервера

| Инструмент | Параметры | Что возвращает |
|---|---|---|
| `get_weather`  | `city` (string, required) | текущая погода: температура, ветер, состояние неба |
| `geocode_city` | `city` (string, required) | координаты города и часовой пояс |

## Архитектура

```
Main.kt (приложение)
  ├─ McpServer (свой MCP-сервер) ──► WeatherApi ──► Open-Meteo (geocoding + forecast)
  │     initialize / tools/list / tools/call   (JSON-RPC 2.0 поверх HTTP, порт 8765)
  ├─ McpClient ──► подключается к серверу (initialize → tools/list → tools/call)
  └─ WeatherAgent (DeepSeek) ──► tool_calls ──► McpClient.callTool ──► результат ──► ответ
```

Приложение по умолчанию само поднимает **встроенный** MCP-сервер на `localhost:8765`.
Если задеплоить сервер отдельно (VPS), укажите `MCP_SERVER_URL` — агент пойдёт по сети.

## Запуск

```bash
cp .env.example .env      # впишите DEEPSEEK_API_KEY (или он подхватится из .env прошлых дней)
./run.sh                  # приложение-агент (встроенный сервер + агент)
```

Пример диалога:

```
you> Какая сейчас погода в Париже и стоит ли брать зонт?
→ Агент думает…
  ⚙ MCP-вызов: get_weather {"city": "Париж"}
    ← Погода в Париж (Франция): преимущественно ясно, температура 39.1°C, ветер 4.4 км/ч…
────────────────────────────────────────────────────────────
Сейчас в Париже преимущественно ясно, +39°C, ветер лёгкий. Дождя не ожидается —
зонт не понадобится. А вот вода и крем от солнца — да! 😊
```

Команды REPL:
- `<вопрос про погоду>` — главный сценарий: агент сам решает, звать ли инструмент;
- `tool get_weather {"city":"Токио"}` — прямой вызов инструмента (демо без LLM);
- `list`, `help`, `quit`.

### Только сервер (для MCP Inspector / деплоя на VPS)

```bash
./run-server.sh           # поднимает только MCP-сервер на localhost:8765/mcp
# проверка: npx @modelcontextprotocol/inspector → Streamable HTTP → http://localhost:8765/mcp
```

## Тесты

```bash
./gradlew test
```

[`McpServerTest.kt`](src/test/kotlin/McpServerTest.kt) проверяет ядро сервера на уровне
JSON-RPC без сети: рукопожатие, `tools/list` со схемой, `tools/call`, ошибку инструмента
(`isError=true`), неизвестный метод (`-32601`) и разбор SSE/JSON в клиенте.

## Конфигурация (`.env`)

- `DEEPSEEK_API_KEY` — ключ агента-LLM (без него работает только прямой `tool`-вызов);
- `MCP_PORT` — порт встроенного сервера (по умолчанию `8765`);
- `MCP_SERVER_URL` — внешний сервер вместо встроенного (напр. `http://VPS_IP:8765/mcp`).
