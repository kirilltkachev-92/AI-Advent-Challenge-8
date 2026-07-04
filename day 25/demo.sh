#!/usr/bin/env bash
# Демо Дня 25: мини-чат с RAG + источниками + памятью задачи.
set -euo pipefail
cd "$(dirname "$0")"

echo "########## 1. Мини-диалог в чате (история + RAG + источники + память задачи) ##########"
rm -f output/chat-session.json
printf '%s\n' \
  "Привет! Готовлю доклад про few-shot GPT-3, отвечай кратко с точными цифрами." \
  "How good is few-shot GPT-3 on LAMBADA?" \
  "А на переводах?" \
  "Напомни, какая у нас цель?" \
  "/state" \
  "/exit" | ./run.sh chat

echo
echo "########## 2. Два длинных сценария по 12 сообщений (проверка дня) ##########"
./run.sh scenarios

echo
echo "########## 3. Итоговый отчёт (output/scenarios.md) ##########"
cat output/scenarios.md
