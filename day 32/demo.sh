#!/usr/bin/env bash
# Демо для видео: создаёт PR с заведомо проблемным кодом — дальше всё
# происходит само: GitHub Actions запускает ревью и оставляет коммент в PR.
# Требует авторизованный gh (gh auth login) и секрет DEEPSEEK_API_KEY в репо.
set -euo pipefail
cd "$(dirname "$0")/.."

gh auth status >/dev/null 2>&1 || { echo "Нужен gh auth login" >&2; exit 1; }

BRANCH="demo/ai-review-$(date +%s)"
git checkout -b "$BRANCH"

mkdir -p "day 32/demo"
cat > "day 32/demo/UserService.kt" <<'EOF'
import java.sql.DriverManager

// Демо-файл для AI-ревью: здесь намеренно собраны типичные проблемы.
object UserService {
    const val API_KEY = "sk-deadbeef1234567890"
    var cache = HashMap<String, String>()

    fun findUser(name: String): String {
        val conn = DriverManager.getConnection("jdbc:sqlite:users.db")
        val rows = conn.createStatement()
            .executeQuery("SELECT * FROM users WHERE name = '" + name + "'")
        var result = ""
        while (rows.next()) {
            result = result + rows.getString("email") + ","
        }
        return result
    }

    fun averageAge(ages: List<Int>): Int {
        return ages.sum() / ages.size
    }

    fun retryForever(block: () -> Unit) {
        while (true) {
            try {
                block()
                return
            } catch (e: Exception) {
            }
        }
    }
}
EOF

git add "day 32/demo/UserService.kt"
git commit -m "demo: сервис пользователей для проверки AI-ревью"
git push -u origin "$BRANCH"

gh pr create \
  --title "Демо: UserService для AI-ревью (день 32)" \
  --body "PR с намеренно проблемным кодом — проверка автоматического AI-ревью. Ничего не мержить." \
  --base main --head "$BRANCH"

echo
echo "PR создан. Через 2–4 минуты workflow «AI Review» оставит коммент с ревью:"
gh pr view --web
