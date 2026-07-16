# День 31. Ассистент разработчика — отчёт

## 1. RAG: документация проекта в индексе

- модель эмбеддингов: `nomic-embed-text` (dim 768), стратегия `markdown-section`
- документов: 33, чанков: 224
- ✅ README: 30 файлов (`day 1` … `day 31`)
- ✅ папка docs: `day 31/docs/architecture.md`, `day 31/docs/index-format.md`, `day 31/docs/mcp-tools.md`
  (архитектура, API-описание MCP-инструментов, схема данных индекса)

## 2. MCP: git-контекст проекта

Сервер: `http://localhost:8031/mcp` (JSON-RPC 2.0, Streamable HTTP, вручную).

Инструменты из `tools/list`:
- `git_branch` — Возвращает текущую git-ветку репозитория.
- `git_status` — Краткое состояние рабочего дерева: ветка, изменённые и новые файлы.
- `git_files` — Список файлов, отслеживаемых git. Можно ограничить подкаталогом.
- `git_diff` — Diff незакоммиченных изменений: сводка по файлам и патч. Можно ограничить путём.
- `git_log` — Последние коммиты репозитория (git log --oneline).

✅ `tools/call git_branch` → «Текущая ветка: main»

## 3. /help: вопросы о проекте

### 1. Как устроена структура репозитория? Что лежит в папках day N?

Структура репозитория описана в файле `day 31/docs/architecture.md`:

- **`day 1` … `day 15`** — базовые приёмы: промпты, диалоги, структурированные ответы, сравнение моделей.
- **`day 16`** — MCP-клиент к публичному серверу Microsoft Learn (JSON-RPC 2.0 поверх Streamable HTTP).
- **`day 17`** — собственный MCP-сервер погоды + агент DeepSeek с function calling.
- **`day 18`–`day 20`** — агенты и мультиагентные сценарии поверх MCP.
- **`day 21`–`day 25`** — Неделя 5: RAG-пайплайн (чанки → эмбеддинги → JSON-индекс → top-K поиск), эксперименты со стратегиями чанкинга.
- **`day 26`–`day 30`** — Неделя 6: локальные модели через Ollama, сравнение локальной и облачной генерации, RAG по истории чата марафона, приватный LLM-сервис с токенами и лимитами.
- **`day 31`** — этот проект: ассистент разработчика, понимающий репозиторий.

Каждый день — самостоятельный Kotlin/Gradle-проект в папке `day N` со своим `README.md`, `run.sh` и исходниками в `src/main/kotlin`.

_Источники RAG: `day 31/docs/architecture.md`, `day 29/README.md`, `day 26/README.md`, `day 12/README.md`_

### 2. На какой git-ветке я сейчас и какие последние три коммита?

Вызовы MCP:
- `git_branch({})` → `Текущая ветка: main`
- `git_log({"count": 3})` → `0c35ad5 day 30`

**Текущая ветка:** `main`

**Последние три коммита:**

1. `0c35ad5` — day 30
2. `363fb37` — day 28, day 29: show full answers and per-case input in output
3. `a4c5cd0` — day 29

_Источники RAG: `day 31/docs/architecture.md`, `day 31/README.md`, `day 31/docs/mcp-tools.md`_

### 3. Какие файлы лежат в day 31?

Вызовы MCP:
- `git_files({"dir": "day 31"})` → `Файлов: 173 (в «day 31»)`

Вот ключевые файлы **day 31** (без артефактов сборки `.gradle/` и `build/`):

### Исходники (`src/main/kotlin/`)
| Файл | Назначение |
|---|---|
| `Assistant.kt` | DeepSeek с function calling, системный промпт |
| `Config.kt` | Конфигурация (ключи API, пути) |
| `DocsCorpus.kt` | Сбор README + docs/, чанкинг по заголовкам |
| `GitMcp.kt` | MCP-сервер git: 5 инструментов (branch, status, files, diff, log) |
| `Indexer.kt` | Эмбеддинги → `output/index-docs.json` |
| `IndexStore.kt` | Чтение/запись индекса |
| `Main.kt` | Точка входа (REPL / report) |
| `McpClient.kt` | MCP-клиент (JSON-RPC 2.0) |
| `McpServer.kt` | Базовый MCP-сервер (Streamable HTTP) |
| `OllamaEmbedder.kt` | Вызов `nomic-embed-text` через Ollama |
| `Report.kt` | Режим `report` — чеклист → `output/report.md` |
| `Search.kt` | Поиск top-K: косинус + бонусы за слова |

### Документация (`docs/`)
- `architecture.md` — архитектура проекта
- `index-format.md` — схема данных индекса
- `mcp-tools.md` — описание MCP-инструментов

### Скрипты и конфиги
- `run.sh` — запуск (REPL или report)
- `demo.sh` — демо для видео
- `.env.example` — шаблон с `DEEPSEEK_API_KEY`
- `build.gradle.kts`, `settings.gradle.kts` — Gradle-сборка

### Выходные файлы (`output/`)
- `index-docs.json` — построенный RAG-индекс
- `report.md` — отчёт чеклиста
- `_run.log` — лог последнего запуска

_Источники RAG: `day 31/README.md`, `day 31/docs/architecture.md`, `day 31/docs/index-format.md`_

### 4. Про что день 30 и как его запустить локально?

## День 30 — Локальная LLM как приватный сервис

Смысл: выложить локальную модель (через Ollama) наружу как HTTP API + веб-чат с авторизацией по токену, rate limit'ами и RAG по истории чата марафона (~3900 сообщений). Без токена сервис не стартует.

### Запуск локально

```bash
ollama pull qwen2.5:1.5b && ollama pull nomic-embed-text

cd "day 30"
cp .env.example .env    # и поменять API_TOKENS
./run.sh                # первый старт построит индекс чата (~40 с) и поднимет сервис
./run.sh verify         # чеклист задания → output/report.md
./demo.sh               # демо для видео (сервис должен уже работать)
```

(*day 30/README.md — Запуск локально*)

Модель по умолчанию — `qwen2.5:1.5b` (крутится даже на VPS с 1 vCPU / 2 ГБ RAM). Сервер на `com.sun.net.httpserver` из JDK, без фреймворков.

_Источники RAG: `day 30/README.md`_

### 5. Какие поля у чанка в индексе документации дня 31?

Поля чанка (`IndexedChunk`) в индексе документации дня 31 (`day 31/output/index-docs.json`):

| Поле | Тип | Описание |
|---|---|---|
| `chunk_id` | string | стабильный id, например `day 30/README.md#3` |
| `source` | string | путь файла от корня репозитория |
| `title` | string | заголовок документа (первый `#`) |
| `section` | string | заголовок раздела, из которого взят чанк |
| `strategy` | string | `markdown-section` |
| `char_start`, `char_end` | int | границы чанка в исходном файле |
| `text` | string | текст чанка |
| `embedding` | array\<float\> | нормированный вектор (косинус = скалярное произведение) |

(*[day 31/docs/index-format.md · Поля чанка (IndexedChunk)]*)

_Источники RAG: `day 31/docs/index-format.md`, `day 31/README.md`, `day 21/README.md`_

### 6. Есть ли в проекте деплой в Kubernetes?

Это вопрос-ловушка из чеклиста Дня 31. Смотрю по документации.

**Нет, в проекте нет деплоя в Kubernetes.**

Из документации:
- День 30 описывает **деплой на VPS** через `deploy/setup-vps.sh` и `deploy/deploy.sh` — JRE 17 + Ollama + systemd-сервис (day 30/README.md · Деплой на VPS).
- Ни в одном из фрагментов (Дни 11, 26, 29, 30, 31) не упоминаются Kubernetes, Docker, Podman, контейнеризация или оркестрация.

Всё развёртывание в проекте — либо локальный запуск (`./run.sh`), либо классический VPS с systemd.

_Источники RAG: `day 30/README.md`, `day 26/README.md`, `day 31/README.md`, `day 11/README.md`, `day 29/README.md`_

## Чеклист задания

- ✅ README + папка docs в RAG (п. 1)
- ✅ MCP — git branch и ещё 4 инструмента (п. 2)
- ✅ /help отвечает на вопросы о структуре проекта (п. 3),
  использует документацию (источники под каждым ответом) и контекст
  проекта через MCP (вызовы инструментов под ответами)
