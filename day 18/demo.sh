#!/usr/bin/env bash
#
# demo.sh — сценарий для записи видео-доказательства (День 18).
#
# Запускает фонового агента-планировщика с короткими интервалами, чтобы на видео
# было видно, как он САМ периодически срабатывает: собирает замеры (с персистом в JSON)
# и выдаёт агрегированную сводку. По ходу подаёт в REPL команды status/samples/summary.
#
# Запуск (нажмите Record в ⌘⇧5 и выполните):
#   ./demo.sh
#
# Темп и интервалы настраиваются переменными окружения, напр.:
#   COLLECT_INTERVAL_SEC=8 SUMMARY_INTERVAL_SEC=20 ./demo.sh
#
set -euo pipefail
cd "$(dirname "$0")"

# Короткие интервалы — чтобы планировщик «тикал» прямо в кадре.
export COLLECT_INTERVAL_SEC="${COLLECT_INTERVAL_SEC:-6}"
export SUMMARY_INTERVAL_SEC="${SUMMARY_INTERVAL_SEC:-18}"
export WATCH_CITIES="${WATCH_CITIES:-Париж,Токио}"

banner() {
  { printf '\n\033[1;36m== %s ==\033[0m\n' "$*" > /dev/tty; } 2>/dev/null || true
}

feed() {
  sleep 8   # дать серверу подняться и агенту подключиться

  banner "Планировщик тикает сам: collect сохраняет замеры, summary выдаёт сводку"
  sleep "$((SUMMARY_INTERVAL_SEC + 6))"   # дождаться 1–2 сборов и первой авто-сводки

  banner "status — доказательство, что задачи реально идут по расписанию"
  printf 'status\n';   sleep 4

  banner "samples — сколько замеров уже СОХРАНЕНО в JSON-хранилище"
  printf 'samples\n';  sleep 4

  banner "summary — сводку можно запросить и вручную (агент зовёт MCP weather_summary)"
  printf 'summary\n';  sleep 14

  banner "Готово — агент так и работает 24/7, выходим"
  printf 'quit\n';     sleep 1
}

# Свежий старт хранилища для чистой демонстрации (по желанию закомментируйте).
rm -rf data

feed | ./run.sh
