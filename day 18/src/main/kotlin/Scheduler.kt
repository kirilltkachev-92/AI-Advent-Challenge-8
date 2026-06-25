import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Один периодический job: имя, интервал, счётчик запусков и время следующего запуска —
 * чтобы по команде `status` показать, что планировщик действительно тикает.
 */
class ScheduledJob(
    val name: String,
    val period: Duration,
    private val task: () -> Unit,
) {
    val runs = AtomicInteger(0)
    private val nextRun = AtomicReference<Instant>(Instant.now().plus(period))

    fun nextRunAt(): Instant = nextRun.get()

    internal fun runOnce() {
        try {
            task()
        } catch (e: Exception) {
            System.err.println("✗ job «$name» упал: ${e.message}")
        } finally {
            runs.incrementAndGet()
            nextRun.set(Instant.now().plus(period))
        }
    }
}

/**
 * Тонкая обёртка над ScheduledExecutorService — «выполняться по расписанию» из задания.
 *
 * Каждый job запускается с фиксированным интервалом в фоновом потоке-демоне, поэтому
 * приложение может жить 24/7 и периодически срабатывать, пока пользователь работает в REPL.
 */
class Scheduler {
    private val pool: ScheduledExecutorService = Executors.newScheduledThreadPool(2) { r ->
        Thread(r, "scheduler").apply { isDaemon = true }
    }
    private val jobs = mutableListOf<ScheduledJob>()

    /** Регистрирует и запускает периодический job. initialDelay даёт первому тику «прогреться». */
    fun every(name: String, period: Duration, initialDelay: Duration = period, task: () -> Unit): ScheduledJob {
        val job = ScheduledJob(name, period, task)
        jobs.add(job)
        pool.scheduleAtFixedRate(
            { job.runOnce() },
            initialDelay.toMillis(),
            period.toMillis(),
            TimeUnit.MILLISECONDS,
        )
        return job
    }

    fun jobs(): List<ScheduledJob> = jobs.toList()

    fun stop() = pool.shutdownNow()
}
