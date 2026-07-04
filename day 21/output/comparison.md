# День 21 — сравнение стратегий chunking

Эмбеддинги: `nomic-embed-text` (dim=768, локальная Ollama). Поиск: косинусная близость, top-3.

## 1. Статистика чанков

| метрика | fixed (окно 1200/200) | structure (разделы) |
|---|---|---|
| чанков всего | 238 | 89 |
| средний размер, символов | 1195 | 2662 |
| медиана, символов | 1197 | 3084 |
| мин / макс | 882 / 1200 | 95 / 4000 |
| чанков с метаданным section | 0 | 89 |

## 2. Retrieval на тестовых вопросах

### Q1. What is in-context learning and how does it differ from fine-tuning?

| | fixed | structure |
|---|---|---|
| top-1 score | 0.7679 | 0.7443 |
| mean top-3 | 0.7603 | 0.7059 |
| контекст, символов | 3593 | 8138 |

Найдено (fixed):
- `fixed-gpt3-language-models-are-few-shot-learne-008` (0.768) — gpt3-language-models-are-few-shot-learners.pdf
- `fixed-gpt3-language-models-are-few-shot-learne-011` (0.759) — gpt3-language-models-are-few-shot-learners.pdf
- `fixed-gpt3-language-models-are-few-shot-learne-018` (0.754) — gpt3-language-models-are-few-shot-learners.pdf

Найдено (structure):
- `structure-gpt3-language-models-are-few-shot-learne-004` (0.744) — gpt3-language-models-are-few-shot-learners.pdf, раздел «1 Introduction»
- `structure-gpt3-language-models-are-few-shot-learne-007` (0.705) — gpt3-language-models-are-few-shot-learners.pdf, раздел «2 Approach»
- `structure-gpt3-language-models-are-few-shot-learne-065` (0.668) — gpt3-language-models-are-few-shot-learners.pdf, раздел «C Details of Test Set Contamination Studies»

**Судья (DeepSeek):** победил **fixed** — Контекст A содержит прямое определение in-context learning как внутреннего цикла мета-обучения, а также явное противопоставление fine-tuning (обновление весов на размеченных данных), что полностью покрывает вопрос.

### Q2. What are the zero-shot, one-shot and few-shot evaluation settings?

| | fixed | structure |
|---|---|---|
| top-1 score | 0.8106 | 0.8142 |
| mean top-3 | 0.8041 | 0.7620 |
| контекст, символов | 3596 | 6140 |

Найдено (fixed):
- `fixed-gpt3-language-models-are-few-shot-learne-021` (0.811) — gpt3-language-models-are-few-shot-learners.pdf
- `fixed-gpt3-language-models-are-few-shot-learne-020` (0.802) — gpt3-language-models-are-few-shot-learners.pdf
- `fixed-gpt3-language-models-are-few-shot-learne-023` (0.800) — gpt3-language-models-are-few-shot-learners.pdf

Найдено (structure):
- `structure-gpt3-language-models-are-few-shot-learne-008` (0.814) — gpt3-language-models-are-few-shot-learners.pdf, раздел «2 Approach»
- `structure-gpt3-language-models-are-few-shot-learne-012` (0.747) — gpt3-language-models-are-few-shot-learners.pdf, раздел «2.4 Evaluation»
- `structure-gpt3-language-models-are-few-shot-learne-014` (0.725) — gpt3-language-models-are-few-shot-learners.pdf, раздел «3.1.1 Language Modeling»

**Судья (DeepSeek):** победил **fixed** — Контекст A содержит более полные и структурированные определения zero-shot, one-shot и few-shot, включая их различия и примеры, в то время как контекст B фрагментирован и содержит много лишней информации, не относящейся к вопросу.

### Q3. How many parameters does GPT-3 have and what data was it used to train on?

| | fixed | structure |
|---|---|---|
| top-1 score | 0.7759 | 0.8133 |
| mean top-3 | 0.7700 | 0.7807 |
| контекст, символов | 3586 | 4372 |

Найдено (fixed):
- `fixed-gpt3-language-models-are-few-shot-learne-097` (0.776) — gpt3-language-models-are-few-shot-learners.pdf
- `fixed-gpt3-language-models-are-few-shot-learne-133` (0.771) — gpt3-language-models-are-few-shot-learners.pdf
- `fixed-gpt3-language-models-are-few-shot-learne-149` (0.763) — gpt3-language-models-are-few-shot-learners.pdf

Найдено (structure):
- `structure-gpt3-language-models-are-few-shot-learne-006` (0.813) — gpt3-language-models-are-few-shot-learners.pdf, раздел «1 Introduction»
- `structure-gpt3-language-models-are-few-shot-learne-062` (0.779) — gpt3-language-models-are-few-shot-learners.pdf, раздел «B Details of Model Training»
- `structure-gpt3-language-models-are-few-shot-learne-040` (0.749) — gpt3-language-models-are-few-shot-learners.pdf, раздел «4 Measuring and Preventing Memorization Of Benchmarks»

**Судья (DeepSeek):** победил **fixed** — Контекст A содержит прямое упоминание 'GPT-3 175B' (параметры) и 'Common Crawl' (данные), что отвечает на вопрос. Контекст B не указывает количество параметров и данные для GPT-3.

### Q4. How does GPT-3 perform on machine translation compared to prior unsupervised approaches?

| | fixed | structure |
|---|---|---|
| top-1 score | 0.8105 | 0.8218 |
| mean top-3 | 0.8022 | 0.8078 |
| контекст, символов | 3582 | 9409 |

Найдено (fixed):
- `fixed-gpt3-language-models-are-few-shot-learne-047` (0.810) — gpt3-language-models-are-few-shot-learners.pdf
- `fixed-gpt3-language-models-are-few-shot-learne-106` (0.807) — gpt3-language-models-are-few-shot-learners.pdf
- `fixed-gpt3-language-models-are-few-shot-learne-050` (0.789) — gpt3-language-models-are-few-shot-learners.pdf

Найдено (structure):
- `structure-gpt3-language-models-are-few-shot-learne-021` (0.822) — gpt3-language-models-are-few-shot-learners.pdf, раздел «3.3 Translation»
- `structure-gpt3-language-models-are-few-shot-learne-020` (0.821) — gpt3-language-models-are-few-shot-learners.pdf, раздел «3.3 Translation»
- `structure-gpt3-language-models-are-few-shot-learne-043` (0.781) — gpt3-language-models-are-few-shot-learners.pdf, раздел «5 Limitations»

**Судья (DeepSeek):** победил **structure** — Контекст B содержит полное сравнение GPT-3 с prior unsupervised NMT (включая таблицу BLEU и детали по языковым направлениям), а также обсуждение zero/one/few-shot, что прямо отвечает на вопрос. Контекст A фрагментарен и не даёт целостной картины.

### Q5. How did the authors measure and address data contamination between training and test sets?

| | fixed | structure |
|---|---|---|
| top-1 score | 0.8078 | 0.8238 |
| mean top-3 | 0.7990 | 0.8068 |
| контекст, символов | 3590 | 9848 |

Найдено (fixed):
- `fixed-gpt3-language-models-are-few-shot-learne-093` (0.808) — gpt3-language-models-are-few-shot-learners.pdf
- `fixed-gpt3-language-models-are-few-shot-learne-098` (0.795) — gpt3-language-models-are-few-shot-learners.pdf
- `fixed-gpt3-language-models-are-few-shot-learne-097` (0.795) — gpt3-language-models-are-few-shot-learners.pdf

Найдено (structure):
- `structure-gpt3-language-models-are-few-shot-learne-063` (0.824) — gpt3-language-models-are-few-shot-learners.pdf, раздел «C Details of Test Set Contamination Studies»
- `structure-gpt3-language-models-are-few-shot-learne-039` (0.806) — gpt3-language-models-are-few-shot-learners.pdf, раздел «4 Measuring and Preventing Memorization Of Benchmarks»
- `structure-gpt3-language-models-are-few-shot-learne-040` (0.791) — gpt3-language-models-are-few-shot-learners.pdf, раздел «4 Measuring and Preventing Memorization Of Benchmarks»

**Судья (DeepSeek):** победил **structure** — Контекст B содержит подробное описание методологии: фильтрация по 13-граммам, удаление окон, обработка ложных срабатываний, использование Apache Spark для точных коллизий, определение 'грязных' и 'чистых' примеров, а также анализ влияния на результаты. Контекст A фрагментарен и неполон.

### Q6. What limitations of GPT-3 do the authors acknowledge?

| | fixed | structure |
|---|---|---|
| top-1 score | 0.7895 | 0.8203 |
| mean top-3 | 0.7636 | 0.7682 |
| контекст, символов | 3574 | 6232 |

Найдено (fixed):
- `fixed-gpt3-language-models-are-few-shot-learne-106` (0.790) — gpt3-language-models-are-few-shot-learners.pdf
- `fixed-gpt3-language-models-are-few-shot-learne-114` (0.755) — gpt3-language-models-are-few-shot-learners.pdf
- `fixed-gpt3-language-models-are-few-shot-learne-097` (0.746) — gpt3-language-models-are-few-shot-learners.pdf

Найдено (structure):
- `structure-gpt3-language-models-are-few-shot-learne-043` (0.820) — gpt3-language-models-are-few-shot-learners.pdf, раздел «5 Limitations»
- `structure-gpt3-language-models-are-few-shot-learne-045` (0.743) — gpt3-language-models-are-few-shot-learners.pdf, раздел «6 Broader Impacts»
- `structure-gpt3-language-models-are-few-shot-learne-006` (0.741) — gpt3-language-models-are-few-shot-learners.pdf, раздел «1 Introduction»

**Судья (DeepSeek):** победил **structure** — Контекст B содержит полный раздел 'Limitations', где перечислены все признанные авторами ограничения: слабости в синтезе текста, проблемы с 'common sense physics', пробелы в обучении на нескольких задачах, структурные и алгоритмические ограничения (отсутствие двунаправленности), а также фундаментальное ограничение подхода масштабирования. Контекст A фрагментирован и не включает все эти детали.

## 3. Итог судьи

| fixed | structure | ничья |
|---|---|---|
| 3 | 3 | 0 |
