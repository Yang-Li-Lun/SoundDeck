package dev.sounddeck

import dev.sounddeck.data.ForegroundConnectionPolicy
import dev.sounddeck.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRecoveryTest {
    @Test fun onlyVerifiedSessionCanRecoverAndRetryAlwaysPinsItsTransport() = runTest {
        val routes = mutableListOf<ControlTransport?>()
        val times = mutableListOf<Long>()
        val events = mutableListOf<String>()
        SessionRecovery({ true }, events::add).run({ _, _ -> }) { route, ready ->
            routes += route; times += currentTime
            if (routes.size == 1) {
                ready(ControlTransport.BLE)
                throw IOException("route wrapper", TransportDisconnected("link lost"))
            }
            ready(ControlTransport.BLE)
        }
        assertEquals(listOf(null, ControlTransport.BLE), routes)
        assertEquals(listOf(0L, 1000L), times)
        assertTrue(events.any { "result=verified" in it })
    }

    @Test fun initialLinkFailureNeverStartsRecovery() = runTest {
        var attempts = 0
        val error = runCatching {
            SessionRecovery({ true }).run({ _, _ -> fail("unexpected recovery") }) { _, _ ->
                attempts++; throw TransportDisconnected("initial failure")
            }
        }.exceptionOrNull()
        assertTrue(error is TransportDisconnected)
        assertEquals(1, attempts); assertEquals(0L, currentTime)
    }

    @Test fun retryBudgetSurvivesRepeatedSuccessfulButFlappingConnections() = runTest {
        val times = mutableListOf<Long>()
        val waits = mutableListOf<Long>()
        val error = runCatching {
            SessionRecovery({ true }).run({ _, ms -> waits += ms }) { _, ready ->
                times += currentTime
                ready(ControlTransport.BLE)
                throw TransportDisconnected("link lost again")
            }
        }.exceptionOrNull()
        assertTrue(error is TransportDisconnected)
        assertEquals(listOf(0L, 1000L, 4000L), times)
        assertEquals(listOf(1000L, 3000L), waits)
    }

    @Test fun unknownTimeoutIdentityPermissionAndSocketErrorsDoNotRecover() = runTest {
        for (failure in listOf(IOException("request timeout"), IdentityRejected("wrong model"),
            SecurityException("permission"), IOException("RFCOMM EOF"), IOException("GATT 133"))) {
            var count = 0
            val caught = runCatching {
                SessionRecovery({ true }).run({ _, _ -> fail("unexpected recovery") }) { _, ready ->
                    count++; ready(ControlTransport.BLE); throw failure
                }
            }.exceptionOrNull()
            assertSame(failure, caught); assertEquals(1, count)
        }
    }

    @Test fun manualSuppressionBackgroundAndUnconfirmedSettingStopRecovery() = runTest {
        for (stop in 0..2) {
            val policy = ForegroundConnectionPolicy().apply { enter(); manualConnection() }
            var unconfirmed = false
            var count = 0
            runCatching {
                SessionRecovery({ policy.allowsAutomaticStart && !unconfirmed }).run({ _, _ -> fail() }) { _, ready ->
                    count++; ready(ControlTransport.BLE)
                    when (stop) { 0 -> policy.manualAction(); 1 -> policy.leave(); else -> unconfirmed = true }
                    throw TransportDisconnected("link lost")
                }
            }
            assertEquals(1, count)
        }
    }

    @Test fun cancellingBackoffOrLosingEligibilityDoesNotCreateAnotherSession() = runTest {
        for (cancelJob in listOf(false, true)) {
            var eligible = true
            var count = 0
            val task = launch {
                SessionRecovery({ eligible }).run({ _, _ -> }) { _, ready ->
                    count++; ready(ControlTransport.BLE); throw TransportDisconnected("link lost")
                }
            }
            runCurrent(); advanceTimeBy(500)
            if (cancelJob) task.cancelAndJoin() else { eligible = false; advanceUntilIdle() }
            assertEquals(1, count); assertTrue(task.isCancelled)
        }
    }

    @Test fun cancellingActiveRecoveryAttemptRunsCleanupAndCannotFallBack() = runTest {
        var attempts = 0
        var cleanups = 0
        val task = launch {
            SessionRecovery({ true }).run({ _, _ -> }) { _, ready ->
                try {
                    attempts++
                    if (attempts == 1) { ready(ControlTransport.BLE); throw TransportDisconnected("link lost") }
                    awaitCancellation()
                } finally { cleanups++ }
            }
        }
        runCurrent(); advanceTimeBy(1000); runCurrent()
        assertEquals(2, attempts)
        task.cancelAndJoin(); advanceUntilIdle()
        assertEquals(2, cleanups); assertEquals(2, attempts)
    }

    @Test fun previousAttemptFinishesCleanupBeforeAnyRecoveryStarts() = runTest {
        var active = false
        var count = 0
        SessionRecovery({ true }).run({ _, _ -> assertFalse(active) }) { _, ready ->
            assertFalse(active); active = true
            try {
                ready(ControlTransport.BLE)
                if (++count == 1) throw TransportDisconnected("link lost")
            } finally { delay(250); active = false }
        }
        assertEquals(2, count); assertFalse(active)
    }
}
