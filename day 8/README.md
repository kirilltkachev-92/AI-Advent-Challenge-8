# День 8 — работа с токенами

Десктопный чат на Kotlin Compose: агент **считает токены** и показывает, как они влияют на стоимость и поведение при переполнении контекста.

**Модель по умолчанию:** `deepseek-v4-flash` (1M контекст, [документация DeepSeek](https://api-docs.deepseek.com/quick_start/pricing)).

## Что считается

| Метрика | Описание |
|---------|----------|
| **Текущий запрос** | Оценка токенов только в поле ввода (до отправки) |
| **История диалога** | Оценка всех сообщений (system + user + assistant) |
| **Ответ модели** | `completion_tokens` из API (или оценка) |
| **Prompt** | `prompt_tokens` из API — вся история, ушедшая в модель |

## API-ключ и модель

```env
DEEPSEEK_API_KEY=sk-...
DEEPSEEK_MODEL=deepseek-v4-flash
```

Лимит контекста для `deepseek-v4-flash`: **1 000 000 токенов** (задаётся автоматически). Переопределение:

```env
CONTEXT_LIMIT_TOKENS=1000000
```

## Запуск

```bash
cd "day 8"
./run.sh
```

## Сценарии

### Короткий / длинный диалог

12 разных вопросов или 2 коротких — реальные API-вызовы, рост токенов в таблице справа.

### Переполнение (deepseek-v4-flash, 1M)

История собирается из **реального open-source кода** (JetBrains/intellij-community, Apache 2.0):

- [`StringUtil.java`](https://github.com/JetBrains/intellij-community/blob/master/platform/util/src/com/intellij/openapi/util/text/StringUtil.java) (~3400 строк)
- [`FileUtil.java`](https://github.com/JetBrains/intellij-community/blob/master/platform/util/src/com/intellij/openapi/util/io/FileUtil.java) (~1500 строк)

Фрагменты циклически добавляются в диалог, пока не наберётся **~1.05M токенов** (30–120 сек, без API). Затем пробный запрос уходит в `deepseek-v4-flash` — ожидается отказ по длине контекста.

**Стоимость одного прогона:** ~$0.14 input (1M × $0.14/1M, cache miss).

**Ускоренная отладка** (не для демонстрации полного 1M):

```env
CONTEXT_LIMIT_TOKENS=10000
```

## Проверка

```bash
./gradlew test
```

Тесты используют малые лимиты (500–5000). Полный 1M прогон — только через UI-кнопку «Переполнение».
