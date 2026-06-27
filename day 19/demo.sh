#!/usr/bin/env bash
#
# demo.sh — сценарий для записи видео-доказательства (День 19).
#
# Показывает АВТОМАТИЧЕСКИЙ пайплайн из MCP-инструментов: на один запрос пользователя
# агент сам строит цепочку search → summarize → save_to_file и передаёт данные между
# инструментами. Затем проверяем, что файл реально создан.
#
# Запуск (нажмите Record в ⌘⇧5 и выполните):
#   ./demo.sh
#
set -euo pipefail
cd "$(dirname "$0")"

PAUSE="${DEMO_PAUSE:-3}"
CHAIN="${DEMO_CHAIN:-30}"   # время на цепочку (3 вызова MCP + ответ LLM)

banner() {
  { printf '\n\033[1;36m== %s ==\033[0m\n' "$*" > /dev/tty; } 2>/dev/null || true
}

feed() {
  sleep 6   # сервер поднимается, агент подключается

  banner "Шаг 1/3 — КОНСПЕКТ: агент строит цепочку search→summarize→save_to_file (один файл)"
  printf 'Найди статьи про Эрмитаж и сохрани конспект в файл hermitage.md\n'
  sleep "$CHAIN"

  banner "Шаг 2/3 — ОРИГИНАЛ: агент строит цепочку search→save_articles (каждая статья в свой файл, без суммаризации)"
  printf 'Найди статьи про Эрмитаж и сохрани оригинальный текст каждой статьи в отдельный файл\n'
  sleep "$CHAIN"

  banner "Шаг 3/3 — список инструментов (всё на ОДНОМ MCP-сервере)"
  printf 'list\n'; sleep "$PAUSE"

  printf 'quit\n'; sleep 1
}

rm -rf output
feed | ./run.sh

# После выхода — показать оба эффекта: один конспект И отдельные файлы статей.
banner "Доказательство: один конспект (save_to_file) + отдельные оригиналы (save_articles)"
ls -la output 2>/dev/null || true
echo "----- output/hermitage.md (конспект, первые строки) -----"
head -8 output/hermitage.md 2>/dev/null || echo "(файл не найден)"
