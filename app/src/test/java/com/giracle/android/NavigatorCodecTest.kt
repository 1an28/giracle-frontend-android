package com.giracle.android

import com.giracle.android.ui.NavigatorCodec
import com.giracle.android.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Test

/** 画面の履歴の保存形式 (D-032) */
class NavigatorCodecTest {
    @Test fun roundTrip() {
        val screens = listOf(
            Screen.Home, Screen.Channel("c1"), Screen.Channel("c2", "m9"), Screen.ChannelSettings("c1"), Screen.Inbox,
            Screen.Search(), Screen.Search("c1", null), Screen.Search(null, "u1"), Screen.Members, Screen.ChannelBrowser,
            Screen.Settings, Screen.ManageServer,
        )
        screens.forEach { assertEquals(it, NavigatorCodec.decode(NavigatorCodec.encode(it))) }
    }

    @Test fun unknownIsDropped() {
        assertEquals(null, NavigatorCodec.decode("future-screen\tx"))
    }
}
