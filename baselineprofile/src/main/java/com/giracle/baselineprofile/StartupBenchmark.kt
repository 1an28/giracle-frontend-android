package com.giracle.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 起動時間 (コールドスタートで最初の画面が出るまで) を測る (#8、D-021)。ログイン済みの状態で動かす。
 * - none: 事前コンパイルなし (Baseline Profile が無い場合に近い)
 * - baselineProfile: APK に同梱した Baseline Profile で事前コンパイル (インストール直後の利用者と同じ)
 * エミュレータの値なので絶対値は実機と違う。同じ実行の中での比較に使う。
 * Firebase Test Lab の実機 (device-benchmark.yml、D-048) でも動かす。そちらは絶対値として読めるが、
 * サーバーに届かないためログインせず、最初の画面 (ログイン画面) までを測る (引数 login=false)。
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    /** データの無い端末でも、ログイン済みの起動を測れるようにする (login=false のときを除く) */
    @Before fun login() = Setup.ensureLoggedIn()

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
