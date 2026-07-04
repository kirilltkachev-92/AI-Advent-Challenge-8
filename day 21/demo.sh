#!/usr/bin/env bash
# Демо Дня 21: полный пайплайн индексации + примеры поиска по обоим индексам.
set -euo pipefail
cd "$(dirname "$0")"

echo "########## 1. Пайплайн: документы → чанки (2 стратегии) → эмбеддинги → JSON-индекс + сравнение ##########"
./run.sh

echo
echo "########## 2. Поиск: конкретный механизм ##########"
./run.sh search "What is in-context learning and how does it differ from fine-tuning?"

echo
echo "########## 3. Поиск: конкретные цифры из статьи ##########"
./run.sh search "How many parameters does the largest GPT-3 model have?"

echo
echo "########## Готово. Отчёт сравнения: output/comparison.md ##########"
