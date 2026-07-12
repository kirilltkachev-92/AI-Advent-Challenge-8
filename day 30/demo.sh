#!/usr/bin/env bash
# Демо для видео: приватный AI-сервис на локальной LLM.
# Сервис должен уже работать: ./run.sh (или на VPS — тогда BASE_URL/TOKEN ниже).
set -euo pipefail
cd "$(dirname "$0")"

BASE_URL="${BASE_URL:-http://localhost:8030}"
TOKEN="${TOKEN:-$(grep '^API_TOKENS=' .env | cut -d= -f2 | cut -d, -f1)}"

echo "── 1. Сервис доступен по сети (без токена — только healthz) ──"
curl -s "$BASE_URL/healthz"; echo; echo

echo "── 2. Приватность: без токена — 401 ─────────────────────────"
curl -s -o /dev/null -w "HTTP %{http_code}\n" -X POST "$BASE_URL/v1/chat" \
  -H 'Content-Type: application/json' -d '{"message":"привет"}'
echo

echo "── 3. Действующие ограничения сервиса ───────────────────────"
curl -s "$BASE_URL/v1/limits" -H "Authorization: Bearer $TOKEN"; echo; echo

echo "── 4. Чат: модель отвечает по истории марафона ──────────────"
curl -s -X POST "$BASE_URL/v1/chat" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"Что тьютор говорил про задание дня 30?"}'; echo; echo

echo "── 5. Полный чеклист задания (сеть/стабильность/лимиты) ─────"
VERIFY_BASE_URL="$BASE_URL" VERIFY_TOKEN="$TOKEN" ./run.sh verify
