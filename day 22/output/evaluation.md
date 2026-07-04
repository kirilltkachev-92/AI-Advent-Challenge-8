# День 22 — сравнение качества: с RAG / без RAG

База: статья GPT-3 (arXiv 2005.14165), индекс `structure` из Дня 21, top-4.
Модель в обоих режимах: `deepseek-chat`, temperature 0. Судья оценивает ответы вслепую по зафиксированному ожиданию: 0 — мимо, 1 — частично, 2 — соответствует.

## Итог

| | без RAG | с RAG |
|---|---|---|
| баллы судьи (макс 20) | **16** | **18** |
| ответов на 2 балла | 8 | 9 |
| ответов на 0 баллов | 2 | 1 |
| попадание в ожидаемые источники | — | 12 из 15 |

---

### Q1. How many parameters does the largest GPT-3 model have, and how many models in total were trained for the paper?

**Ожидание:** 175 billion parameters; 8 models were trained (from 125M to 175B).

**Ожидаемые источники:** «1 Introduction»; «2.1 Model and Architectures»

**Извлечено (RAG):** «1 Introduction» (0.812); «B Details of Model Training» (0.785); «6.3 Energy Usage» (0.781); «2.1 Model and Architectures» (0.775)

**Попадание в источники:** 2/2 («1 Introduction»; «2.1 Model and Architectures»)

| режим | балл | вердикт судьи |
|---|---|---|
| без RAG | 2 | Ответ полностью соответствует эталону: указаны 175 млрд параметров и 8 моделей. |
| с RAG | 2 | Ответ полностью соответствует эталону: указано 175 млрд параметров и 8 моделей. |

<details><summary>Ответ без RAG</summary>

The largest GPT-3 model has 175 billion parameters. In total, the paper describes training eight different model sizes, ranging from 125 million to 175 billion parameters. These models were trained to study the effects of scale on few-shot learning performance.

</details>

<details><summary>Ответ с RAG</summary>

The largest GPT-3 model has 175 billion parameters. In total, 8 different sizes of models were trained, ranging from 125 million to 175 billion parameters [2.1 Model and Architectures].

</details>

---

### Q2. Which datasets make up GPT-3's training mix and with what sampling weights?

**Ожидание:** Common Crawl (filtered) 60%, WebText2 22%, Books1 8%, Books2 8%, Wikipedia 3%.

**Ожидаемые источники:** «2.2 Training Dataset»

**Извлечено (RAG):** «4 Measuring and Preventing Memorization Of Benchmarks» (0.767); «B Details of Model Training» (0.765); «1 Introduction» (0.739); «3.3 Translation» (0.736)

**Попадание в источники:** 0/1

| режим | балл | вердикт судьи |
|---|---|---|
| без RAG | 2 | Ответ полностью соответствует эталонному ожиданию, перечислены все пять наборов данных с правильными весами выборки. |
| с RAG | 0 | Ответ модели не содержит запрошенной информации, хотя ожидался конкретный ответ с данными. |

<details><summary>Ответ без RAG</summary>

GPT-3 was trained on a mixture of five datasets: Common Crawl (weighted at 60% during training), WebText2 (22%), Books1 (8%), Books2 (8%), and Wikipedia (3%). These weights were used for sampling during training, not proportional to dataset size, to balance quality and diversity. The exact proportions were chosen to upweight higher-quality sources like books and Wikipedia relative to their raw size in the corpus.

</details>

<details><summary>Ответ с RAG</summary>

The provided context chunks do not contain information about which datasets make up GPT-3's training mix or their sampling weights. The context discusses model training details, data contamination, and evaluation results, but does not specify the composition or weighting of the training datasets.

</details>

---

### Q3. What exactly do zero-shot, one-shot and few-shot mean in the paper, and how many examples K are typically used in few-shot?

**Ожидание:** Settings without weight updates: 0 / 1 / K demonstrations in context; K is typically 10-100, bounded by the 2048-token context window.

**Ожидаемые источники:** «2 Approach»; «1 Introduction»

**Извлечено (RAG):** «2 Approach» (0.816); «2.4 Evaluation» (0.747); «3.1.1 Language Modeling» (0.715); «3.9.6 Correcting English Grammar» (0.710)

**Попадание в источники:** 1/2 («2 Approach»)

| режим | балл | вердикт судьи |
|---|---|---|
| без RAG | 2 | Ответ полностью соответствует эталонному ожиданию: верно описаны zero-shot, one-shot и few-shot, указан типичный диапазон K (10-100) и контекст без обновления весов. |
| с RAG | 2 | Ответ содержит все ключевые факты из эталона: определения zero-shot, one-shot, few-shot; диапазон K от 10 до 100; ограничение контекстным окном 2048 токенов. Ошибок нет. |

<details><summary>Ответ без RAG</summary>

In the paper, zero-shot refers to the model performing a task with no examples provided, only a natural language instruction. One-shot means the model is given a single example (K=1) of the task before making a prediction. Few-shot involves providing the model with a small number of examples (typically K=10 to 100) in the prompt, without any gradient updates. The authors emphasize that few-shot learning in this context is purely in-context learning, not fine-tuning.

</details>

<details><summary>Ответ с RAG</summary>

In the paper, zero-shot (0S) means the model is given only a natural language instruction with no demonstrations, one-shot provides a single example, and few-shot provides K examples from the task's training set as conditioning [2 Approach]. For few-shot, K can range from 0 up to the maximum allowed by the model's context window (nctx = 2048), which typically fits 10 to 100 examples [2.4 Evaluation]. Larger values of K are usually but not always better, so the best value is often selected on a development set before testing [2.4 Evaluation].

</details>

---

### Q4. What accuracy does GPT-3 achieve on LAMBADA in the few-shot setting and how does it compare to the previous state of the art?

**Ожидание:** 86.4% accuracy, an 18% absolute improvement over the previous SOTA.

**Ожидаемые источники:** «3.1.2 LAMBADA»

**Извлечено (RAG):** «3.3 Translation» (0.760); «3.1.2 LAMBADA» (0.750); «3.9.1 Arithmetic» (0.724); «3.1.4 StoryCloze» (0.700)

**Попадание в источники:** 1/1 («3.1.2 LAMBADA»)

| режим | балл | вердикт судьи |
|---|---|---|
| без RAG | 0 | Ответ модели содержит неверные цифры (76.2% и 68%), в то время как эталонные значения — 86.4% и 18% абсолютного улучшения. |
| с RAG | 2 | Ответ содержит ключевые факты: 86.4% accuracy и 18% абсолютное улучшение по сравнению с предыдущим SOTA, без ошибок. |

<details><summary>Ответ без RAG</summary>

In the few-shot setting, GPT-3 achieves 76.2% accuracy on the LAMBADA dataset. This is a significant improvement over the previous state-of-the-art result of 68% from a fine-tuned model. The paper notes that GPT-3's performance on LAMBADA is particularly notable because it requires understanding long-range dependencies in text.

</details>

<details><summary>Ответ с RAG</summary>

GPT-3 achieves 86.4% accuracy on LAMBADA in the few-shot setting, which is an increase of over 18% from the previous state-of-the-art [3.1.2 LAMBADA]. This performance is notably higher than the zero-shot setting (76.2%) and the one-shot setting (72.5%) [3.1.2 LAMBADA]. The few-shot setting allows the model to infer from examples that a completion of exactly one word is desired, addressing a classic problem with the dataset [3.1.2 LAMBADA].

</details>

---

### Q5. How accurately can humans detect that news articles were generated by the largest GPT-3 model?

**Ожидание:** Mean human accuracy ~52%, barely above chance (50%).

**Ожидаемые источники:** «3.9.4 News Article Generation»; «E Human Quality Assessment of Synthetic News Articles»

**Извлечено (RAG):** «3.9.4 News Article Generation» (0.836); «3.9.4 News Article Generation» (0.814); «E Human Quality Assessment of Synthetic News Articles» (0.787); «E Human Quality Assessment of Synthetic News Articles» (0.783)

**Попадание в источники:** 2/2 («3.9.4 News Article Generation»; «E Human Quality Assessment of Synthetic News Articles»)

| режим | балл | вердикт судьи |
|---|---|---|
| без RAG | 2 | Ответ полностью соответствует эталонному ожиданию: указана точность ~52% и что это чуть выше случайного угадывания (50%). |
| с RAG | 2 | Ответ содержит ключевые факты из эталона: точность ~52%, чуть выше случайного угадывания (50%), для коротких и длинных статей. |

<details><summary>Ответ без RAG</summary>

Humans detected GPT-3-generated news articles with an accuracy of approximately 52%, which is only slightly above chance (50%). This was tested in a task where participants had to distinguish between human-written and GPT-3-written articles. The result indicates that even the largest GPT-3 model can produce text that is nearly indistinguishable from human writing in this domain.

</details>

<details><summary>Ответ с RAG</summary>

Humans detected GPT-3 175B-generated short news articles (~200 words) with only ~52% accuracy, barely above chance [3.9.4]. For longer articles (~500 words), accuracy remained at ~52% [3.9.4]. This indicates that humans find GPT-3 175B articles very difficult to distinguish from human-written ones [3.9.4].

</details>

---

### Q6. How did the authors measure test set contamination and what did they do about it?

**Ожидание:** 13-gram overlap search with training data, 'clean' vs 'dirty' benchmark versions, a bug in the initial filtering; most benchmarks showed little effect.

**Ожидаемые источники:** «4 Measuring and Preventing Memorization Of Benchmarks»; «C Details of Test Set Contamination Studies»

**Извлечено (RAG):** «C Details of Test Set Contamination Studies» (0.779); «Appendix C.» (0.747); «4 Measuring and Preventing Memorization Of Benchmarks» (0.714); «C Details of Test Set Contamination Studies» (0.711)

**Попадание в источники:** 2/2 («4 Measuring and Preventing Memorization Of Benchmarks»; «C Details of Test Set Contamination Studies»)

| режим | балл | вердикт судьи |
|---|---|---|
| без RAG | 2 | Ответ содержит ключевые факты: измерение через поиск совпадений подстрок, создание 'чистой' версии бенчмарков, упоминание влияния на few-shot производительность. Соответствует эталонному ожиданию без ошибок. |
| с RAG | 2 | Ответ содержит ключевые факты из ожидания: N-граммный поиск, чистые/грязные версии, незначительное влияние. Упомянуты детали (переменная N, определение 'грязного' примера), что соответствует эталону. |

<details><summary>Ответ без RAG</summary>

The authors measured test set contamination by checking for overlap between their training data (Common Crawl) and the benchmark test sets using substring matching. They found that some benchmarks had significant overlap, which could inflate few-shot performance. To address this, they created a "clean" version of certain benchmarks by removing any test examples that appeared in the training data. They also reported results on both the original and clean versions to show the impact of contamination.

</details>

<details><summary>Ответ с RAG</summary>

The authors measured test set contamination by searching for N-gram overlaps between test/development sets and the training data, using a variable N based on the 5th percentile example length (minimum 8, maximum 13) [C Details of Test Set Contamination Studies]. They defined a "dirty" example as one with any N-gram overlap with a training document [C Details of Test Set Contamination Studies]. To address contamination, they produced a "clean" version of each benchmark by removing all potentially leaked examples, then evaluated GPT-3 on these clean subsets to compare performance [Appendix C.]. They found that in most cases, performance changed negligibly, suggesting contamination had little effect on reported results [Appendix C.].

</details>

---

### Q7. What limitations of GPT-3 do the authors themselves acknowledge?

**Ожидание:** Weaknesses in text synthesis and coherence over long passages, common-sense physics, no bidirectionality, sample inefficiency during pre-training, ambiguity of few-shot 'learning', expensive inference, limited interpretability.

**Ожидаемые источники:** «5 Limitations»

**Извлечено (RAG):** «5 Limitations» (0.822); «6 Broader Impacts» (0.750); «1 Introduction» (0.740); «4 Measuring and Preventing Memorization Of Benchmarks» (0.734)

**Попадание в источники:** 1/1 («5 Limitations»)

| режим | балл | вердикт судьи |
|---|---|---|
| без RAG | 2 | Ответ содержит ключевые факты из эталонного ожидания: слабости в логическом мышлении и здравом смысле, неэффективность выборки, предвзятости, повторяемость и непоследовательность в длинных текстах, а также ограничения few-shot обучения. |
| с RAG | 2 | Ответ содержит ключевые факты из эталонного ожидания: слабости в синтезе текста и связности, физику здравого смысла, отсутствие двунаправленности, неэффективность выборки при предобучении. Отсутствуют упоминания о неоднозначности few-shot обучения, дороговизне инференса и ограниченной интерпретируемости, но это не является ошибкой, так как ответ частично охватывает ожидание. |

<details><summary>Ответ без RAG</summary>

The authors acknowledge several limitations of GPT-3, including its poor performance on tasks requiring logical reasoning or common sense, such as the ANLI dataset and certain reading comprehension benchmarks. They also note that the model struggles with sample efficiency, as it requires massive amounts of data and compute, and exhibits biases present in its training data. Additionally, GPT-3 shows a tendency to generate repetitive text and can be inconsistent in its outputs, especially in longer generations. Finally, the authors highlight that the model's few-shot learning, while impressive, still falls short of fine-tuned smaller models on many specific tasks.

</details>

<details><summary>Ответ с RAG</summary>

The authors acknowledge several limitations of GPT-3. First, it has notable weaknesses in text synthesis, such as repeating itself, losing coherence over long passages, contradicting itself, and containing non-sequitur sentences [5 Limitations]. Second, it struggles with "common sense physics" questions and performs little better than chance on some comparison and reading comprehension tasks [5 Limitations]. Third, its autoregressive architecture lacks bidirectionality, which may hinder performance on tasks requiring comparing two pieces of content or re-reading long passages [5 Limitations]. Finally, the pretraining objective weights every token equally, lacking a notion of what is most important to predict [5 Limitations].

</details>

---

### Q8. How much compute was used to train GPT-3 175B?

**Ожидание:** Several thousand petaflop/s-days (~3640 petaflop/s-days, ~3.14e23 flops).

**Ожидаемые источники:** «D Total Compute Used to Train Language Models»; «5 Limitations»

**Извлечено (RAG):** «6.3 Energy Usage» (0.754); «D Total Compute Used to Train Language Models» (0.668); «3.9.1 Arithmetic» (0.660); «B Details of Model Training» (0.658)

**Попадание в источники:** 1/2 («D Total Compute Used to Train Language Models»)

| режим | балл | вердикт судьи |
|---|---|---|
| без RAG | 2 | Ответ содержит ключевые факты из ожидания: 3.14e23 FLOPs, упоминание тысяч V100 GPU и нескольких недель, без ошибок. |
| с RAG | 2 | Ответ содержит ключевые факты из ожидания: несколько тысяч петафлоп/с-дней, 3640 PF-days, 3.14e23 flops, без ошибок. |

<details><summary>Ответ без RAG</summary>

According to the GPT-3 paper, training the 175B parameter model required a total of 3.14E23 FLOPs of compute. This was achieved using thousands of V100 GPUs over several weeks, though the exact number of GPUs and duration are not specified in precise terms. The paper notes that the training cost was substantial, but it does not provide a simple single number for GPU-hours or total energy consumption.

</details>

<details><summary>Ответ с RAG</summary>

Training GPT-3 175B consumed several thousand petaflop/s-days of compute during pre-training [6.3 Energy Usage]. Specifically, Table D.1 shows the total training compute was 3.64e+03 PF-days (or 3.14e+23 flops) [D Total Compute Used to Train Language Models].

</details>

---

### Q9. How well does GPT-3 perform on arithmetic tasks like 2-digit and 5-digit addition?

**Ожидание:** Few-shot: ~100% on 2-digit addition, ~98-99% on 2-digit subtraction; accuracy drops sharply with more digits (~10% or less on 5-digit).

**Ожидаемые источники:** «3.9.1 Arithmetic»

**Извлечено (RAG):** «3.9.1 Arithmetic» (0.828); «3.9.1 Arithmetic» (0.815); «3.9 Synthetic and Qualitative Tasks» (0.734); «5 Limitations» (0.733)

**Попадание в источники:** 1/1 («3.9.1 Arithmetic»)

| режим | балл | вердикт судьи |
|---|---|---|
| без RAG | 2 | Ответ содержит ключевые факты из эталона: высокая точность на 2-значном сложении, резкое падение на 5-значном, и объяснение причины. |
| с RAG | 2 | Ответ содержит ключевые факты из эталонного ожидания: 100% на 2-значном сложении и резкое падение до ~9.3% на 5-значном, без ошибок. |

<details><summary>Ответ без RAG</summary>

GPT-3 shows strong performance on 2-digit addition, achieving near-perfect accuracy in the few-shot setting. However, performance drops significantly on 5-digit addition, where it often fails to produce correct results. The paper notes that GPT-3's accuracy on arithmetic tasks degrades as the number of digits increases, and it does not reliably generalize to more complex calculations. This limitation is attributed to the model's lack of an explicit mathematical reasoning mechanism.

</details>

<details><summary>Ответ с RAG</summary>

GPT-3 achieves 100% accuracy on 2-digit addition in the few-shot setting, but performance drops to 9.3% on 5-digit addition [3.9.1 Arithmetic]. This shows strong proficiency with small numbers but significant degradation as digit count increases [3.9.1 Arithmetic]. The model still demonstrates some capacity to generalize to larger numbers, though accuracy is low [3.9.1 Arithmetic].

</details>

---

### Q10. What context window size does GPT-3 use and what attention pattern is used in its layers?

**Ожидание:** Context window of 2048 tokens; alternating dense and locally banded sparse attention, as in the Sparse Transformer.

**Ожидаемые источники:** «2.1 Model and Architectures»

**Извлечено (RAG):** «B Details of Model Training» (0.729); «3.7 SuperGLUE» (0.726); «2.1 Model and Architectures» (0.718); «3.3 Translation» (0.712)

**Попадание в источники:** 1/1 («2.1 Model and Architectures»)

| режим | балл | вердикт судьи |
|---|---|---|
| без RAG | 0 | Ответ неверен: утверждает, что GPT-3 использует плотное внимание, тогда как эталон указывает на чередование плотного и локально-ленточного разреженного внимания. |
| с RAG | 2 | Ответ полностью соответствует эталону: указан размер контекста 2048 токенов и описан паттерн внимания (чередование плотного и локально-ленточного разреженного). |

<details><summary>Ответ без RAG</summary>

GPT-3 uses a context window size of 2048 tokens. Its layers employ a dense (full) attention pattern, meaning each token can attend to all other tokens within the context window, rather than using sparse or masked attention patterns. This is consistent with the standard Transformer architecture used in GPT-2 and earlier GPT models.

</details>

<details><summary>Ответ с RAG</summary>

GPT-3 uses a context window of 2048 tokens [2.1 Model and Architectures]. Its layers employ alternating dense and locally banded sparse attention patterns, similar to the Sparse Transformer [2.1 Model and Architectures].

</details>

