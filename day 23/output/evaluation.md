# День 23 — реранкинг и фильтрация: сравнение режимов

База: статья GPT-3 (arXiv 2005.14165), индекс `structure`. Модель: `deepseek-chat`, temperature 0.

- **baseline** — пайплайн Дня 22: вопрос → top-4 по косинусу → LLM;
- **improved** — query rewrite → top-20 кандидатов → порог косинуса 0.55 → LLM-реранкер (0–10, cross-encoder-паттерн) → порог ≥6 → top-4.

Судья оценивает ответы вслепую по зафиксированному ожиданию: 0 — мимо, 1 — частично, 2 — соответствует.

## Итог

| | baseline (без фильтра) | improved (rewrite + фильтр + реранк) |
|---|---|---|
| баллы судьи (макс 20) | **18** | **20** |
| ответов на 2 балла | 9 | 10 |
| ответов на 0 баллов | 1 | 0 |
| попадание в ожидаемые источники | 12 из 15 | 12 из 15 |
| чанков в контексте (сумма) | 40 | 17 |

---

### Q1. How many parameters does the largest GPT-3 model have, and how many models in total were trained for the paper?

**Ожидание:** 175 billion parameters; 8 models were trained (from 125M to 175B).

**Ожидаемые источники:** «1 Introduction»; «2.1 Model and Architectures»

**Rewrite:** largest GPT-3 model number of parameters 175 billion total number of models trained 8

**Воронка improved:** топ-20 кандидатов → порог косинуса → 20 → реранк → 2 → в контекст 2

| контекст | baseline | improved |
|---|---|---|
| 1 | «1 Introduction» (cos 0.812) | «2.1 Model and Architectures» (rerank 10, cos 0.754) |
| 2 | «B Details of Model Training» (cos 0.785) | «1 Introduction» (rerank 10, cos 0.703) |
| 3 | «6.3 Energy Usage» (cos 0.781) |  |
| 4 | «2.1 Model and Architectures» (cos 0.775) |  |

| режим | балл | источники | вердикт судьи |
|---|---|---|---|
| baseline | 2 | 2/2 | Ответ содержит ключевые факты: 175 млрд параметров и 8 моделей, что полностью соответствует эталону. |
| improved | 2 | 2/2 | Ответ полностью соответствует эталону: указано 175 млрд параметров и 8 моделей. |

<details><summary>Ответ baseline</summary>

The largest GPT-3 model has 175 billion parameters. In total, 8 different sizes of models were trained, ranging from 125 million to 175 billion parameters [2.1 Model and Architectures].

</details>

<details><summary>Ответ improved</summary>

The largest GPT-3 model has 175 billion parameters. In total, 8 different sizes of models were trained, ranging from 125 million to 175 billion parameters [2.1 Model and Architectures].

</details>

---

### Q2. Which datasets make up GPT-3's training mix and with what sampling weights?

**Ожидание:** Common Crawl (filtered) 60%, WebText2 22%, Books1 8%, Books2 8%, Wikipedia 3%.

**Ожидаемые источники:** «2.2 Training Dataset»

**Rewrite:** GPT-3 training dataset composition sampling weights Common Crawl WebText2 Books1 Books2 Wikipedia

**Воронка improved:** топ-20 кандидатов → порог косинуса → 20 → реранк → 1 → в контекст 1

| контекст | baseline | improved |
|---|---|---|
| 1 | «4 Measuring and Preventing Memorization Of Benchmarks» (cos 0.767) | «2.2 Training Dataset» (rerank 10, cos 0.765) |
| 2 | «B Details of Model Training» (cos 0.765) |  |
| 3 | «1 Introduction» (cos 0.739) |  |
| 4 | «3.3 Translation» (cos 0.736) |  |

| режим | балл | источники | вердикт судьи |
|---|---|---|---|
| baseline | 0 | 0/1 | Ответ модели отказывается отвечать, хотя ожидался конкретный ответ с известными данными. |
| improved | 2 | 1/1 | Ответ полностью соответствует эталонному ожиданию, указаны все пять датасетов с правильными весами. |

<details><summary>Ответ baseline</summary>

The provided context chunks do not specify the datasets that make up GPT-3's training mix or their sampling weights. The context discusses model training details, data contamination, and evaluation results, but does not list the training datasets or their proportions. Therefore, I cannot answer this question based on the given information.

</details>

<details><summary>Ответ improved</summary>

Based on the context, GPT-3's training mix consists of Common Crawl (filtered) at 60%, WebText2 at 22%, Books1 at 8%, Books2 at 8%, and Wikipedia at 3% [2.2 Training Dataset]. These weights refer to the fraction of examples drawn from each dataset during training, which is intentionally not proportional to dataset size [2.2 Training Dataset].

</details>

---

### Q3. What exactly do zero-shot, one-shot and few-shot mean in the paper, and how many examples K are typically used in few-shot?

**Ожидание:** Settings without weight updates: 0 / 1 / K demonstrations in context; K is typically 10-100, bounded by the 2048-token context window.

**Ожидаемые источники:** «2 Approach»; «1 Introduction»

**Rewrite:** zero-shot one-shot few-shot definitions number of examples K in-context learning GPT-3

**Воронка improved:** топ-20 кандидатов → порог косинуса → 20 → реранк → 3 → в контекст 3

| контекст | baseline | improved |
|---|---|---|
| 1 | «2 Approach» (cos 0.816) | «2 Approach» (rerank 10, cos 0.807) |
| 2 | «2.4 Evaluation» (cos 0.747) | «1 Introduction» (rerank 10, cos 0.751) |
| 3 | «3.1.1 Language Modeling» (cos 0.715) | «2.4 Evaluation» (rerank 10, cos 0.750) |
| 4 | «3.9.6 Correcting English Grammar» (cos 0.710) |  |

| режим | балл | источники | вердикт судьи |
|---|---|---|---|
| baseline | 2 | 1/2 | Ответ полностью соответствует эталонному ожиданию: верно определены zero-shot, one-shot, few-shot, указан диапазон K (10-100) и ограничение контекстным окном 2048 токенов. |
| improved | 2 | 2/2 | Ответ содержит ключевые факты из эталона: определения zero-shot, one-shot, few-shot; указано, что K обычно 10-100 и ограничено контекстным окном 2048 токенов. Ошибок нет. |

<details><summary>Ответ baseline</summary>

In the paper, zero-shot means the model is given only a natural language instruction with no demonstrations, one-shot provides a single demonstration, and few-shot provides K examples from the task's training set as conditioning [2 Approach]. For few-shot, K can range from 0 up to the maximum allowed by the model's context window (nctx = 2048), which typically fits 10 to 100 examples [2.4 Evaluation]. Larger K values are usually but not always better, and the best K is selected on a development set before testing [2.4 Evaluation].

</details>

<details><summary>Ответ improved</summary>

In the paper, zero-shot means no demonstrations are given, only a natural language instruction describing the task. One-shot allows exactly one demonstration, while few-shot allows as many demonstrations as fit into the model's context window (typically 10 to 100 examples, with K ranging from 0 to 2048 tokens) [1 Introduction][2 Approach]. Few-shot typically uses K values from 10 to 100, and larger K is usually but not always better [2.4 Evaluation].

</details>

---

### Q4. What accuracy does GPT-3 achieve on LAMBADA in the few-shot setting and how does it compare to the previous state of the art?

**Ожидание:** 86.4% accuracy, an 18% absolute improvement over the previous SOTA.

**Ожидаемые источники:** «3.1.2 LAMBADA»

**Rewrite:** GPT-3 few-shot accuracy on LAMBADA dataset compared to previous state-of-the-art results

**Воронка improved:** топ-20 кандидатов → порог косинуса → 20 → реранк → 1 → в контекст 1

| контекст | baseline | improved |
|---|---|---|
| 1 | «3.3 Translation» (cos 0.760) | «3.1.2 LAMBADA» (rerank 10, cos 0.799) |
| 2 | «3.1.2 LAMBADA» (cos 0.750) |  |
| 3 | «3.9.1 Arithmetic» (cos 0.724) |  |
| 4 | «3.1.4 StoryCloze» (cos 0.700) |  |

| режим | балл | источники | вердикт судьи |
|---|---|---|---|
| baseline | 2 | 1/1 | Ответ содержит ключевые факты: 86.4% точности и улучшение на 18% по сравнению с предыдущим SOTA, без ошибок. |
| improved | 2 | 1/1 | Ответ содержит ключевые факты: 86.4% точности и улучшение на 18% по сравнению с предыдущим SOTA, без ошибок. |

<details><summary>Ответ baseline</summary>

GPT-3 achieves 86.4% accuracy on LAMBADA in the few-shot setting, which is an increase of over 18% from the previous state-of-the-art [3.1.2 LAMBADA]. This performance is notably strong, as the few-shot setting allows the model to infer from examples that a one-word completion is desired, addressing a classic issue with the dataset [3.1.2 LAMBADA].

</details>

<details><summary>Ответ improved</summary>

GPT-3 achieves 86.4% accuracy on LAMBADA in the few-shot setting, which is an increase of over 18% from the previous state-of-the-art [3.1.2 LAMBADA]. This performance significantly advances the state of the art, with GPT-3 175B outperforming prior models like Turing-NLG [3.1.2 LAMBADA]. The few-shot setting allows GPT-3 to frame the task as a cloze-test, improving accuracy by 10% compared to its zero-shot performance [3.1.2 LAMBADA].

</details>

---

### Q5. How accurately can humans detect that news articles were generated by the largest GPT-3 model?

**Ожидание:** Mean human accuracy ~52%, barely above chance (50%).

**Ожидаемые источники:** «3.9.4 News Article Generation»; «E Human Quality Assessment of Synthetic News Articles»

**Rewrite:** human detection accuracy news articles generated by GPT-3 175 billion parameter model

**Воронка improved:** топ-20 кандидатов → порог косинуса → 20 → реранк → 1 → в контекст 1

| контекст | baseline | improved |
|---|---|---|
| 1 | «3.9.4 News Article Generation» (cos 0.836) | «3.9.4 News Article Generation» (rerank 10, cos 0.825) |
| 2 | «3.9.4 News Article Generation» (cos 0.814) |  |
| 3 | «E Human Quality Assessment of Synthetic News Articles» (cos 0.787) |  |
| 4 | «E Human Quality Assessment of Synthetic News Articles» (cos 0.783) |  |

| режим | балл | источники | вердикт судьи |
|---|---|---|---|
| baseline | 2 | 2/2 | Ответ содержит ключевые факты из эталона: точность ~52%, чуть выше случайного уровня (50%), и упоминает контрольные модели. |
| improved | 2 | 1/2 | Ответ содержит ключевые факты из эталонного ожидания: точность ~52%, чуть выше случайного уровня (50%), без ошибок. |

<details><summary>Ответ baseline</summary>

Humans detect GPT-3 175B-generated news articles with barely above chance accuracy, around 52% for both short (~200 words) and longer (~500 words) articles [3.9.4 News Article Generation]. This is close to the 50% chance level, while detection of intentionally bad control model articles is ~86-88% [3.9.4 News Article Generation]. Human ability to detect model-generated text decreases as model size increases, with GPT-3 175B being the hardest to distinguish from human-written content [3.9.4 News Article Generation].

</details>

<details><summary>Ответ improved</summary>

Humans detected news articles generated by the largest GPT-3 model (175B parameters) with barely above chance accuracy at ~52%, where 50% is chance level [3.9.4]. This held true even for longer articles around 500 words, where accuracy remained at ~52% [3.9.4]. Human detection ability appears to decrease as model size increases, approaching chance for GPT-3 [3.9.4].

</details>

---

### Q6. How did the authors measure test set contamination and what did they do about it?

**Ожидание:** 13-gram overlap search with training data, 'clean' vs 'dirty' benchmark versions, a bug in the initial filtering; most benchmarks showed little effect.

**Ожидаемые источники:** «4 Measuring and Preventing Memorization Of Benchmarks»; «C Details of Test Set Contamination Studies»

**Rewrite:** test set contamination measurement method, overlap detection, n-gram overlap, filtering, removal of contaminated examples, benchmark evaluation

**Воронка improved:** топ-20 кандидатов → порог косинуса → 20 → реранк → 3 → в контекст 3

| контекст | baseline | improved |
|---|---|---|
| 1 | «C Details of Test Set Contamination Studies» (cos 0.779) | «C Details of Test Set Contamination Studies» (rerank 10, cos 0.822) |
| 2 | «Appendix C.» (cos 0.747) | «Appendix C.» (rerank 8, cos 0.764) |
| 3 | «4 Measuring and Preventing Memorization Of Benchmarks» (cos 0.714) | «4 Measuring and Preventing Memorization Of Benchmarks» (rerank 7, cos 0.710) |
| 4 | «C Details of Test Set Contamination Studies» (cos 0.711) |  |

| режим | балл | источники | вердикт судьи |
|---|---|---|---|
| baseline | 2 | 2/2 | Ответ содержит ключевые факты из эталона: N-граммовый поиск, 'чистые' и 'грязные' версии, ошибка в фильтрации, незначительное влияние на большинство бенчмарков. |
| improved | 2 | 2/2 | Ответ содержит ключевые факты из ожидания: N-граммное перекрытие, чистые/грязные версии, фильтрация, незначительное влияние. Ошибок нет. |

<details><summary>Ответ baseline</summary>

The authors measured test set contamination by computing exact N-gram overlaps between test sets and the full training corpus, using a variable N based on the 5th percentile example length (minimum 8, maximum 13) [C Details of Test Set Contamination Studies]. They defined a 'dirty' example as one with any N-gram overlap with training data [C Details of Test Set Contamination Studies]. To address contamination, they initially attempted to filter training data by removing overlapping 13-grams, but a bug caused only partial removal, and retraining was infeasible due to cost [4 Measuring and Preventing Memorization Of Benchmarks]. Instead, they produced 'clean' versions of benchmarks by removing potentially leaked examples and compared performance on clean vs. full datasets, finding that contamination had negligible effects on most results [Appendix C.].

</details>

<details><summary>Ответ improved</summary>

The authors measured test set contamination by computing exact N-gram overlaps between test sets and the training corpus, using a variable number of words N (with a minimum of 8 and maximum of 13) [C Details of Test Set Contamination Studies]. They defined a 'dirty' example as one with any N-gram overlap with training data [C Details of Test Set Contamination Studies]. To address contamination, they filtered the training set by removing colliding 13-grams and a 200-character window around them, and then evaluated GPT-3 on clean-only subsets to compare performance [C Details of Test Set Contamination Studies]. They found that in most cases, performance changed negligibly, concluding that contamination had little effect on results [Appendix C.].

</details>

---

### Q7. What limitations of GPT-3 do the authors themselves acknowledge?

**Ожидание:** Weaknesses in text synthesis and coherence over long passages, common-sense physics, no bidirectionality, sample inefficiency during pre-training, ambiguity of few-shot 'learning', expensive inference, limited interpretability.

**Ожидаемые источники:** «5 Limitations»

**Rewrite:** limitations of GPT-3 acknowledged by authors Brown et al. in Language Models are Few-Shot Learners

**Воронка improved:** топ-20 кандидатов → порог косинуса → 20 → реранк → 2 → в контекст 2

| контекст | baseline | improved |
|---|---|---|
| 1 | «5 Limitations» (cos 0.822) | «5 Limitations» (rerank 10, cos 0.835) |
| 2 | «6 Broader Impacts» (cos 0.750) | «5 Limitations» (rerank 10, cos 0.783) |
| 3 | «1 Introduction» (cos 0.740) |  |
| 4 | «4 Measuring and Preventing Memorization Of Benchmarks» (cos 0.734) |  |

| режим | балл | источники | вердикт судьи |
|---|---|---|---|
| baseline | 2 | 1/1 | Ответ содержит ключевые факты из эталона: слабости в синтезе текста и связности, физику здравого смысла, отсутствие двунаправленности, а также упоминает неэффективность выборки при предобучении. Ошибок нет. |
| improved | 2 | 1/1 | Ответ содержит все ключевые факты из эталонного ожидания: слабости в синтезе текста и связности длинных отрывков, физику здравого смысла, отсутствие двунаправленности, неэффективность выборки при предобучении, неоднозначность few-shot обучения, дорогой инференс и ограниченную интерпретируемость. |

<details><summary>Ответ baseline</summary>

The authors acknowledge several limitations of GPT-3. First, it has notable weaknesses in text synthesis, such as repeating itself semantically, losing coherence over long passages, and occasionally containing non-sequitur sentences [5 Limitations]. Second, GPT-3 struggles with "common sense physics" questions and performs poorly on some comparison and reading comprehension tasks like WiC and ANLI [5 Limitations]. Third, its autoregressive architecture lacks bidirectionality, which may hinder performance on tasks requiring comparing two pieces of content or re-reading passages [5 Limitations]. Finally, the pretraining objective weights every token equally without prioritizing what is most important to predict [5 Limitations].

</details>

<details><summary>Ответ improved</summary>

The authors acknowledge several limitations of GPT-3, including weaknesses in text synthesis such as semantic repetition, loss of coherence over long passages, and contradictions [5 Limitations]. They also note poor performance on certain NLP tasks like "common sense physics" and comparison tasks (e.g., WIC and ANLI) [5 Limitations]. Structural limitations include the lack of bidirectional architectures, which may hinder performance on tasks requiring comparison or careful reading [5 Limitations]. Additionally, they mention poor sample efficiency during pre-training, ambiguity about whether few-shot learning truly learns new tasks, high inference cost, and issues with interpretability, calibration, and biases [5 Limitations].

</details>

---

### Q8. How much compute was used to train GPT-3 175B?

**Ожидание:** Several thousand petaflop/s-days (~3640 petaflop/s-days, ~3.14e23 flops).

**Ожидаемые источники:** «D Total Compute Used to Train Language Models»; «5 Limitations»

**Rewrite:** GPT-3 175B training compute petaflop/s-days

**Воронка improved:** топ-20 кандидатов → порог косинуса → 20 → реранк → 1 → в контекст 1

| контекст | baseline | improved |
|---|---|---|
| 1 | «6.3 Energy Usage» (cos 0.754) | «6.3 Energy Usage» (rerank 10, cos 0.743) |
| 2 | «D Total Compute Used to Train Language Models» (cos 0.668) |  |
| 3 | «3.9.1 Arithmetic» (cos 0.660) |  |
| 4 | «B Details of Model Training» (cos 0.658) |  |

| режим | балл | источники | вердикт судьи |
|---|---|---|---|
| baseline | 2 | 1/2 | Ответ содержит ключевые факты из ожидания: указано 'several thousand petaflop/s-days' и точное значение 3.64E+03 PF-days, что соответствует эталону. |
| improved | 2 | 0/2 | Ответ содержит ключевой факт из ожидания: 'several thousand petaflop/s-days', что соответствует эталону. |

<details><summary>Ответ baseline</summary>

According to the context, training the GPT-3 175B model consumed several thousand petaflop/s-days of compute during pre-training [6.3 Energy Usage]. Specifically, Table D.1 shows that the total training compute for GPT-3 175B was 3.64E+03 PF-days (petaflop/s-days) [D Total Compute Used to Train Language Models].

</details>

<details><summary>Ответ improved</summary>

According to [6.3 Energy Usage], training the GPT-3 175B model consumed several thousand petaflop/s-days of compute during pre-training.

</details>

---

### Q9. How well does GPT-3 perform on arithmetic tasks like 2-digit and 5-digit addition?

**Ожидание:** Few-shot: ~100% on 2-digit addition, ~98-99% on 2-digit subtraction; accuracy drops sharply with more digits (~10% or less on 5-digit).

**Ожидаемые источники:** «3.9.1 Arithmetic»

**Rewrite:** GPT-3 performance on arithmetic tasks including 2-digit addition and 5-digit addition, accuracy results

**Воронка improved:** топ-20 кандидатов → порог косинуса → 20 → реранк → 2 → в контекст 2

| контекст | baseline | improved |
|---|---|---|
| 1 | «3.9.1 Arithmetic» (cos 0.828) | «3.9.1 Arithmetic» (rerank 10, cos 0.854) |
| 2 | «3.9.1 Arithmetic» (cos 0.815) | «3.9.1 Arithmetic» (rerank 10, cos 0.832) |
| 3 | «3.9 Synthetic and Qualitative Tasks» (cos 0.734) |  |
| 4 | «5 Limitations» (cos 0.733) |  |

| режим | балл | источники | вердикт судьи |
|---|---|---|---|
| baseline | 2 | 1/1 | Ответ содержит ключевые факты из эталона: 100% на 2-значном сложении и ~9.3% на 5-значном, что соответствует ожиданию. |
| improved | 2 | 1/1 | Ответ содержит ключевые факты из эталона: 100% на 2-значном сложении, резкое падение до 9.3% на 5-значном, без ошибок. |

<details><summary>Ответ baseline</summary>

GPT-3 achieves 100% accuracy on 2-digit addition in the few-shot setting, but performance drops to 9.3% on 5-digit addition [3.9.1 Arithmetic]. This shows strong proficiency with small numbers but significant decline as digit count increases [3.9.1 Arithmetic]. The model still demonstrates some capacity to generalize to larger numbers, though accuracy is low [3.9.1 Arithmetic].

</details>

<details><summary>Ответ improved</summary>

In few-shot settings, GPT-3 achieves 100% accuracy on 2-digit addition but only 9.3% on 5-digit addition [3.9.1 Arithmetic]. Performance decreases as the number of digits increases, though the model still shows some ability to generalize to larger numbers [3.9.1 Arithmetic]. The results come from evaluating 2,000 random instances per task [3.9.1 Arithmetic].

</details>

---

### Q10. What context window size does GPT-3 use and what attention pattern is used in its layers?

**Ожидание:** Context window of 2048 tokens; alternating dense and locally banded sparse attention, as in the Sparse Transformer.

**Ожидаемые источники:** «2.1 Model and Architectures»

**Rewrite:** GPT-3 context window size 2048 tokens, attention pattern is causal (masked self-attention) in its layers

**Воронка improved:** топ-20 кандидатов → порог косинуса → 20 → реранк → 1 → в контекст 1

| контекст | baseline | improved |
|---|---|---|
| 1 | «B Details of Model Training» (cos 0.729) | «2.1 Model and Architectures» (rerank 10, cos 0.732) |
| 2 | «3.7 SuperGLUE» (cos 0.726) |  |
| 3 | «2.1 Model and Architectures» (cos 0.718) |  |
| 4 | «3.3 Translation» (cos 0.712) |  |

| режим | балл | источники | вердикт судьи |
|---|---|---|---|
| baseline | 2 | 1/1 | Ответ полностью соответствует эталону: указан размер контекстного окна 2048 токенов и описан паттерн внимания с чередованием плотного и локально-ленточного разреженного внимания. |
| improved | 2 | 1/1 | Ответ полностью соответствует эталону: указан размер контекстного окна 2048 токенов и описан паттерн внимания с чередованием плотного и локально-ленточного разреженного внимания. |

<details><summary>Ответ baseline</summary>

GPT-3 uses a context window of nctx = 2048 tokens [2.1]. Its transformer layers employ alternating dense and locally banded sparse attention patterns, similar to the Sparse Transformer [2.1].

</details>

<details><summary>Ответ improved</summary>

GPT-3 uses a context window of 2048 tokens [2.1]. Its layers employ alternating dense and locally banded sparse attention patterns, similar to the Sparse Transformer [2.1].

</details>

