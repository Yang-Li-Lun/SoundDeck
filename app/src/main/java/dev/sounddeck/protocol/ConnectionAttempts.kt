package dev.sounddeck.protocol

import kotlinx.coroutines.CancellationException
import java.io.IOException

class IdentityRejected(message: String) : IOException(message)

object ConnectionDiagnostics {
    private val address = Regex("(?i)(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}")
    fun redact(text: String): String = address.replace(text, "<address>")
    fun describe(error: Throwable): String = generateSequence(error) { it.cause }
        .take(4).joinToString(" <- ") { "${it.javaClass.simpleName}: ${redact(it.message.orEmpty()).take(240)}" }
}

/** Fallback is initialization-only, on the chosen address. Never replays a user's command or reconnects a ready session. */
object ConnectionAttempts {
    suspend fun run(
        routes: List<ControlTransport>,
        failure: (ControlTransport, String) -> Unit,
        attempt: suspend (ControlTransport, () -> Unit) -> Unit,
    ) {
        require(routes.isNotEmpty())
        val failures = mutableListOf<String>()
        for ((index, route) in routes.withIndex()) {
            var established = false
            try {
                attempt(route) { established = true }
                return
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val description = ConnectionDiagnostics.describe(e)
                failure(route, description)
                failures += "${route.label}: $description"
                if (established || e is IdentityRejected || e is SecurityException || index == routes.lastIndex)
                    throw IOException(failures.joinToString("\n"), e)
            }
        }
    }
}
