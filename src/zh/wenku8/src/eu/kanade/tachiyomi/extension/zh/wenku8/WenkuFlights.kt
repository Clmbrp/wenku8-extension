package eu.kanade.tachiyomi.extension.zh.wenku8

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Concurrent callers share active work. A result or failure is retained only by its callers. */
internal class WenkuFlights<K, V> {
    private class Flight<V>(val work: Deferred<V>, var subscribers: Int = 0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flights = mutableMapOf<K, Flight<V>>()

    suspend fun await(key: K, load: suspend () -> V): V {
        currentCoroutineContext().ensureActive()
        val flight = synchronized(flights) {
            val active = flights[key]?.takeUnless { it.work.isCompleted }
                ?: Flight(scope.async(start = CoroutineStart.LAZY) { load() }).also { flights[key] = it }
            active.subscribers++
            active
        }
        try {
            return flight.work.await()
        } finally {
            synchronized(flights) {
                if (--flight.subscribers == 0) {
                    flights.remove(key, flight)
                    flight.work.cancel()
                }
            }
        }
    }
}
