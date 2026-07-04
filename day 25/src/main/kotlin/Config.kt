import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * Конфигурация Дня 23 (реранкинг и фильтрация).
 *
 * Пайплайн улучшенного RAG: query rewrite → top-CANDIDATES_K по косинусу →
 * порог SIM_THRESHOLD → LLM-реранкер → порог RERANK_THRESHOLD → top-FINAL_K.
 * Все пороги и K настраиваются через .env.
 */
object Config {
    private const val OLLAMA_URL_VAR = "OLLAMA_BASE_URL"
    private const val EMBED_MODEL_VAR = "EMBED_MODEL"
    private const val DOCS_VAR = "DOCS_DIR"
    private const val OUTPUT_VAR = "OUTPUT_DIR"
    private const val CHUNK_SIZE_VAR = "CHUNK_SIZE"
    private const val CHUNK_OVERLAP_VAR = "CHUNK_OVERLAP"
    private const val STRATEGY_VAR = "RAG_STRATEGY"
    private const val API_KEY_VAR = "DEEPSEEK_API_KEY"
    private const val MODEL_VAR = "DEEPSEEK_MODEL"

    private const val CANDIDATES_K_VAR = "CANDIDATES_K"
    private const val FINAL_K_VAR = "FINAL_K"
    private const val SIM_THRESHOLD_VAR = "SIM_THRESHOLD"
    private const val RERANK_THRESHOLD_VAR = "RERANK_THRESHOLD"

    const val DEEPSEEK_API_BASE = "https://api.deepseek.com"
    private const val DEEPSEEK_MODEL_DEFAULT = "deepseek-chat"

    private const val DEFAULT_OLLAMA_URL = "http://localhost:11434"
    private const val DEFAULT_EMBED_MODEL = "nomic-embed-text"
    private const val DEFAULT_DOCS = "docs"
    private const val DEFAULT_OUTPUT = "output"
    private const val DEFAULT_CHUNK_SIZE = 1200
    private const val DEFAULT_CHUNK_OVERLAP = 200
    private const val DEFAULT_STRATEGY = "structure"

    // Схема из лекции: широкая выборка кандидатов → реранк → узкий финальный top-K.
    private const val DEFAULT_CANDIDATES_K = 20
    private const val DEFAULT_FINAL_K = 4

    // Порог косинуса: ниже — почти наверняка нерелевантный чанк (дёшево отсекаем до реранка).
    private const val DEFAULT_SIM_THRESHOLD = 0.55

    // Порог LLM-реранкера (0–10): сколько должен набрать чанк, чтобы попасть в контекст.
    private const val DEFAULT_RERANK_THRESHOLD = 6

    const val MAX_SECTION_CHARS = 4000

    private val dotEnv = mutableMapOf<String, String>()

    private val dotEnvCandidates = listOf(
        Path.of(".env"),
        Path.of("day 25/.env"),
    )

    fun loadDotEnv() {
        dotEnvCandidates.forEach { path -> if (path.exists()) loadDotEnvFile(path) }
    }

    private fun loadDotEnvFile(path: Path) {
        path.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val idx = trimmed.indexOf('=')
            if (idx <= 0) return@forEach
            val key = trimmed.substring(0, idx).trim()
            var value = trimmed.substring(idx + 1).trim()
            if ((value.startsWith('"') && value.endsWith('"')) ||
                (value.startsWith('\'') && value.endsWith('\''))
            ) {
                value = value.substring(1, value.length - 1)
            }
            dotEnv.putIfAbsent(key, value)
        }
    }

    private fun envValue(key: String): String? =
        System.getenv(key)?.takeIf { it.isNotBlank() }
            ?: dotEnv[key]?.takeIf { it.isNotBlank() }

    fun ollamaBaseUrl(): String = (envValue(OLLAMA_URL_VAR) ?: DEFAULT_OLLAMA_URL).trimEnd('/')

    fun embedModel(): String = envValue(EMBED_MODEL_VAR) ?: DEFAULT_EMBED_MODEL

    fun docsDir(): Path = Path.of(envValue(DOCS_VAR) ?: DEFAULT_DOCS)

    fun outputDir(): Path = Path.of(envValue(OUTPUT_VAR) ?: DEFAULT_OUTPUT)

    fun chunkSize(): Int = envValue(CHUNK_SIZE_VAR)?.toIntOrNull() ?: DEFAULT_CHUNK_SIZE

    fun chunkOverlap(): Int = envValue(CHUNK_OVERLAP_VAR)?.toIntOrNull() ?: DEFAULT_CHUNK_OVERLAP

    fun ragStrategy(): String = envValue(STRATEGY_VAR) ?: DEFAULT_STRATEGY

    /** Сколько кандидатов достаём по косинусу ДО фильтрации/реранка. */
    fun candidatesK(): Int = envValue(CANDIDATES_K_VAR)?.toIntOrNull() ?: DEFAULT_CANDIDATES_K

    /** Сколько чанков идёт в контекст ПОСЛЕ фильтрации/реранка (и в baseline-режиме). */
    fun finalK(): Int = envValue(FINAL_K_VAR)?.toIntOrNull() ?: DEFAULT_FINAL_K

    /** Порог косинусной близости для дешёвого отсечения нерелевантных кандидатов. */
    fun simThreshold(): Double = envValue(SIM_THRESHOLD_VAR)?.toDoubleOrNull() ?: DEFAULT_SIM_THRESHOLD

    /** Порог балла LLM-реранкера (0–10). */
    fun rerankThreshold(): Int = envValue(RERANK_THRESHOLD_VAR)?.toIntOrNull() ?: DEFAULT_RERANK_THRESHOLD

    /** Сколько последних ходов диалога передаётся в промпт «сырыми» (остальное — в памяти задачи). */
    fun historyTurns(): Int = envValue("HISTORY_TURNS")?.toIntOrNull() ?: 8

    /** Файл, куда персистится сессия чата (история + память задачи). */
    fun sessionFile(): Path = outputDir().resolve("chat-session.json")

    fun deepSeekKey(): String =
        envValue(API_KEY_VAR) ?: error("Нет API-ключа. Задайте $API_KEY_VAR в окружении или .env.")

    fun deepSeekModel(): String = envValue(MODEL_VAR) ?: DEEPSEEK_MODEL_DEFAULT
}
