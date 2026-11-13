package com.giracle.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `smallTouchTargets` が、実際に押せる範囲が 48dp に足りない要素だけを見つけること (D-045) */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class TouchTargetCheckTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun findsOnlySmallTargets() {
        rule.setContent {
            Column {
                // 隣と接している小さい要素は、広げた範囲が隣に重なるので見つける
                Box(Modifier.size(40.dp).background(Color.Gray).clickable { }.testTag("small40"))
                Box(Modifier.size(48.dp).background(Color.Gray).clickable { }.testTag("ok48"))
                Box(Modifier.size(44.dp).background(Color.Gray).clickable { }.testTag("small44"))
                // 周りが空いていれば、Compose が 48dp に広げるので問題にしない
                Box(Modifier.padding(16.dp).size(32.dp).background(Color.Gray).clickable { }.testTag("isolated32"))
                // 見た目の小さいアイコンでも、IconButton は押せる範囲を 48dp に広げる
                IconButton(onClick = {}, Modifier.testTag("iconButton")) { Icon(Icons.Default.Search, "検索", Modifier.size(16.dp)) }
                Box(Modifier.size(24.dp).background(Color.Gray)) // 押せない要素は数えない
            }
        }
        val found = rule.smallTouchTargets(rule.density)
        assertEquals(listOf("small40: 40x40dp (ok48 と近い)", "small44: 44x44dp (ok48 と近い)"), found)
    }
}
