#!/usr/bin/env bash
# Демо Дня 23: улучшенный RAG — query rewrite + порог + LLM-реранк против baseline.
set -euo pipefail
cd "$(dirname "$0")"

echo "########## 1. Один вопрос, оба режима бок о бок (виден rewrite и воронка фильтров) ##########"
./run.sh ask "Which datasets make up GPT-3's training mix and with what sampling weights?"

echo
echo "########## 2. Полное сравнение: 10 контрольных вопросов + судья ##########"
./run.sh eval

echo
echo "########## 3. Итоговый отчёт (output/evaluation.md) ##########"
cat output/evaluation.md
