package com.giracle.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose が、小さい押せる要素の押せる範囲を広げるときの動き (D-045)。`smallTouchTargets` はこの動きを前提にしている。
 * 要素の見た目の少し外を押して、どの要素が反応するかを見る。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class TouchHitBehaviorTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var hit = ""

    private fun tapAbove(tag: String, dp: Float) {
        val px = dp * rule.density.density
        rule.onNodeWithTag(tag).performTouchInput { click(Offset(width / 2f, -px)) }
        rule.waitForIdle()
    }

    /** 周りに何も無ければ、見た目の外でも反応する (範囲が広がる) */
    @Test fun isolatedTargetIsExpanded() {
        rule.setContent {
            Box(Modifier.size(400.dp, 100.dp)) {
                Box(Modifier.align(Alignment.Center).size(60.dp, 28.dp).background(Color.Gray).clickable { hit = "child" }.testTag("c"))
            }
        }
        tapAbove("c", 6f)
        assertEquals("child", hit)
    }

    /** 押せる親の中でも、広げた範囲は子が取る */
    @Test fun childWinsOverParent() {
        rule.setContent {
            Box(Modifier.size(400.dp, 100.dp).clickable { hit = "parent" }) {
                Box(Modifier.align(Alignment.Center).size(60.dp, 28.dp).background(Color.Gray).clickable { hit = "child" }.testTag("c"))
            }
        }
        tapAbove("c", 6f)
        assertEquals("child", hit)
    }

    /** 真下に別の押せる要素 (兄弟) があると、そちらが取る (範囲は広がらない) */
    @Test fun layerBelowWins() {
        rule.setContent {
            Box(Modifier.size(400.dp, 100.dp)) {
                Box(Modifier.fillMaxSize().clickable { hit = "under" })
                Box(Modifier.align(Alignment.Center).size(60.dp, 28.dp).background(Color.Gray).clickable { hit = "child" }.testTag("c"))
            }
        }
        tapAbove("c", 6f)
        assertEquals("under", hit)
    }

    /** Surface の上の要素は、Surface の後ろの押せる層 (シートの背景など) に取られない */
    @Test fun surfaceBlocksLayerBehind() {
        rule.setContent {
            Box(Modifier.size(400.dp, 300.dp)) {
                Box(Modifier.fillMaxSize().clickable { hit = "scrim" })
                Surface(Modifier.align(Alignment.Center).size(300.dp, 200.dp)) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.align(Alignment.Center).size(40.dp).background(Color.Gray).clickable { hit = "child" }.testTag("c"))
                    }
                }
            }
        }
        tapAbove("c", 3f)
        assertEquals("child", hit)
    }
}
