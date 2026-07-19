#!/usr/bin/env bash
# Демо для видео: AI-конвейер подготовки релиза.
# Сначала сухой прогон (факты → преflight → notes → gate), затем реальная
# публикация: тег + GitHub Release. Если релиз уже опубликован, перед записью
# видео его можно снять одной командой (см. README, раздел «Повторный прогон»).
set -euo pipefail
cd "$(dirname "$0")"

echo '$ ./run.sh            # сухой прогон'
./run.sh
echo
echo '$ ./run.sh publish    # реальная публикация'
./run.sh publish
