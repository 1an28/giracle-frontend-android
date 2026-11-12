pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "giracle-frontend-android"
include(":app")
// 配布版をエミュレータで外から操作する確認 (#12)。Baseline Profile の記録 (#8) も同じ形のモジュールで作れる
include(":e2e")
