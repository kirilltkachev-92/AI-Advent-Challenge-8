#!/usr/bin/env bash
# Деплой на VPS: ./deploy/deploy.sh root@<vps-ip>
# Собирает fat jar, заливает jar + корпус + готовый индекс (если есть) + юнит,
# на сервере дописывает .env (если ещё нет) и перезапускает сервис.
set -euo pipefail
cd "$(dirname "$0")/.."

HOST="${1:?Использование: ./deploy/deploy.sh user@host}"

echo "── Сборка fat jar ────────────────────────────────────────────"
./gradlew -q fatJar
JAR="build/libs/day30-service.jar"

echo "── Заливка на $HOST ─────────────────────────────────────────"
scp "$JAR" "$HOST:/opt/day30/day30-service.jar"
scp data/messages*.html "$HOST:/opt/day30/data/"
# Готовый индекс экономит первый запуск (иначе VPS посчитает его сам при старте).
if [[ -f output/index-chat.json ]]; then
  scp output/index-chat.json "$HOST:/opt/day30/output/index-chat.json"
fi
scp deploy/day30.service "$HOST:/etc/systemd/system/day30.service"

echo "── Настройка и запуск ───────────────────────────────────────"
ssh "$HOST" bash -s <<'REMOTE'
set -euo pipefail
if [[ ! -f /opt/day30/.env ]]; then
  TOKEN="$(head -c16 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  cat > /opt/day30/.env <<ENV
API_TOKENS=$TOKEN
BIND_HOST=0.0.0.0
PORT=8030
CHAT_MODEL=qwen2.5:1.5b
# Тюнинг под 1 vCPU / 2 GB: меньше контекст и один слот генерации — иначе OOM
NUM_CTX=2048
MAX_ANSWER_TOKENS=256
FRAGMENTS_CHAR_BUDGET=2500
HISTORY_CHAR_BUDGET=2500
MAX_CONCURRENT=1
QUEUE_TIMEOUT_MS=180000
ENV
  echo "Создан /opt/day30/.env, токен: $TOKEN"
fi
chown -R day30:day30 /opt/day30
systemctl daemon-reload
systemctl enable --now day30
systemctl restart day30
sleep 3
systemctl --no-pager status day30 | head -8
curl -s "http://localhost:$(grep '^PORT=' /opt/day30/.env | cut -d= -f2 || echo 8030)/healthz" && echo
REMOTE

echo "── Готово. Проверка снаружи: ────────────────────────────────"
echo "  curl http://\${VPS_IP}:8030/healthz"
echo "  VERIFY_BASE_URL=http://\${VPS_IP}:8030 VERIFY_TOKEN=<токен> ./run.sh verify"
