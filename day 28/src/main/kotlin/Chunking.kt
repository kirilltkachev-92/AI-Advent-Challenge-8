/**
 * Гранулярность индекса — ОДНО сообщение = один чанк («message-level»).
 *
 * Первая версия резала чат окнами по ~1500 символов, как «fixed» из Недели 5,
 * но на переписке это провалилось: точечный факт тонет в окне среди болтовни,
 * и анонс «День 21…» оказывался на 467-м месте по косинусу. Вектор одного
 * сообщения точен: тот же вопрос находит анонс 2-м, а факты — 1-ми.
 * Контекст соседних реплик возвращается на этапе поиска (Search.expand),
 * а не запекается в вектор.
 */
object Chunking {

    data class MessageChunk(
        val chunkId: String,
        val source: String,
        val section: String, // дата сообщения
        val msgIndex: Int,
        val text: String,
    )

    fun perMessage(messages: List<TgMessage>): List<MessageChunk> =
        messages.mapIndexed { i, m ->
            MessageChunk(
                chunkId = "msg-%04d".format(i),
                source = m.file,
                section = m.date,
                msgIndex = i,
                text = render(m),
            )
        }

    fun render(m: TgMessage) = "[${m.date} ${m.time}] ${m.author}: ${m.text}"
}
