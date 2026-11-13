package com.giracle.android

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.giracle.android.data.InAppNotice
import com.giracle.android.notify.Notifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 通知を会話の形にまとめる動き (D-022・#26) と、払って消したときに会話を忘れること */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotifierTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun notice(id: String) = InAppNotice("bob (#general)", "本文 $id", "ch1", id, sender = "bob", channelName = "general")

    private fun lines(): Int {
        val nm = app.getSystemService(NotificationManager::class.java)
        val n = shadowOf(nm).allNotifications.single()
        return NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)!!.messages.size
    }

    @Test fun dismissForgetsConversation() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifier.post(app, notice("m1"))
        Notifier.post(app, notice("m2"))
        assertEquals(2, lines())
        // 払って消すと届く削除の知らせが付いている
        assertNotNull(shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.single().deleteIntent)
        // 払って消した後の通知には、前の発言を並べない
        Notifier.forgetChannel("ch1")
        Notifier.post(app, notice("m3"))
        assertEquals(1, lines())
    }
}
