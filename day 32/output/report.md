# День 32. Автоматизация ревью кода — отчёт

Локальный прогон того же пайплайна, что запускается в GitHub Actions
(`.github/workflows/ai-review.yml`, реактивно по `on: pull_request`).

## Вход: diff `0c35ad5...6cb4ae5`

- [A] `day 31/.env.example`
- [A] `day 31/.gitignore`
- [A] `day 31/README.md`
- [A] `day 31/build.gradle.kts`
- [A] `day 31/demo.sh`
- [A] `day 31/docs/architecture.md`
- [A] `day 31/docs/index-format.md`
- [A] `day 31/docs/mcp-tools.md`
- [A] `day 31/gradle/gradle-daemon-jvm.properties`
- [A] `day 31/gradle/wrapper/gradle-wrapper.jar`
- [A] `day 31/gradle/wrapper/gradle-wrapper.properties`
- [A] `day 31/gradle/wrapper/gradlew`
- [A] `day 31/gradle/wrapper/gradlew.bat`
- [A] `day 31/gradlew`
- [A] `day 31/gradlew.bat`
- [A] `day 31/output/_run.log`
- [A] `day 31/output/report.md`
- [A] `day 31/run.sh`
- [A] `day 31/settings.gradle.kts`
- [A] `day 31/src/main/kotlin/Assistant.kt`
- [A] `day 31/src/main/kotlin/Config.kt`
- [A] `day 31/src/main/kotlin/DocsCorpus.kt`
- [A] `day 31/src/main/kotlin/GitMcp.kt`
- [A] `day 31/src/main/kotlin/IndexStore.kt`
- [A] `day 31/src/main/kotlin/Indexer.kt`
- [A] `day 31/src/main/kotlin/Main.kt`
- [A] `day 31/src/main/kotlin/McpClient.kt`
- [A] `day 31/src/main/kotlin/McpServer.kt`
- [A] `day 31/src/main/kotlin/OllamaEmbedder.kt`
- [A] `day 31/src/main/kotlin/Report.kt`
- [A] `day 31/src/main/kotlin/Search.kt`
- _(diff обрезан до 40000 символов)_

## Контекст RAG (документация + код)

- `day 31/README.md`
- `day 31/src/main/kotlin/GitMcp.kt`
- `day 31/src/main/kotlin/McpServer.kt`
- `day 31/docs/index-format.md`
- `day 31/src/main/kotlin/DocsCorpus.kt`
- `day 31/docs/architecture.md`
- `day 31/docs/mcp-tools.md`
- `day 31/src/main/kotlin/Main.kt`
- `day 31/src/main/kotlin/Assistant.kt`
- `day 31/src/main/kotlin/Report.kt`

## Ревью

## 🐛 Потенциальные баги

- **`Assistant.kt:120` — обрезка ответа по `<|` может сломать корректный текст.**  
  Строка `substringBefore("<｜")` предназначена для удаления служебной разметки DeepSeek, но если в ответе модели встречается символьная последовательность `<|` в легальном контексте (например, в коде или документации), она будет безвозвратно обрезана. Лучше проверять наличие конкретного токена-разделителя (например, `<|end▁of▁text|>`) и обрезать только его.

- **`GitMcp.kt:68` — `git ls-files --others --exclude-standard` может вернуть огромный список.**  
  В инструменте `git_files` нет ограничения на количество возвращаемых строк до вызова `take(200)`. Если в репозитории тысячи неотслеживаемых файлов (например, `build/` не в `.gitignore`), команда `git ls-files` может выполняться долго и вернуть много данных. Ограничение стоит применить на уровне аргументов `git` (например, `head -n 200` через shell) или добавить ранний выход при превышении лимита.

- **`Assistant.kt:100` — `tool_choice: "auto"` может привести к бесконечному циклу вызовов.**  
  Если модель на каждом шаге решает вызвать инструмент, а не дать текстовый ответ, цикл `repeat(maxSteps)` исчерпает лимит и вернёт сообщение об ошибке. Однако при `maxSteps=4` и нескольких инструментах в одном ответе (например, `git_branch` + `git_log`) шаги могут закончиться раньше, чем модель соберёт достаточно контекста. Стоит увеличить `maxSteps` или добавить логику принудительного завершения, если модель вызывает только инструменты без прогресса.

## 🏛 Архитектурные проблемы

- **`Assistant.kt:60-70` — инструменты MCP передаются модели как функции DeepSeek, но без проверки на дубликаты имён.**  
  Если в `mcpTools` окажется два инструмента с одинаковым `name`, последний перезапишет первый в `toolsJson`, и модель не сможет вызвать первый. Хотя в текущей реализации дубликатов нет, отсутствие защиты — потенциальная проблема при расширении.

- **`DocsCorpus.kt:40-50` — сбор README только из папок, начинающихся с `day `.**  
  Если в корне репозитория появятся другие markdown-файлы (например, `CONTRIBUTING.md`), они не попадут в корпус. Это может быть осознанным решением, но в архитектуре не заложена гибкость для добавления дополнительных источников без изменения кода.

- **`McpServer.kt:150-160` — ошибки инструментов возвращаются как `isError=true`, но клиент не проверяет это поле.**  
  В `Assistant.kt:140` результат вызова MCP (`McpToolResult`) содержит поле `isError`, но оно никак не обрабатывается — сообщение об ошибке просто передаётся модели как текст. Модель может проигнорировать ошибку и продолжить, что приведёт к некорректному ответу. Лучше явно помечать такие сообщения в промпте или прерывать цепочку вызовов.

## 💡 Рекомендации

- **`Assistant.kt:120` — заменить `substringBefore("<｜")` на проверку конкретного токена.**  
  Используйте `substringBefore("<｜end▁of▁text|>")` или аналогичный разделитель, специфичный для DeepSeek, чтобы избежать ложных срабатываний.

- **`GitMcp.kt:68` — добавить ограничение на количество строк в `git ls-files`.**  
  Например, передавать `-n 200` через shell или обрезать вывод после 200 строк на уровне вызова `git`. Это предотвратит зависание при большом количестве неотслеживаемых файлов.

- **`Assistant.kt:100` — увеличить `maxSteps` до 6-8 и добавить проверку на повторяющиеся вызовы.**  
  Если модель на двух последовательных шагах вызывает один и тот же инструмент с теми же аргументами, можно прервать цикл и вернуть сообщение о невозможности завершить ответ.

- **`Assistant.kt:140` — обрабатывать `isError` в `McpToolResult`.**  
  Добавить проверку: если `result.isError`, то либо прерывать цепочку вызовов, либо явно сообщать модели, что инструмент вернул ошибку, и просить её ответить на основе уже имеющейся информации.

**Вердикт:** можно мержить

---
_Токены: 23582 промпт / 1149 ответ · модель deepseek-chat_
