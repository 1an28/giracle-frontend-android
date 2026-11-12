// 配布版 (R8 適用後) の APK を、エミュレータ上で外から操作して確かめるテスト (#12)。
// アプリのコードには依存せず、UI Automator で画面の resource-id (TestTags の名前、D-016) を探して操作する。
// self-instrumenting にして、このテスト APK だけで動くようにする (アプリと同じ鍵で署名しなくてよい)。
// Baseline Profile の記録 (#8) も同じ形 (com.android.test + self-instrumenting) で作れる。
plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "com.giracle.e2e"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.junit)
}
