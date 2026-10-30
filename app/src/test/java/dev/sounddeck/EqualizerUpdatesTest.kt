package dev.sounddeck

import dev.sounddeck.data.*
import dev.sounddeck.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EqualizerUpdatesTest {
    private fun target(gain: Int) = ControlAction.Equalizer(0xa1,List(6){gain})

    @Test fun continuousDragIsBoundedAndAlwaysFlushesFinalTarget() = runTest {
        val queue=EqualizerUpdates()
        val applied=mutableListOf<Pair<Long,ControlAction.Equalizer>>()
        assertTrue(queue.offer(target(0)))
        val drain=launch { queue.drain { applied.add(currentTime to it); delay(40); true } }
        repeat(100) { index ->
            advanceTimeBy(10)
            queue.offer(target(index % 21))
        }
        queue.offer(target(20))
        advanceUntilIdle(); drain.join()
        assertTrue("100 updates produce at most 7 writes",applied.size in 1..7)
        assertEquals(target(20),applied.last().second)
        assertTrue(applied.zipWithNext().all { (a,b) -> b.first-a.first>=150 })
        assertFalse(queue.active)
    }

    @Test fun inFlightWriteFinishesBeforeLatestTargetAndDuplicatesAreSkipped() = runTest {
        val queue=EqualizerUpdates(); val applied=mutableListOf<ControlAction.Equalizer>()
        queue.offer(target(1))
        launch { queue.drain { applied.add(it); delay(300); true } }
        advanceTimeBy(151)
        queue.offer(target(2)); queue.offer(target(3))
        advanceTimeBy(451)
        queue.offer(target(3))
        advanceUntilIdle()
        assertEquals(listOf(target(1),target(3)),applied)
    }

    @Test fun failedReadbackDiscardsQueuedValuesAndDoesNotRetry() = runTest {
        val queue=EqualizerUpdates(); val applied=mutableListOf<ControlAction.Equalizer>()
        queue.offer(target(1))
        launch { queue.drain { applied.add(it); delay(300); false } }
        advanceTimeBy(151); queue.offer(target(20)); advanceUntilIdle()
        assertEquals(listOf(target(1)),applied); assertFalse(queue.active)
        queue.offer(target(5)); queue.drain { applied.add(it); true }
        assertEquals(listOf(target(1),target(5)),applied)
    }

    @Test fun disconnectCancelsPendingTargetAndNewSessionStartsEmpty() = runTest {
        val queue=EqualizerUpdates(); val applied=mutableListOf<ControlAction.Equalizer>()
        queue.offer(target(1))
        val job=launch { queue.drain { applied.add(it); true } }
        advanceTimeBy(100); job.cancelAndJoin()
        assertFalse(queue.active); assertTrue(applied.isEmpty())
        queue.offer(target(5)); queue.drain { applied.add(it); true }
        assertEquals(listOf(target(5)),applied)
    }

    @Test fun capabilityGateAllowsStandardPresetAndKeepsCurrentCustomSlot() {
        val state=HeadphoneState(connection=ConnectionState.READY,functions=setOf(0x50),eqAvailable=true,
            eq=SonyEvent.Equalizer(0x10,List(6){10}),
            eqCapability=SonyEvent.EqCapability(6,21,listOf(EqPreset(0x10,""),EqPreset(0xa1,""),EqPreset(0xa2,""))))
        assertTrue(state.canAdjustEqBands); assertEquals(0xa1,state.customEqPreset)
        assertEquals(0xa2,state.copy(eq=SonyEvent.Equalizer(0xa2,List(6){10})).customEqPreset)
        assertFalse(state.copy(pending="DSEE").canAdjustEqBands)
        assertTrue(state.copy(pending="EQ",eqTarget=target(12)).canAdjustEqBands)
        assertFalse(state.copy(eqAvailable=false).canAdjustEqBands)
        assertFalse(state.copy(connection=ConnectionState.DISCONNECTED).canAdjustEqBands)
        assertFalse(state.copy(eqCapability=state.eqCapability!!.copy(bands=5)).canAdjustEqBands)
        assertFalse(state.copy(eqCapability=state.eqCapability!!.copy(presets=listOf(EqPreset(0x10,"")))).canAdjustEqBands)
    }
}
