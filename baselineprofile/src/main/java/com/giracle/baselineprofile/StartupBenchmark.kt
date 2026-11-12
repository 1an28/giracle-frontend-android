package com.giracle.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 起動時間 (コールドスタートで最初の画面が出るまで) を測る (#8、D-021)。ログイン済みの状態で動かす。
 * - none: 事前コンパイルなし (Baseline Profile が無い場合に近い)
 * - baselineProfile: APK に同梱した Baseline Profile で事前コンパイル (インストール直後の利用者と同じ)
 * エミュレータの値なので絶対値は実機と違う。同じ実行の中での比較に使う。
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun none() = startup(CompilationMode.None())

    @Test fun baselineProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = Setup.pkg,
        metrics = listOf(StartupTimingMetric()),
        iterations = 10,
        startupMode = StartupMode.COLD,
        compilationMode = mode,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
    }
}
