package dev.sounddeck

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import dev.sounddeck.data.SuccessfulConnectionStore
import dev.sounddeck.protocol.ControlTransport
import org.junit.Assert.*
import org.junit.Test

class SuccessfulConnectionStoreTest {
    @Test fun onlySuccessfulEndpointSurvivesStoreRecreationAndFailedSelections() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("connection_history_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            prefs.edit().putString("address", "synthetic-selected").putString("controlPreference", "AUTO").commit()
            assertNull(SuccessfulConnectionStore(prefs).read())
            SuccessfulConnectionStore(prefs).record("synthetic-success", ControlTransport.BLE)
            prefs.edit().putString("address", "synthetic-failed-selection").commit()
            val restored = SuccessfulConnectionStore(prefs).read()!!
            assertEquals("synthetic-success", restored.address); assertEquals(ControlTransport.BLE, restored.transport)
            prefs.edit().putString("successfulTransport", "invalid").commit()
            assertNull(SuccessfulConnectionStore(prefs).read())
        } finally { prefs.edit().clear().commit() }
    }
}
