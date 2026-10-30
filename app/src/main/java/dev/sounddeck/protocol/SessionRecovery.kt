package dev.sounddeck.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException

/** Recognized link-loss callback, never a request timeout or an unclassified socket error. */
class TransportDisconnected(message: String) : IOException(message)

/** Shares one connection owner. Every attempt must fully close/join its old session before returning. */
class SessionRecovery(
    private val allowed: () -> Boolean,
    private val diagnostic: (String) -> Unit = {},
) {
    suspend fun run(
        waiting: (attempt: Int, delayMs: Long) -> Unit,
        attempt: suspend (verifiedRoute: ControlTransport?, established: (ControlTransport) -> Unit) -> Unit,
    ) {
        var verifiedRoute: ControlTransport? = null
        var retries = 0
        val delays = listOf(1_000L, 3_000L)
        while (true) {
            try {
                attempt(verifiedRoute) { route ->
                    verifiedRoute = route
                    if (retries > 0) diagnostic("Recovery attempt=$retries result=verified route=$route")
                }
                return
            } catch (e: CancellationException) {
                if (retries > 0) diagnostic("Recovery cancelled")
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                val disconnected = generateSequence<Throwable>(e) { it.cause }.take(8).any { it is TransportDisconnected }
                val eligible = allowed()
                if (retries > 0) diagnostic("Recovery attempt=$retries result=ended: ${ConnectionDiagnostics.describe(e)}")
                if (verifiedRoute == null || !disconnected || !eligible) {
                    diagnostic("Recovery skipped: verified=${verifiedRoute != null} transportDisconnected=$disconnected eligible=$eligible")
                    throw e
                }
                if (retries == delays.size) {
                    diagnostic("Recovery exhausted attempts=$retries")
                    throw e
                }
                val pause = delays[retries++]
                diagnostic("Recovery scheduled attempt=$retries/${delays.size} delayMs=$pause route=$verifiedRoute")
                waiting(retries, pause)
                try {
                    delay(pause)
                    currentCoroutineContext().ensureActive()
                    if (!allowed()) throw CancellationException("Recovery no longer eligible")
                } catch (cancelled: CancellationException) {
                    diagnostic("Recovery cancelled before attempt=$retries")
                    throw cancelled
                }
            }
        }
    }
}
