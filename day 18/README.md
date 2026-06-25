# День 18. Планировщик и фоновые задачи

Фоновый **агент-планировщик погоды**, который работает 24/7: по расписанию собирает
замеры через MCP-инструмент (с сохранением в JSON), а периодически — выдаёт
**агрегированную сводку**, которую формулирует агент на DeepSeek.

Развитие Дней 16–17: тот же **рукописный MCP** (JSON-RPC 2.0 поверх Streamable HTTP,
без SDK). Логика планировщика выражена **через MCP-инструменты** — как и советовал
Алексей: *«У MCP есть понятие tool. Покопай в эту сторону»*.

## Что сделано по заданию

| Требование задания | Где в коде |
|---|---|
| 👉 Отложенное/периодическое выполнение | [`Scheduler.kt`](src/main/kotlin/Scheduler.kt) — `ScheduledExecutorService`, два периодических job |
| 👉 Сохранять данные (JSON) | [`SampleStore.kt`](src/main/kotlin/SampleStore.kt) — атомарная запись в `data/samples.json` |
| 👉 Выполняться по расписанию | [`Main.kt`](src/main/kotlin/Main.kt): `scheduler.every("collect"…)` и `every("summary"…)` |
| 👉 Возвращать агрегированный результат | инструмент `weather_summary` → [`WeatherSummary.kt`](src/main/kotlin/WeatherSummary.kt) (средняя/мин/макс/тренд) |
| 👉 Агент 24/7 выдаёт сводку | [`SummaryAgent.kt`](src/main/kotlin/SummaryAgent.kt) — по расписанию зовёт `weather_summary` и формулирует отчёт |

## MCP-инструменты сервера

| Инструмент | Параметры | Что делает |
|---|---|---|
| `get_weather`           | `city` (string, required) | текущая погода (источник данных, без персиста) |
| `record_weather_sample` | `city` (string, required) | снимает замер и **СОХРАНЯЕТ** его в JSON-хранилище |
| `weather_summary`       | `city` (string, required) | **АГРЕГИРОВАННАЯ** сводка по сохранённым замерам |

## Архитектура

```
Main.kt (агент 24/7)
  ├─ McpServer (свой MCP-сервер) ─ tools ─► WeatherApi ─► Open-Meteo
  │     record_weather_sample ─► SampleStore (data/samples.json)   ← СОХРАНЕНИЕ
  │     weather_summary       ─► WeatherSummary.aggregate(...)     ← АГРЕГАЦИЯ
  ├─ Scheduler (ScheduledExecutorService)
  │     • job "collect": каждые COLLECT_INTERVAL_SEC → record_weather_sample
  │     • job "summary": каждые SUMMARY_INTERVAL_SEC → SummaryAgent
  └─ SummaryAgent (DeepSeek) ─► tool_calls ─► weather_summary ─► сводка
```

Планировщик (расписание) — **на стороне агента**; MCP-сервер остаётся адаптером к данным
и хранилищу. Сбор данных детерминированный (работает и без ключа), а финальную сводку
озвучивает LLM-агент, опираясь только на агрегаты инструмента.

## Запуск

```bash
cp .env.example .env      # впишите DEEPSEEK_API_KEY (или подхватится из .env прошлых дней)
./run.sh                  # фоновый агент-планировщик 24/7
```

Что увидите: каждые ~20 c строку `⏱ collect ← Замер … сохранён`, а раз в минуту —
`📊 ПЕРИОДИЧЕСКАЯ СВОДКА` с отчётом агента. Команды REPL поверх работающего планировщика:

- `summary` — выдать сводку прямо сейчас (не дожидаясь расписания);
- `status`  — число запусков каждого job и время следующего тика;
- `samples` — сколько замеров сохранено по городам;
- `list`    — список MCP-инструментов;
- `quit`.

«24/7» = процесс живёт постоянно и периодически срабатывает; для реального режима
поставьте бо́льшие интервалы (см. ниже). Демо-интервалы — короткие, чтобы было видно в кадре.

### Видео-демо одной командой

```bash
./demo.sh                 # короткие интервалы + автокоманды status/samples/summary
```

### Только сервер (для MCP Inspector / деплоя на VPS)

```bash
./run-server.sh           # MCP-сервер на localhost:8765/mcp
# npx @modelcontextprotocol/inspector → Streamable HTTP → http://localhost:8765/mcp
```

## Тесты

```bash
./gradlew test
```

[`McpSchedulerTest.kt`](src/test/kotlin/McpSchedulerTest.kt) (без сети, погода — заглушка):
регистрация трёх инструментов со схемой, сбор+персист, агрегация (средняя/диапазон/тренд),
пустая сводка, `isError=true` при отсутствии `city`, и переживание перезапуска (чтение JSON с диска).

## Конфигурация (`.env` / окружение)

- `DEEPSEEK_API_KEY` — ключ агента-сводки (без него сводка печатается детерминированно, без LLM);
- `WATCH_CITIES` — города через запятую (по умолчанию `Париж,Токио`);
- `COLLECT_INTERVAL_SEC` — период сбора замеров (по умолчанию `20`; для 24/7 — напр. `300`);
- `SUMMARY_INTERVAL_SEC` — период сводки (по умолчанию `60`; для 24/7 — напр. `3600`);
- `DATA_FILE` — путь к JSON-хранилищу (по умолчанию `data/samples.json`);
- `MCP_PORT` / `MCP_SERVER_URL` — порт встроенного или адрес внешнего MCP-сервера.
