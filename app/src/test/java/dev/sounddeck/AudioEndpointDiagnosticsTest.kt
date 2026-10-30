package dev.sounddeck

import dev.sounddeck.data.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioEndpointDiagnosticsTest {
    private val selected = "AA:BB:CC:DD:EE:01"
    private val other = "AA:BB:CC:DD:EE:02"
    private val snapshot = AudioEndpointSnapshot(listOf(AudioPeer(selected, 7)), 7, "observed")

    @Test fun exactDeviceAndSameGroupAreDistinctEvidence() {
        val aliases = EndpointAliases()
        val exact = AudioEndpointDiagnostics.lines(snapshot, selected, aliases)
        assertTrue(exact.first().contains("relation=same_address_connected"))
        val group = AudioEndpointDiagnostics.lines(snapshot.copy(connected = listOf(AudioPeer(other, 7))), selected, aliases)
        assertTrue(group.first().contains("selectedListed=false"))
        assertTrue(group.first().contains("relation=same_group_connected"))
        assertTrue(group.first().contains("activeAudio=unknown acousticEffect=unknown"))
    }

    @Test fun missingOrDifferentGroupAndEmptyListNeverImplyWrongEndpoint() {
        for (value in listOf(
            AudioEndpointSnapshot(listOf(AudioPeer(other))),
            AudioEndpointSnapshot(listOf(AudioPeer(other, 8)), 7, "observed"),
            AudioEndpointSnapshot(emptyList(), 7, "observed"),
            AudioEndpointSnapshot(listOf(AudioPeer(other, 7)), null, "permission_denied"),
        )) {
            val line = AudioEndpointDiagnostics.lines(value, selected, EndpointAliases()).first()
            assertTrue(line.contains("relation=unknown"))
            assertFalse(line.contains("wrong"))
        }
    }

    @Test fun a2dpObservationDoesNotClaimAacCodecOrActivePlayback() {
        val line = AudioEndpointDiagnostics.lines(AudioEndpointSnapshot(listOf(AudioPeer(selected))), selected, EndpointAliases()).first()
        assertTrue(line.contains("same_address_connected"))
        assertTrue(line.contains("selectedGroup=unknown groupRead=not_applicable"))
        assertTrue(line.contains("activeAudio=unknown"))
        assertFalse(line.contains("AAC")); assertFalse(line.contains("LC3"))
    }

    @Test fun aliasesSurviveRecordReorderingButHaveNoPersistentIdentifier() {
        val aliases = EndpointAliases()
        assertEquals("endpoint1", aliases.id(selected))
        assertEquals("endpoint2", aliases.id(other))
        assertEquals("endpoint1", aliases.id(selected.lowercase()))
        val lines = AudioEndpointDiagnostics.lines(snapshot, selected, aliases).joinToString()
        assertFalse(lines.contains(selected)); assertFalse(lines.contains("EE:01"))
        val anotherProcess = EndpointAliases()
        assertNotEquals(aliases.scope, anotherProcess.scope)
        assertEquals("endpoint1", anotherProcess.id(other))
    }

    @Test fun peerDetailsAreBoundedAndDoNotExposeRawAddresses() {
        val peers = (1..30).map { AudioPeer("PRIVATE-$it", 7) }
        val lines = AudioEndpointDiagnostics.lines(AudioEndpointSnapshot(peers, 7, "observed"), selected, EndpointAliases())
        assertEquals(18, lines.size)
        assertTrue(lines.first().contains("connectedCount=30"))
        assertEquals("connectedDetails=truncated", lines.last())
        assertFalse(lines.joinToString().contains("PRIVATE"))
    }

    @Test fun successReadsOnceAndReleasesProxyOnce() = runTest {
        val proxy = Any()
        var reads = 0
        val closed = mutableListOf<Any>()
        val result = ReadOnlyProfileProbe<Any>(
            open = { connected, _ -> connected(proxy); connected(proxy); true },
            close = { closed += it }, read = { reads++; snapshot },
        ).capture()
        assertEquals("observed", result.first); assertEquals(snapshot, result.second)
        assertEquals(1, reads); assertEquals(listOf(proxy), closed)
    }

    @Test fun timeoutReportsUnknownAndClosesALateProxyWithoutReading() = runTest {
        lateinit var deliver: (Any) -> Unit
        val closed = mutableListOf<Any>()
        val result = ReadOnlyProfileProbe<Any>(
            open = { connected, _ -> deliver = connected; true },
            close = { closed += it }, read = { error("No read after timeout") }, timeoutMs = 20,
        ).capture()
        assertEquals("proxy_timeout", result.first); assertNull(result.second)
        val late = Any(); deliver(late); deliver(late)
        assertEquals(listOf(late), closed)
    }

    @Test fun cancellationReleasesLateProxyAndDoesNotBecomeFailure() = runTest {
        lateinit var deliver: (Any) -> Unit
        val closed = mutableListOf<Any>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            ReadOnlyProfileProbe<Any>(
                open = { connected, _ -> deliver = connected; true },
                close = { closed += it }, read = { error("No read after cancellation") },
            ).capture()
            fail("Cancellation must propagate")
        }
        job.cancelAndJoin()
        val late = Any(); deliver(late)
        assertTrue(job.isCancelled); assertEquals(listOf(late), closed)
    }

    @Test fun permissionAndReadFailuresAreUnknownWithoutExceptionText() = runTest {
        for (error in listOf(SecurityException("PRIVATE-$selected"), IllegalStateException("PRIVATE-$other"))) {
            val proxy = Any()
            val closed = mutableListOf<Any>()
            val result = ReadOnlyProfileProbe<Any>(
                open = { connected, _ -> connected(proxy); true }, close = { closed += it }, read = { throw error },
            ).capture()
            assertEquals(if (error is SecurityException) "permission_denied" else "read_failed", result.first)
            assertNull(result.second); assertEquals(listOf(proxy), closed)
            assertFalse(result.toString().contains("PRIVATE"))
        }
    }

    @Test fun unavailableProxyDoesNotReadOrRetry() = runTest {
        var opens = 0
        val result = ReadOnlyProfileProbe<Any>(
            open = { _, _ -> opens++; false }, close = { fail("No proxy") }, read = { error("No proxy") },
        ).capture()
        assertEquals("proxy_unavailable", result.first); assertNull(result.second); assertEquals(1, opens)
    }

    @Test fun disconnectedServiceInvalidatesSnapshotAndClosesProxy() = runTest {
        lateinit var disconnected: () -> Unit
        val proxy = Any()
        val closed = mutableListOf<Any>()
        val result = ReadOnlyProfileProbe<Any>(
            open = { connected, lost -> disconnected = lost; connected(proxy); true },
            close = { closed += it }, read = { disconnected(); snapshot },
        ).capture()
        assertEquals("service_disconnected", result.first); assertNull(result.second)
        assertEquals(listOf(proxy), closed)
    }

    @Test fun disconnectBeforeProxyDoesNotWaitOrReadAndStillClosesLateCallback() = runTest {
        lateinit var deliver: (Any) -> Unit
        val closed = mutableListOf<Any>()
        val result = ReadOnlyProfileProbe<Any>(
            open = { connected, lost -> deliver = connected; lost(); true },
            close = { closed += it }, read = { error("Disconnected") },
        ).capture()
        assertEquals("service_disconnected", result.first); assertNull(result.second)
        val late = Any(); deliver(late); assertEquals(listOf(late), closed)
    }

    @Test fun cancellationAfterAttachmentStillReleasesProxy() = runTest {
        val proxy = Any()
        val closed = mutableListOf<Any>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            ReadOnlyProfileProbe<Any>(
                open = { connected, _ -> connected(proxy); true }, close = { closed += it },
                read = { throw kotlinx.coroutines.CancellationException("cancelled") },
            ).capture()
            fail("Cancellation must propagate")
        }
        job.join()
        assertTrue(job.isCancelled); assertEquals(listOf(proxy), closed)
    }

    @Test fun extraProxyAndCloseFailureDoNotChangeObservation() = runTest {
        val first = Any(); val extra = Any()
        val closed = mutableListOf<Any>()
        val result = ReadOnlyProfileProbe<Any>(
            open = { connected, _ -> connected(first); connected(extra); connected(extra); true },
            close = { closed += it; error("PRIVATE-$selected") },
            read = { assertSame(first, it); snapshot },
        ).capture()
        assertEquals("observed", result.first)
        assertEquals(listOf(extra, first), closed)
    }
}
