package dev.sounddeck
import dev.sounddeck.data.ForegroundConnectionPolicy
import org.junit.Assert.*
import org.junit.Test

class ForegroundConnectionPolicyTest {
    @Test fun onlyEligibleVisibleVisitCanClaim() {
        val p = ForegroundConnectionPolicy()
        assertFalse(p.claim(true, false)); p.enter()
        assertFalse(p.claim(false, false)); assertTrue(p.claim(true, false))
        assertFalse(p.claim(true, false))
    }
    @Test fun resumeAndRotationDoNotResetAttempt() {
        val p = ForegroundConnectionPolicy(); p.enter(); assertTrue(p.claim(true, false))
        repeat(3) { p.enter(); assertFalse(p.claim(true, false)) }
    }
    @Test fun manualDisconnectOrManualAttemptSuppressesAutomaticConnection() {
        val p = ForegroundConnectionPolicy(); p.enter(); p.manualAction()
        p.enter(); assertFalse(p.claim(true, false))
    }
    @Test fun nextActualForegroundVisitGetsOneAttempt() {
        val p = ForegroundConnectionPolicy(); p.enter(); p.manualAction(); p.leave(); p.enter()
        assertTrue(p.claim(true, false)); assertFalse(p.claim(true, false))
    }
    @Test fun activeSessionPreventsCompetingConnection() {
        val p = ForegroundConnectionPolicy(); p.enter()
        assertFalse(p.claim(true, true))
    }
    @Test fun failedAttemptHasNoAutomaticRetryInSameVisit() {
        val p = ForegroundConnectionPolicy(); p.enter(); p.claim(true, false)
        assertFalse(p.claim(true, false)); p.leave(); assertFalse(p.claim(true, false))
    }

    @Test fun foregroundPowerCycleRearmsExactlyOnceWithoutResume() {
        val p = ForegroundConnectionPolicy(); p.enter(); assertTrue(p.claim(true,false))
        p.bluetoothOff(); p.bluetoothOff(); p.bluetoothOn()
        assertTrue(p.claim(true,false)); p.bluetoothOn(); assertFalse(p.claim(true,false))
    }
    @Test fun manualDisconnectSurvivesPowerCycleUntilNextVisit() {
        val p = ForegroundConnectionPolicy(); p.enter(); p.manualAction()
        p.bluetoothOff(); p.bluetoothOn(); assertFalse(p.claim(true,false))
        p.leave(); p.enter(); assertTrue(p.claim(true,false))
    }
    @Test fun successfulManualConnectionAllowsSubsequentPowerRecovery() {
        val p = ForegroundConnectionPolicy(); p.enter(); p.manualAction(); p.manualConnection()
        assertFalse(p.claim(true,false)); p.bluetoothOff(); p.bluetoothOn()
        assertTrue(p.claim(true,false))
    }
    @Test fun backgroundPowerRecoveryCannotStartService() {
        val p = ForegroundConnectionPolicy(); p.enter(); p.claim(true,false); p.leave()
        p.bluetoothOff(); p.bluetoothOn(); assertFalse(p.claim(true,false))
        assertFalse(p.allowsAutomaticStart)
        p.enter(); assertTrue(p.claim(true,false))
    }
    @Test fun busyCleanupDoesNotConsumeRestoredAttempt() {
        val p = ForegroundConnectionPolicy(); p.enter(); p.claim(true,false)
        p.bluetoothOff(); p.bluetoothOn(); assertFalse(p.claim(true,true))
        assertTrue(p.claim(true,false)); assertFalse(p.claim(true,false))
    }
    @Test fun cancelledManualSelectionSuppressesAutomaticStartAlreadyDispatched() {
        val p = ForegroundConnectionPolicy(); p.enter(); p.claim(true,false)
        p.manualAction(); assertFalse(p.allowsAutomaticStart)
    }
}
