package io.github.roberbarras.racha.android.notifications

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.roberbarras.racha.application.notifications.Notice
import io.github.roberbarras.racha.domain.library.ContentId
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/** Notices reach the system's notifications, each in its channel, with a tap that opens the right place. */
@RunWith(AndroidJUnit4::class)
class AndroidNotifierTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun noticesArePostedInTheirChannels() = runBlocking<Unit> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val notifier = AndroidNotifier(context)
        notifier.show(Notice.NewContent(2, listOf("The Expanse"), listOf("S01E02", "S01E03"), ContentId("b".repeat(64))))
        notifier.show(Notice.SyncFailed("unauthorized"))
        val manager = context.getSystemService(NotificationManager::class.java)
        val posted = manager.activeNotifications.map { it.notification }
        assertTrue(posted.any { it.channelId == "racha-new_content" && it.extras.getString("android.title")!!.startsWith("The Expanse") }, "$posted")
        assertTrue(posted.any { it.channelId == "racha-sync" && it.contentIntent != null })
        manager.cancelAll()
    }
}
