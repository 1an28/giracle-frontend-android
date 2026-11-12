package com.giracle.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Baseline Profile を記録する (#8、D-021)。起動と、よく使う流れ (チャンネルを開いてスクロール・設定) で使われたコードを記録する。
 * 結果は端末の /sdcard/Android/media/com.giracle.baselineprofile/ に *-baseline-prof.txt として出る (CI が取り出す)。
 * 記録する APK は難読化していない配布版 (nonMinifiedRelease)。クラス名がソースと同じなので、
 * app/src/main/baseline-prof.txt に置けば、配布版のビルドで R8 が名前を合わせて取り込む。
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun generate() {
        val channelId = Setup.prepare()
        rule.collect(packageName = Setup.pkg, includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait()
            Setup.loginIfNeeded(device)
            Setup.typicalUse(device, channelId)
        }
    }
}
