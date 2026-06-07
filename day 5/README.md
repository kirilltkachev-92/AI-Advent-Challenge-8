# День 5 — версии моделей

Десктопное приложение на Kotlin Compose сравнивает ответы **одного и того же запроса** на трёх уровнях моделей:

| Уровень | Провайдер | Модель |
|---------|-----------|--------|
| Слабая | HuggingFace | [CohereLabs/tiny-aya-global](https://huggingface.co/CohereLabs/tiny-aya-global) |
| Средняя | HuggingFace | [meta-llama/Llama-3.1-8B-Instruct](https://huggingface.co/meta-llama/Llama-3.1-8B-Instruct) |
| Сильная | DeepSeek | [deepseek-v4-pro](https://api-docs.deepseek.com) |

## Что замеряется

- **Время ответа** (мс / с)
- **Количество токенов** (из API `usage` или оценка)
- **Стоимость** (оценка по публичным тарифам; для HF — приблизительно)

## Режимы

- **Все 3 модели + сравнение** — последовательные запросы к слабой, средней и сильной модели, затем DeepSeek формирует сравнение по качеству, скорости и ресурсоёмкости.
- **Одна выбранная модель** — один запрос к выбранному tier.

## API-ключи

Ключи берутся из:

- переменных окружения `HF_TOKEN` и `DEEPSEEK_API_KEY`

Модели должны быть доступны в [Inference Providers](https://huggingface.co/settings/inference-providers) вашего аккаунта. Список: `curl https://router.huggingface.co/v1/models -H "Authorization: Bearer $HF_TOKEN"`.

Опционально в `.env`:

```
HF_WEAK_MODEL=...
HF_MEDIUM_MODEL=...
DEEPSEEK_STRONG_MODEL=...
```

## Запуск

```bash
cd "day 5"
chmod +x run.sh
./run.sh
```

Или:

```bash
./gradlew run
```

Требуется Java 17.

## Демо-запрос

По умолчанию используется задача-ловушка «5 станков за 5 минут» (правильный ответ — 5 минут, а не 100) — она наглядно показывает разницу в качестве рассуждений между моделями.
