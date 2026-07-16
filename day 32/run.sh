#!/usr/bin/env bash
# ./run.sh index                 — построить индекс документации и кода
# ./run.sh review <base> [head]  — ревью диффа между ветками/коммитами (локально, в консоль)
# ./run.sh ci                    — режим GitHub Actions: событие PR → ревью → коммент в PR
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

if [[ -z "${JAVA_HOME:-}" ]] && ! command -v java >/dev/null; then
  echo "Java 17 not found. Install with: brew install openjdk@17" >&2
  exit 1
fi

[[ -n "${JAVA_HOME:-}" ]] && export PATH="$JAVA_HOME/bin:$PATH"

if [[ "${1:-}" == "review" ]]; then
  mkdir -p output
  ./gradlew run -q --console=plain --args="$*" 2>&1 | tee "output/_run.log"
else
  exec ./gradlew run -q --console=plain --args="${*:-index}"
fi
