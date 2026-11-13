package com.giracle.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
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

    /** ダイアログの中の小さい要素を、下に隠れた画面の要素と比べない (ウィンドウごとに比べる) */
    @Test fun windowsAreComparedSeparately() {
        rule.setContent {
            Column { repeat(10) { i -> Box(Modifier.fillMaxWidth().height(80.dp).clickable { }.testTag("bg$i")) } }
            androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
                Box(Modifier.size(200.dp).background(Color.White)) {
                    Box(Modifier.padding(40.dp).size(30.dp).background(Color.Gray).clickable { }.testTag("dialogSmall"))
                }
            }
        }
        assertEquals(emptyList<String>(), rule.smallTouchTargets(rule.density))
    }

    /** 一覧の端で一部だけ見えている行を、小さい要素として数えない (切り取る前の大きさで判定する) */
    @Test fun clippedRowsAreNotSmall() {
        rule.setContent {
            // 4 行目は 30dp だけ見える
            LazyColumn(Modifier.height(270.dp)) {
                items(10) { i -> Box(Modifier.fillMaxWidth().height(80.dp).clickable { }.testTag("row$i")) }
            }
        }
        assertEquals(emptyList<String>(), rule.smallTouchTargets(rule.density))
    }

    /** TouchHitBehaviorTest と同じ配置で、検査の判定が実際の動きと合うこと */
    @Test fun agreesWithHitBehavior() {
        rule.setContent {
            Column {
                Box(Modifier.size(400.dp, 100.dp)) {
                    Box(Modifier.fillMaxSize().clickable { }.testTag("under"))
                    Box(Modifier.align(Alignment.Center).size(60.dp, 28.dp).clickable { }.testTag("onLayer"))
                }
                Box(Modifier.size(400.dp, 300.dp)) {
                    Box(Modifier.fillMaxSize().clickable { }.testTag("scrim"))
                    Surface(Modifier.align(Alignment.Center).size(300.dp, 200.dp)) {
                        Box(Modifier.fillMaxSize()) { Box(Modifier.align(Alignment.Center).size(40.dp).clickable { }.testTag("onSurface")) }
                    }
                }
            }
        }
        assertEquals(listOf("onLayer: 60x28dp (under と近い)"), rule.smallTouchTargets(rule.density))
    }
}
