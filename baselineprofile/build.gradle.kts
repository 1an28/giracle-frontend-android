// 起動・よく使う画面のコードを記録して Baseline Profile を作り、起動時間を測る (#8、D-021)。
// e2e (#12) と同じく com.android.test + self-instrumenting で、配布版と同じ作りの APK を外から操作する。
// Baseline Profile Gradle Plugin は使わない (AGP 9.5 との組み合わせが確かめられないため)。
// CI (.github/scripts/baseline-profile.sh) が、エミュレータで動かして結果を取り出す。
plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "com.giracle.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // エミュレータでの計測は値が揺れるが、相対的な比較に使う (D-021)
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.junit)
}
