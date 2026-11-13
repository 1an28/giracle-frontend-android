package com.giracle.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.giracle.android.ui.OfflineBanner
import com.giracle.android.ui.common.TestTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 読み込みに失敗して前回の内容を出しているときの帯 (#82)。押せる範囲と、やり直し中の表示を確かめる */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class OfflineBannerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun retryThenShowsProgress() {
        var loading by mutableStateOf(false)
        var retried = 0
        rule.setContent { OfflineBanner(loading = loading, onRetry = { retried++; loading = true }) }
        rule.onNodeWithText("オフライン", substring = true).assertIsDisplayed()
        assertEquals(emptyList<String>(), rule.smallTouchTargets(rule.density))
        rule.onNodeWithTag(TestTags.OFFLINE_RETRY).performClick()
        assertEquals(1, retried)
        // やり直している間は「再試行」を出さない (二重に押せない)
        rule.onNodeWithText("つないでいます", substring = true).assertIsDisplayed()
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasTestTag(TestTags.OFFLINE_RETRY)).fetchSemanticsNodes().size)
    }
}
