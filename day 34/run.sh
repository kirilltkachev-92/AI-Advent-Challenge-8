#!/usr/bin/env bash
# ./run.sh          — консоль ассистента по файлам проекта (REPL)
# ./run.sh reset    — восстановить рабочую копию проекта из шаблона
# ./run.sh report   — чеклист задания без интерактива → output/report.md
set -euo pipefail
cd "$(dirname "$0")"

if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in \
    "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" \
    "/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"; do
    if [[ -d "$candidate" ]]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "Java 17 not found. Install with: brew install openjdk@17" >&2
  exit 1
fi

export PATH="$JAVA_HOME/bin:$PATH"

MODE="${1:-repl}"
if [[ "$MODE" == "report" ]]; then
  mkdir -p output
  ./gradlew run -q --console=plain --args="report" 2>&1 | tee "output/_run.log"
else
  exec ./gradlew run -q --console=plain --args="$MODE"
fi
