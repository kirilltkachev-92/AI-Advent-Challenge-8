#!/usr/bin/env bash
# Демо для видео: показываем, что LLM локальная и отвечает тремя способами.
set -euo pipefail
cd "$(dirname "$0")"

echo "── 1. Сервер и модели локально ──────────────────────────────"
curl -s http://localhost:11434/api/version
echo
ollama list
echo

echo "── 2. Запрос через CLI (ollama run) ─────────────────────────"
ollama run qwen2.5:14b "Ответь одним предложением: что такое локальная LLM?"
echo

echo "── 3. Запрос через HTTP API (curl) ──────────────────────────"
curl -s http://localhost:11434/api/chat -d '{
  "model": "qwen2.5:14b",
  "messages": [{"role": "user", "content": "2+2? Ответь одним числом."}],
  "stream": false
}' | python3 -c 'import json,sys; print(json.load(sys.stdin)["message"]["content"])'
echo

echo "── 4. Полный прогон: 4 запроса разной сложности × 2 модели ──"
./run.sh
