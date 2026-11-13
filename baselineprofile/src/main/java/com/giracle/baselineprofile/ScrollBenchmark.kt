package com.giracle.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * チャンネルの履歴をスクロールしたときのフレーム時間を測る (UI_PRINCIPLES.md 8 章、D-048)。
 * 今はエミュレータ (baseline-profile.yml) で、前の実行と比べるために動かす (suppressErrors=EMULATOR を付けたときだけ動く)。
 * Firebase Test Lab の実機 (device-benchmark.yml) では、端末からサーバーへ届ける方法が決まるまで動かせない (ROADMAP)。
 * コンパイルは利用者と同じ (APK に同梱した Baseline Profile を使う既定の状態)。
 */
@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun scrollHistory() {
        // 何度か大きく払っても端に着かない量。古い方へ払うと、続きの読み込みも含まれる (利用者と同じ)
        val channelId = Setup.prepare(messages = 200)
        rule.measureRepeated(
            packageName = Setup.pkg,
            metrics = listOf(FrameTimingMetric()),
            iterations = 5,
            compilationMode = CompilationMode.DEFAULT,
            setupBlock = {
                pressHome()
                startActivityAndWait()
                Setup.loginIfNeeded(device)
                Setup.openChannel(device, channelId)
            },
        ) {
            val list = device.findObject(By.res("channel.messages")) ?: error("履歴の一覧が無い")
            // 端からの操作が戻る操作に取られないよう、左右に余白を取る
            list.setGestureMargin(device.displayWidth / 5)
            repeat(3) { list.fling(Direction.UP) }
            repeat(3) { list.fling(Direction.DOWN) }
            device.waitForIdle()
        }
    }
}
