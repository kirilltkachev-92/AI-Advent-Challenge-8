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
