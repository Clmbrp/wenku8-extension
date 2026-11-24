package eu.kanade.tachiyomi.extension.zh.wenku8

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import okhttp3.HttpUrl
import org.jsoup.nodes.Document
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import kotlin.time.TimeSource

internal class WenkuRequestPolicy(
    private val intervalMillis: Long = 2_000,
    private val searchIntervalMillis: Long = 6_500,
    private val fallbackCooldownMillis: Long = 30_000,
    private val monotonicMillis: () -> Long = monotonicClock(),
    private val wallMillis: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val flights = WenkuFlights<Triple<HttpUrl, String?, Boolean>, Document>()
    private val gate = Mutex()
    private var lastStart: Long? = null
    private var lastSearchStart: Long? = null

    @Volatile private var cooldownUntil = 0L

    suspend fun page(url: HttpUrl, searchKeyword: String?, retryOnRateLimit: Boolean = true, load: suspend () -> Document): Document = flights.await(Triple(url, searchKeyword, retryOnRateLimit), load)

    fun isCoolingDown(): Boolean = cooldownUntil > monotonicMillis()

    suspend fun <T> exchange(searchResults: Boolean, retryOnRateLimit: Boolean = true, load: suspend () -> T): T = try {
        dispatch(searchResults, load)
    } catch (e: WenkuAccessException) {
        if (!retryOnRateLimit || e.failure != AccessFailure.RATE_LIMITED) throw e
        dispatch(searchResults, load)
    }

    private suspend fun <T> dispatch(searchResults: Boolean, load: suspend () -> T): T {
        while (true) {
            gate.lock()
            val wait = try {
                val now = monotonicMillis()
                val normalWait = lastStart?.let { intervalMillis - (now - it) } ?: 0L
                val searchWait = if (searchResults) {
                    lastSearchStart?.let { searchIntervalMillis - (now - it) } ?: 0L
                } else {
                    0L
                }
                val remaining = maxOf(normalWait, searchWait, cooldownUntil - now)
                if (remaining <= 0) {
                    currentCoroutineContext().ensureActive()
                    lastStart = now
                    if (searchResults) lastSearchStart = now
                    // Classify the response before giving other requests a slot.
                    return load()
                }
                remaining
            } finally {
                gate.unlock()
            }
            // A waiting search page must allow an unrelated book request to use an earlier slot.
            pause(wait)
        }
    }

    /** Called while handling a response inside [exchange]. */
    fun limited(retryAfter: String?, serverDate: String?): Nothing {
        val duration = retryAfter?.trim()?.let { value ->
            value.toLongOrNull()?.takeIf { it >= 0 }?.let(TimeUnit.SECONDS::toMillis)
                ?: httpDate(value)?.let { expires -> (expires - (httpDate(serverDate) ?: wallMillis())).coerceAtLeast(0) }
        } ?: fallbackCooldownMillis
        val now = monotonicMillis()
        cooldownUntil = maxOf(cooldownUntil, now + duration.coerceAtMost(Long.MAX_VALUE - now))
        throw WenkuAccessException(AccessFailure.RATE_LIMITED, "站点持续限制访问，本次加载失败")
    }

    private fun httpDate(raw: String?): Long? = raw?.let {
        try {
            ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

private fun monotonicClock(): () -> Long {
    val started = TimeSource.Monotonic.markNow()
    return { started.elapsedNow().inWholeMilliseconds }
}
