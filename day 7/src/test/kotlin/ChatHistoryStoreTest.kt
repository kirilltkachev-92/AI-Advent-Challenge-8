import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ChatHistoryStoreTest {

    @Test
    fun `save and load roundtrip`(@TempDir dir: Path) {
        val file = dir.resolve("chat_history.json")
        val store = ChatHistoryStore(file)

        val messages = listOf(
            ChatMessage(role = "system", content = "system"),
            ChatMessage(role = "user", content = "Меня зовут Кирилл"),
            ChatMessage(role = "assistant", content = "Приятно познакомиться, Кирилл!"),
        )

        store.save(messages)
        assertEquals(messages, store.load())
    }

    @Test
    fun `clear removes saved history`(@TempDir dir: Path) {
        val file = dir.resolve("chat_history.json")
        val store = ChatHistoryStore(file)

        store.save(listOf(ChatMessage(role = "user", content = "test")))
        store.clear()

        assertNull(store.load())
    }
}
