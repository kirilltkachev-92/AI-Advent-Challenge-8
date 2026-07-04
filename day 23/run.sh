#!/usr/bin/env bash
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
# Команды: index | ask «вопрос» | eval | без аргументов = всё.
# Вопрос оборачиваем в двойные кавычки: --args парсит сам Gradle, и апостроф
# в тексте («GPT-3's») иначе ломает разбор как «unbalanced quotes».
if [[ $# -gt 1 ]]; then
  cmd="$1"; shift
  rest="$*"
  rest="${rest//\"/}" # двойные кавычки внутри вопроса Gradle не экранирует — убираем
  exec ./gradlew run -q --console=plain --args="$cmd \"$rest\""
elif [[ $# -eq 1 ]]; then
  exec ./gradlew run -q --console=plain --args="$1"
else
  exec ./gradlew run -q --console=plain
fi
