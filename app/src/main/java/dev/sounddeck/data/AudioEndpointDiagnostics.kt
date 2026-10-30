package dev.sounddeck.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.Collections
import java.util.IdentityHashMap

/** In-memory aliases only; neither addresses nor a reusable address hash leave the repository. */
internal class EndpointAliases {
    val scope = UUID.randomUUID().toString().take(8)
    private val aliases = mutableMapOf<String, String>()
    @Synchronized fun id(address: String): String =
        aliases.getOrPut(address.uppercase(Locale.ROOT)) { "endpoint${aliases.size + 1}" }
}

internal data class AudioPeer(val address: String, val group: Int? = null)
internal data class AudioEndpointSnapshot(
    val connected: List<AudioPeer>,
    val selectedGroup: Int? = null,
    val groupRead: String = "not_applicable",
)

internal object AudioEndpointDiagnostics {
    fun lines(snapshot: AudioEndpointSnapshot, selected: String, aliases: EndpointAliases): List<String> {
        val selectedId = aliases.id(selected)
        val exact = snapshot.connected.any { aliases.id(it.address) == selectedId }
        // Missing/invalid group IDs must never match one another. A different address is not a wrong endpoint.
        val sameGroup = snapshot.selectedGroup?.let { group -> snapshot.connected.any { it.group == group } } == true
        val relation = when {
            exact -> "same_address_connected"
            sameGroup -> "same_group_connected"
            else -> "unknown"
        }
        return listOf("selected=$selectedId connectedCount=${snapshot.connected.size} " +
            "selectedListed=$exact selectedGroup=${snapshot.selectedGroup ?: "unknown"} " +
            "groupRead=${snapshot.groupRead} relation=$relation activeAudio=unknown acousticEffect=unknown") +
            snapshot.connected.take(16).map { peer ->
                "connected=${aliases.id(peer.address)} group=${peer.group ?: "unknown"} " +
                    "selectedAddress=${aliases.id(peer.address) == selectedId}"
            } + if (snapshot.connected.size > 16) listOf("connectedDetails=truncated") else emptyList()
    }
}

/** One asynchronous, read-only profile binding. Timeout/cancellation also releases late proxies. */
internal class ReadOnlyProfileProbe<T : Any>(
    private val open: (connected: (T) -> Unit, disconnected: () -> Unit) -> Boolean,
    private val close: (T) -> Unit,
    private val read: (T) -> AudioEndpointSnapshot,
    private val timeoutMs: Long = 1_500,
) {
    suspend fun capture(): Pair<String, AudioEndpointSnapshot?> {
        val lock = Any()
        val ready = CompletableDeferred<T?>()
        var held: T? = null
        val seen = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
        var finished = false
        var disconnected = false
        fun release(proxy: T) { runCatching { close(proxy) } }
        try {
            val requested = open({ proxy ->
                synchronized(lock) {
                    if (seen.add(proxy)) {
                        if (finished || held != null) release(proxy)
                        else { held = proxy; ready.complete(proxy) }
                    }
                }
            }, {
                synchronized(lock) { disconnected = true; ready.complete(null) }
            })
            if (!requested) return "proxy_unavailable" to null
            val proxy = withTimeoutOrNull(timeoutMs) { ready.await() }
                ?: return (if (synchronized(lock) { disconnected }) "service_disconnected" else "proxy_timeout") to null
            if (synchronized(lock) { disconnected }) return "service_disconnected" to null
            val snapshot = read(proxy)
            return if (synchronized(lock) { disconnected }) "service_disconnected" to null else "observed" to snapshot
        } catch (e: CancellationException) { throw e }
        catch (_: SecurityException) { return "permission_denied" to null }
        catch (_: Exception) { return "read_failed" to null }
        finally {
            val proxy = synchronized(lock) { finished = true; held }
            proxy?.let(::release)
        }
    }
}
