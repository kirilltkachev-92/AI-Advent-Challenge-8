# День 24 — цитаты, источники и анти-галлюцинации: проверка

База: статья GPT-3 (arXiv 2005.14165). Пайплайн Дня 23 (rewrite → top-20 → порог 0.55 → реранк ≥6 → top-4), fallback убран: слабый контекст ⇒ «не знаю».

Проверки: источники и дословность цитат — кодом (`Validator`), совпадение смысла ответа с цитатами — слепой судья (0–2).

## Итог по 10 контрольным вопросам

| проверка | результат |
|---|---|
| источники есть и валидны (chunk_id из контекста) | 10/10 |
| цитаты есть | 10/10 |
| все цитаты дословны | 8/10 |
| смысл ответа совпадает с цитатами (2/2) | 8/10 |
| отказ «не знаю» на вопросы мимо базы | 3/3 |

---

### Q1. How many parameters does the largest GPT-3 model have, and how many models in total were trained for the paper?

**Воронка:** 20 → 20 → 2 → 2 | **rewrite:** largest GPT-3 model number of parameters total number of models trained

**Ответ:** The largest GPT-3 model has 175 billion parameters. A total of 8 different sizes of models were trained, ranging from 125 million to 175 billion parameters.

**Источники:** `structure-gpt3-language-models-are-few-shot-learne-009` («2.1 Model and Architectures»); `structure-gpt3-language-models-are-few-shot-learne-005` («1 Introduction»)

**Цитаты:**
- ✓ [`structure-gpt3-language-models-are-few-shot-learne-009`] «we train 8 different sizes of model, ranging over three orders of magnitude from 125 million parameters to 175 billion parameters, with the last being the model we call GPT-3.»
- ✓ [`structure-gpt3-language-models-are-few-shot-learne-005`] «training a 175 billion parameter autoregressive language model, which we call GPT-3»

| источники | цитаты | дословность | смысл ↔ цитаты |
|---|---|---|---|
| ✓ | ✓ | 2/2 | 2/2 — Все утверждения ответа напрямую подтверждены цитатами: размер модели 175 млрд параметров и обучение 8 моделей от 125 млн до 175 млрд параметров. |

---

### Q2. Which datasets make up GPT-3's training mix and with what sampling weights?

**Воронка:** 20 → 20 → 1 → 1 | **rewrite:** GPT-3 training dataset composition sampling weights Common Crawl WebText2 Books1 Books2 Wikipedia

**Ответ:** GPT-3's training mix consists of five datasets: Common Crawl (filtered) with 60% weight, WebText2 with 22%, Books1 with 8%, Books2 with 8%, and Wikipedia with 3%. These weights are not proportional to dataset sizes; higher-quality datasets are sampled more frequently.

**Источники:** `structure-gpt3-language-models-are-few-shot-learne-010` («2.2 Training Dataset»)

**Цитаты:**
- ✗ [`structure-gpt3-language-models-are-few-shot-learne-010`] «Dataset Quantity (tokens) Weight in training mix Common Crawl (filtered) 410 billion 60% WebText2 19 billion 22% Books1 12 billion 8% Books2 55 billion 8% Wikipedia 3 billion 3%»

| источники | цитаты | дословность | смысл ↔ цитаты |
|---|---|---|---|
| ✓ | ✓ | 0/1 | 2/2 — Все утверждения ответа (состав датасетов, их веса и непропорциональность размерам) напрямую следуют из цитаты. |

---

### Q3. What exactly do zero-shot, one-shot and few-shot mean in the paper, and how many examples K are typically used in few-shot?

**Воронка:** 20 → 20 → 2 → 2 | **rewrite:** zero-shot one-shot few-shot definitions number of examples K few-shot setting GPT-3 paper

**Ответ:** Zero-shot (0S) means no demonstrations are allowed and the model is only given a natural language instruction describing the task. One-shot (1S) allows only one demonstration. Few-shot learning allows as many demonstrations as will fit into the model's context window, typically 10 to 100 examples (K). These definitions are given in the introduction and approach sections.

**Источники:** `structure-gpt3-language-models-are-few-shot-learne-008` («2 Approach»); `structure-gpt3-language-models-are-few-shot-learne-005` («1 Introduction»)

**Цитаты:**
- ✓ [`structure-gpt3-language-models-are-few-shot-learne-008`] «• Zero-Shot (0S) is the same as one-shot except that no demonstrations are allowed, and the model is only given a natural language instruction describing the task.»
- ✓ [`structure-gpt3-language-models-are-few-shot-learne-005`] «For each task, we evaluate GPT-3 under 3 conditions: (a) “few-shot learning”, or in-context learning where we allow as many demonstrations as will fit into the model’s context window (typically 10 to 100), (b) “one-shot learning”, where we allow only one demonstration, and (c) “zero-shot” learning, where no demonstrations are allowed and only an instruction in natural language is given to the model.»

| источники | цитаты | дословность | смысл ↔ цитаты |
|---|---|---|---|
| ✓ | ✓ | 2/2 | 2/2 — Все утверждения ответа напрямую следуют из цитат: определение zero-shot, one-shot и few-shot совпадает с текстом цитат. |

---

### Q4. What accuracy does GPT-3 achieve on LAMBADA in the few-shot setting and how does it compare to the previous state of the art?

**Воронка:** 20 → 20 → 1 → 1 | **rewrite:** GPT-3 few-shot accuracy on LAMBADA dataset compared to previous state-of-the-art results

**Ответ:** GPT-3 achieves 86.4% accuracy on LAMBADA in the few-shot setting, which is an increase of over 18% from the previous state-of-the-art. The previous SOTA was 68.0% accuracy, as shown in Table 3.2. This improvement demonstrates that scaling language models remains a promising path for advancing performance on difficult benchmarks.

**Источники:** `structure-gpt3-language-models-are-few-shot-learne-015` («3.1.2 LAMBADA»)

**Цитаты:**
- ✓ [`structure-gpt3-language-models-are-few-shot-learne-015`] «GPT-3 achieves 86.4% accuracy in the few-shot setting, an increase of over 18% from the previous state-of-the-art.»
- ✓ [`structure-gpt3-language-models-are-few-shot-learne-015`] «Setting LAMBADA (acc) ... SOTA 68.0»

| источники | цитаты | дословность | смысл ↔ цитаты |
|---|---|---|---|
| ✓ | ✓ | 2/2 | 2/2 — Все утверждения ответа подтверждены цитатами: точность 86.4% и улучшение на 18% из первой цитаты, предыдущий SOTA 68.0 из второй. |

---

### Q5. How accurately can humans detect that news articles were generated by the largest GPT-3 model?

**Воронка:** 20 → 20 → 1 → 1 | **rewrite:** human detection accuracy news articles generated by GPT-3 175 billion parameter model

**Ответ:** Humans detect news articles generated by the largest GPT-3 model (175B parameters) with barely above chance accuracy, around 52%. This holds for both shorter and longer articles (around 500 words). In contrast, humans detect intentionally bad articles from a control model with ~86-88% accuracy.

**Источники:** `structure-gpt3-language-models-are-few-shot-learne-034` («3.9.4 News Article Generation»)

**Цитаты:**
- ✓ [`structure-gpt3-language-models-are-few-shot-learne-034`] «mean human accuracy at detecting articles that were produced by the 175B parameter model was barely above chance at ∼ 52%»
- ✓ [`structure-gpt3-language-models-are-few-shot-learne-034`] «mean human accuracy at detecting the longer articles that were produced by GPT-3 175B was still barely above chance at ∼ 52%»

| источники | цитаты | дословность | смысл ↔ цитаты |
|---|---|---|---|
| ✓ | ✓ | 2/2 | 2/2 — Оба утверждения ответа (точность ~52% для 175B модели и для длинных статей) напрямую подтверждены цитатами. Утверждение про контрольную модель не содержится в цитатах, но оно не противоречит им и не является частью основного смысла ответа. |

---

### Q6. How did the authors measure test set contamination and what did they do about it?

**Воронка:** 20 → 20 → 3 → 3 | **rewrite:** test set contamination measurement method, overlap detection, n-gram overlap, filtering, removal of contaminated examples, benchmark evaluation

**Ответ:** The authors measured test set contamination by computing exact N-gram overlaps between test sets and the training corpus, using a variable number of words N (minimum 8, maximum 13) based on the 5th percentile example length. They defined a 'dirty' example as one with any N-gram overlap with any training document. To address contamination, they filtered the training set by removing 13-gram overlaps and a 200-character window around them, and discarded documents split into more than 10 pieces. They also evaluated on clean-only examples and compared scores to assess the impact of contamination, finding that in most cases performance changed negligibly.

**Источники:** `structure-gpt3-language-models-are-few-shot-learne-063` («C Details of Test Set Contamination Studies»); `structure-gpt3-language-models-are-few-shot-learne-041` («Appendix C.»)

**Цитаты:**
- ✓ [`structure-gpt3-language-models-are-few-shot-learne-063`] «We compute overlaps between test sets and our full training corpus, even though we only trained on 40% of our filtered Common Crawl documents per Section 2.2.»
- ✓ [`structure-gpt3-language-models-are-few-shot-learne-063`] «We define a 'dirty' example as one with any N -gram overlap with any training document, and a 'clean' example as one with no collision.»
- ✓ [`structure-gpt3-language-models-are-few-shot-learne-063`] «We attempted to remove text occurring in benchmarks from training data by searching for 13−gram overlaps between all test/development sets used in this work and our training data, and we removed the colliding 13−gram as well as a 200 character window around it, splitting the original document into pieces.»
- ✗ [`structure-gpt3-language-models-are-few-shot-learne-063`] «Documents split into more than 10 pieces were considered contaminated and removed entirely.»
- ✓ [`structure-gpt3-language-models-are-few-shot-learne-041`] «We then evaluate GPT-3 on these clean benchmarks, and compare to the original score. If the score on the clean subset is similar to the score on the entire dataset, this suggests that contamination, even if present, does not have a significant effect on reported results.»

| источники | цитаты | дословность | смысл ↔ цитаты |
|---|---|---|---|
| ✓ | ✓ | 4/5 | 1/2 — Утверждение о переменном N (от 8 до 13) не подтверждено цитатами; в цитатах указано только 13-граммы. |

---

### Q7. What limitations of GPT-3 do the authors themselves acknowledge?

**Воронка:** 20 → 20 → 2 → 2 | **rewrite:** limitations of GPT-3 acknowledged by authors Brown et al. in Language Models are Few-Shot Learners

**Ответ:** The authors acknowledge several limitations of GPT-3, including weaknesses in text synthesis such as semantic repetition, loss of coherence over long passages, and contradictions. They note difficulty with 'common sense physics' and poor performance on comparison tasks like WIC and ANLI. Structural limitations include the lack of bidirectionality, which may hinder performance on tasks requiring comparison or careful reading. They also mention poor sample efficiency during pre-training, ambiguity about whether few-shot learning learns new tasks or recognizes prior ones, high inference cost, and issues with interpretability, calibration, and biases.

**Источники:** `structure-gpt3-language-models-are-few-shot-learne-043` («5 Limitations»); `structure-gpt3-language-models-are-few-shot-learne-044` («5 Limitations»)

**Цитаты:**
- ✓ [`structure-gpt3-language-models-are-few-shot-learne-043`] «GPT-3 samples still sometimes repeat themselves semantically at the document level, start to lose coherence over sufficiently long passages, contradict themselves, and occasionally contain non-sequitur sentences or paragraphs.»
- ✓ [`structure-gpt3-language-models-are-few-shot-learne-043`] «GPT-3 seems to have special difficulty with 'common sense physics'»
- ✓ [`structure-gpt3-language-models-are-few-shot-learne-043`] «it does little better than chance when evaluated one-shot or even few-shot on some 'comparison' tasks, such as determining if two words are used the same way in a sentence, or if one sentence implies another (WIC and ANLI respectively)»
- ✓ [`structure-gpt3-language-models-are-few-shot-learne-043`] «our experiments do not include any bidirectional architectures or other training objectives such as denoising.»
- ✓ [`structure-gpt3-language-models-are-few-shot-learne-044`] «Another limitation broadly shared by language models is poor sample efficiency during pre-training.»
- ✓ [`structure-gpt3-language-models-are-few-shot-learne-044`] «ambiguity about whether few-shot learning actually learns new tasks 'from scratch' at inference time, or if it simply recognizes and identifies tasks that it has learned during training.»
- ✓ [`structure-gpt3-language-models-are-few-shot-learne-044`] «they are both expensive and inconvenient to perform inference on»
- ✓ [`structure-gpt3-language-models-are-few-shot-learne-044`] «its decisions are not easily interpretable, it is not necessarily well-calibrated in its predictions on novel inputs ... and it retains the biases of the data it has been trained on.»

| источники | цитаты | дословность | смысл ↔ цитаты |
|---|---|---|---|
| ✓ | ✓ | 8/8 | 2/2 — Каждое утверждение ответа напрямую подтверждается цитатами: семантические повторы, потеря связности, противоречия, сложности с физикой, плохие результаты на WIC и ANLI, отсутствие двунаправленности, низкая эффективность выборки, неоднозначность few-shot обучения, высокая стоимость инференса, проблемы интерпретируемости, калибровки и предвзятости. |

---

### Q8. How much compute was used to train GPT-3 175B?

**Воронка:** 20 → 20 → 1 → 1 | **rewrite:** GPT-3 175B training compute petaflop/s-days

**Ответ:** Training the GPT-3 175B model consumed several thousand petaflop/s-days of compute during pre-training, compared to tens of petaflop/s-days for a 1.5B parameter GPT-2 model.

**Источники:** `structure-gpt3-language-models-are-few-shot-learne-055` («6.3 Energy Usage»)

**Цитаты:**
- ✓ [`structure-gpt3-language-models-are-few-shot-learne-055`] «training the GPT-3 175B consumed several thousand petaflop/s-days of compute during pre-training, compared to tens of petaflop/s-days for a 1.5B parameter GPT-2 model»

| источники | цитаты | дословность | смысл ↔ цитаты |
|---|---|---|---|
| ✓ | ✓ | 1/1 | 2/2 — Ответ дословно повторяет цитату, каждое утверждение подкреплено. |

---

### Q9. How well does GPT-3 perform on arithmetic tasks like 2-digit and 5-digit addition?

**Воронка:** 20 → 20 → 2 → 2 | **rewrite:** GPT-3 performance on arithmetic tasks such as two-digit addition and five-digit addition

**Ответ:** GPT-3 achieves 100% accuracy on 2-digit addition and 9.3% accuracy on 5-digit addition in the few-shot setting. Performance decreases as the number of digits increases, but the model still shows some capacity to generalize to larger numbers. The model's performance improves from zero-shot to one-shot to few-shot settings.

**Источники:** `structure-gpt3-language-models-are-few-shot-learne-029` («3.9.1 Arithmetic»); `structure-gpt3-language-models-are-few-shot-learne-030` («3.9.1 Arithmetic»)

**Цитаты:**
- ✓ [`structure-gpt3-language-models-are-few-shot-learne-029`] «GPT-3 displays strong proficiency when the number of digits is small, achieving 100% accuracy on 2 digit addition»
- ✓ [`structure-gpt3-language-models-are-few-shot-learne-030`] «GPT-3 Few-shot 100.0 98.9 80.4 94.2 25.5 26.8 9.3 9.9 29.2 21.3»
- ✓ [`structure-gpt3-language-models-are-few-shot-learne-029`] «Performance decreases as the number of digits increases, but GPT-3 still achieves 25-26% accuracy on four digit operations and 9-10% accuracy on five digit operations, suggesting at least some capacity to generalize to larger numbers of digits.»

| источники | цитаты | дословность | смысл ↔ цитаты |
|---|---|---|---|
| ✓ | ✓ | 3/3 | 1/2 — Утверждение о 9.3% точности на 5-значном сложении подтверждено цитатой, но утверждение об улучшении от zero-shot к one-shot и few-shot не подкреплено цитатами. |

---

### Q10. What context window size does GPT-3 use and what attention pattern is used in its layers?

**Воронка:** 20 → 20 → 1 → 1 | **rewrite:** GPT-3 context window size 2048 tokens, attention pattern is causal (masked self-attention) in its layers

**Ответ:** GPT-3 uses a context window of 2048 tokens. Its layers use alternating dense and locally banded sparse attention patterns, similar to the Sparse Transformer.

**Источники:** `structure-gpt3-language-models-are-few-shot-learne-009` («2.1 Model and Architectures»)

**Цитаты:**
- ✓ [`structure-gpt3-language-models-are-few-shot-learne-009`] «All models use a context window of nctx = 2048 tokens.»
- ✓ [`structure-gpt3-language-models-are-few-shot-learne-009`] «we use alternating dense and locally banded sparse attention patterns in the layers of the transformer, similar to the Sparse Transformer»

| источники | цитаты | дословность | смысл ↔ цитаты |
|---|---|---|---|
| ✓ | ✓ | 2/2 | 2/2 — Оба утверждения ответа напрямую подтверждены цитатами: контекстное окно 2048 токенов и чередование плотного и разреженного внимания. |

---

## Режим «не знаю» (вопросы мимо базы)

### Какая столица Франции?

**Отказ:** ✓ — сработал порог релевантности (реранк не пропустил ни одного чанка), генератор не вызывался.

> Не знаю: в базе (статья GPT-3) не нашлось достаточно релевантного контекста для этого вопроса. Уточните, пожалуйста, вопрос — например, какой раздел или аспект статьи вас интересует.

### How do I cook a perfect risotto?

**Отказ:** ✓ — сработал порог релевантности (реранк не пропустил ни одного чанка), генератор не вызывался.

> Не знаю: в базе (статья GPT-3) не нашлось достаточно релевантного контекста для этого вопроса. Уточните, пожалуйста, вопрос — например, какой раздел или аспект статьи вас интересует.

### What score does GPT-4 achieve on the bar exam?

**Отказ:** ✓ — сработал порог релевантности (реранк не пропустил ни одного чанка), генератор не вызывался.

> Не знаю: в базе (статья GPT-3) не нашлось достаточно релевантного контекста для этого вопроса. Уточните, пожалуйста, вопрос — например, какой раздел или аспект статьи вас интересует.
