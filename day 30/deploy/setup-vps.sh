#!/usr/bin/env bash
# Первичная настройка VPS (Ubuntu/Debian, root). Запускается ОДИН раз на сервере:
#   ssh root@<vps> 'bash -s' < deploy/setup-vps.sh
# Ставит JRE 17 и Ollama, тянет модели, создаёт пользователя и /opt/day30.
set -euo pipefail

CHAT_MODEL="${CHAT_MODEL:-qwen2.5:1.5b}"
EMBED_MODEL="${EMBED_MODEL:-nomic-embed-text}"

echo "── JRE 17 + утилиты ──────────────────────────────────────────"
apt-get update -q
apt-get install -y -q openjdk-17-jre-headless curl

echo "── Ollama (слушает только localhost — наружу торчит лишь наш сервис) ──"
if ! command -v ollama >/dev/null; then
  curl -fsSL https://ollama.com/install.sh | sh
fi
systemctl enable --now ollama
# На слабом CPU сервер поднимается не мгновенно — ждём готовности API, иначе pull упадёт.
for i in $(seq 1 30); do
  curl -fsS http://127.0.0.1:11434/api/version >/dev/null 2>&1 && break
  sleep 2
done
# Один слот генерации: на слабом VPS два параллельных KV-кэша приводят к OOM-kill.
mkdir -p /etc/systemd/system/ollama.service.d
cat > /etc/systemd/system/ollama.service.d/override.conf <<'CONF'
[Service]
Environment=OLLAMA_NUM_PARALLEL=1
CONF
systemctl daemon-reload
systemctl restart ollama
for i in $(seq 1 30); do
  curl -fsS http://127.0.0.1:11434/api/version >/dev/null 2>&1 && break
  sleep 2
done
ollama pull "$CHAT_MODEL"
ollama pull "$EMBED_MODEL"

echo "── Пользователь и каталог сервиса ───────────────────────────"
id -u day30 &>/dev/null || useradd --system --home /opt/day30 --shell /usr/sbin/nologin day30
mkdir -p /opt/day30/data /opt/day30/output
chown -R day30:day30 /opt/day30

echo "── Готово. Дальше: deploy/deploy.sh user@host с локальной машины ──"
