package dev.sounddeck

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import dev.sounddeck.control.*
import dev.sounddeck.data.*
import dev.sounddeck.protocol.*
import org.junit.Assert.*
import org.junit.Test

class StatusNotificationTest {
    @Test fun statusContainsOnlyReportedModeAndBatteriesWithoutAnyActions() {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        for (mode in NoiseMode.entries) {
            val s=HeadphoneState(connection=ConnectionState.READY,noiseSettled = true, noise=NoiseState(mode,7,false,1),
                left=BatteryReading(0,false),right=BatteryReading(100,true),pending="等待確認")
            val n=StatusNotification.build(c,s)
            assertTrue(n.actions.isNullOrEmpty())
            assertEquals(mode.label,n.extras.getString(Notification.EXTRA_TITLE))
            assertEquals("左耳 0%｜充電盒 —｜右耳 100%",n.extras.getString(Notification.EXTRA_TEXT))
            assertNotNull(n.contentIntent)
            assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        }
        val unknown=StatusNotification.build(c,HeadphoneState())
        assertEquals("環境聲音模式尚未確認",unknown.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("左耳 —｜充電盒 —｜右耳 —",unknown.extras.getString(Notification.EXTRA_TEXT))
    }
    @Test fun androidSystemPublishesActionlessNotification() {
        val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
        val emulator=i.uiAutomation.executeShellCommand("getprop ro.kernel.qemu").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { r -> r.readText().trim() }
        }
        org.junit.Assume.assumeTrue(emulator=="1")
        val manager=c.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(ControlService.CHANNEL,"耳機連線狀態",NotificationManager.IMPORTANCE_LOW))
        i.uiAutomation.adoptShellPermissionIdentity("android.permission.POST_NOTIFICATIONS")
        try {
            val state=HeadphoneState(connection=ConnectionState.READY,noiseSettled = true, noise=NoiseState(NoiseMode.AMBIENT,7,false,1),left=BatteryReading(72,false))
            manager.notify(505,StatusNotification.build(c,state))
            val device=androidx.test.uiautomator.UiDevice.getInstance(i)
            device.openNotification()
            // notify() posts asynchronously. Wait for System UI before inspecting the published record.
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.textContains("左耳 72%")),3000))
            val published=manager.activeNotifications.single { it.id==505 }.notification
            assertTrue(published.actions.isNullOrEmpty())
            assertEquals("左耳 72%｜充電盒 —｜右耳 —",published.extras.getString(Notification.EXTRA_BIG_TEXT))
            val dir=java.io.File(c.getExternalFilesDir(null),"validation-${BuildConfig.VERSION_NAME}").apply{mkdirs()}
            device.takeScreenshot(java.io.File(dir,"synthetic-notification.png"))
            device.pressBack()
        } finally { manager.cancel(505);i.uiAutomation.dropShellPermissionIdentity() }
    }
}
