package dev.sounddeck

import android.bluetooth.BluetoothManager
import android.Manifest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import dev.sounddeck.data.BluetoothPowerObserver
import dev.sounddeck.data.HeadphoneRepository
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Adapter broadcasts on the dedicated emulator; no headset/LC3 success is inferred. */
class BluetoothRecoveryTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun realAdapterOffOnIsHandledWithoutLeavingForeground() {
        val i=InstrumentationRegistry.getInstrumentation()
        fun shell(command:String)=i.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { r -> r.readText() }
        }
        assumeTrue("Emulator only",shell("getprop ro.kernel.qemu").trim()=="1")
        i.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter=i.targetContext.getSystemService(BluetoothManager::class.java).adapter
        assumeTrue(adapter!=null)
        val initiallyEnabled=adapter.isEnabled
        val events=LinkedBlockingQueue<String>()
        val observer=BluetoothPowerObserver({events.offer("off")},{events.offer("on")})
        try {
            compose.runOnUiThread { observer.start(compose.activity) }
            shell("svc bluetooth disable")
            var off=events.poll(10,TimeUnit.SECONDS)
            if (off==null && !initiallyEnabled) { shell("svc bluetooth enable"); Thread.sleep(1500); events.clear(); shell("svc bluetooth disable"); off=events.poll(10,TimeUnit.SECONDS) }
            assertEquals("off",off)
            val end=System.currentTimeMillis()+10000
            while(adapter.isEnabled && System.currentTimeMillis()<end) Thread.sleep(50)
            assertFalse(adapter.isEnabled)
            events.clear(); shell("svc bluetooth enable")
            var on:String?=null
            val onEnd=System.currentTimeMillis()+10000
            while(on!="on" && System.currentTimeMillis()<onEnd) on=events.poll(100,TimeUnit.MILLISECONDS)
            assertEquals("on",on)
            compose.waitForIdle()
            compose.runOnIdle {
                assertTrue(compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                val diagnostic=compose.activity.repository.state.value.diagnostics
                assertTrue(diagnostic.any{it.contains("Bluetooth unavailable")})
                assertTrue(diagnostic.any{it.contains("Bluetooth ON; foreground reconnect")})
                assertFalse(compose.activity.repository.state.value.ready)
            }
        } finally {
            compose.runOnUiThread { observer.stop(compose.activity) }
            shell(if(initiallyEnabled) "svc bluetooth enable" else "svc bluetooth disable")
            i.uiAutomation.dropShellPermissionIdentity()
        }
    }
    @Test fun automaticTargetWaitsForPreviousServiceToBeDestroyed() = runBlocking {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val repository=HeadphoneRepository(c)
        val oldService=Any()
        withContext(Dispatchers.Main) { repository.foregroundStarted(); repository.serviceStarted(oldService) }
        val attempt=async(Dispatchers.Main) { repository.automaticTarget() }
        delay(150)
        assertFalse("must not race a closing service",attempt.isCompleted)
        withContext(Dispatchers.Main) { repository.foregroundStopped(); repository.serviceStopped(oldService) }
        assertNull(withTimeout(1000) { attempt.await() })
    }
}
