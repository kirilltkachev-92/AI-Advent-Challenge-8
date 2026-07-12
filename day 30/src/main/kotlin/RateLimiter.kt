import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.min

/**
 * Rate limit — классический token bucket на каждый API-токен.
 *
 * Ведро вмещает `burst` жетонов (допустимый залп) и пополняется со скоростью
 * `perMin`/60 жетонов в секунду (устоявшийся темп). Запрос без жетона получает
 * 429 и честный Retry-After — сколько секунд ждать следующий жетон.
 */
class RateLimiter(private val perMin: Int, private val burst: Int) {

    data class Decision(val allowed: Boolean, val retryAfterSec: Int, val remaining: Int)

    private class Bucket(var tokens: Double, var lastRefillNanos: Long)

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val ratePerSec = perMin / 60.0

    fun check(key: String): Decision {
        val bucket = buckets.computeIfAbsent(key) { Bucket(burst.toDouble(), System.nanoTime()) }
        synchronized(bucket) {
            val now = System.nanoTime()
            val elapsedSec = (now - bucket.lastRefillNanos) / 1_000_000_000.0
            bucket.tokens = min(burst.toDouble(), bucket.tokens + elapsedSec * ratePerSec)
            bucket.lastRefillNanos = now
            return if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                Decision(allowed = true, retryAfterSec = 0, remaining = bucket.tokens.toInt())
            } else {
                val waitSec = ceil((1.0 - bucket.tokens) / ratePerSec).toInt()
                Decision(allowed = false, retryAfterSec = waitSec, remaining = 0)
            }
        }
    }
}
