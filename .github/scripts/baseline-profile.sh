#!/usr/bin/env bash
# CI 用: Baseline Profile の記録と、起動時間の計測 (#8、D-021)。
# 前提: emulator-start.sh で起動済み、localhost:3333 にサーバー (local-server)、APK はビルド済み。
#   baseline-profile.sh generate <app.apk>   記録して app/src/main/baseline-prof.txt を置き換える
#   baseline-profile.sh measure  <app.apk> <名前>   起動時間を測ってログに出す (ログイン済みの状態で使う)
# 出力は bp-out/。
set -uo pipefail
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
ADB="$SDK/platform-tools/adb"
AAPT2=$(ls -d "$SDK"/build-tools/*/aapt2 | sort -V | tail -1)
MODE=$1
APP_APK=$2
TEST_APK=$(ls baselineprofile/build/outputs/apk/debug/*.apk | head -1)
APP_PKG=$("$AAPT2" dump packagename "$APP_APK")
TEST_PKG=$("$AAPT2" dump packagename "$TEST_APK")
OUT=bp-out
mkdir -p "$OUT"

set -e
"$ADB" reverse tcp:3333 tcp:3333
# データを残したまま入れ替える (同じ debug 鍵で署名している)
"$ADB" install -r "$APP_APK"
"$ADB" install -r -t "$TEST_APK"
set +e

run() { # $1: テストクラス, $2: 出力名
  # build.gradle.kts の testInstrumentationRunnerArguments は、am instrument を直接呼ぶと渡らないので、ここで渡す
  "$ADB" shell am instrument -w -e class "$TEST_PKG.$1" -e appPackage "$APP_PKG" -e server http://localhost:3333 \
    -e androidx.benchmark.suppressErrors EMULATOR \
    "$TEST_PKG/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee "$OUT/$2.txt"
  grep -q '^OK (' "$OUT/$2.txt"
}

case "$MODE" in
  generate)
    t0=$(date +%s)
    if ! run BaselineProfileGenerator generate; then
      echo "::error::Baseline Profile の記録が失敗した"
      "$ADB" logcat -d | grep -E "Benchmark|BaselineProfile|AndroidRuntime|TestRunner" | tail -n 80
      exit 1
    fi
    echo "generate: $(( $(date +%s) - t0 ))s"
    "$ADB" pull "/sdcard/Android/media/$TEST_PKG" "$OUT/media" > /dev/null
    # 書き出される名前はライブラリの版で違う (1.5.0 は includeInStartupProfile のとき *-startup-prof.txt だけを書く)。
    # 日時の付いていない方を使う
    PROF=$(find "$OUT/media" -name '*-baseline-prof.txt' ! -name '*-20[0-9][0-9]-*' | head -1)
    [ -n "$PROF" ] || PROF=$(find "$OUT/media" -name '*-startup-prof.txt' ! -name '*-20[0-9][0-9]-*' | head -1)
    if [ -z "$PROF" ]; then echo "::error::記録した Baseline Profile が見つからない"; find "$OUT/media"; exit 1; fi
    {
      echo "# Baseline Profile (#8、D-021)。CI のエミュレータで、起動とよく使う流れ (チャンネルを開いてスクロール・設定) を記録したもの。"
      echo "# .github/scripts/baseline-profile.sh が生成する。手で書き換えない。"
      cat "$PROF"
    } > app/src/main/baseline-prof.txt
    echo "profile: $PROF"
    echo "rules: $(grep -vc '^#' app/src/main/baseline-prof.txt) (アプリ: $(grep -c 'com/giracle/android' app/src/main/baseline-prof.txt))"
    ;;
  measure)
    NAME=${3:-measure}
    if ! run StartupBenchmark "$NAME"; then echo "::warning::起動時間の計測 ($NAME) が失敗した"; exit 0; fi
    # 結果の要約 (timeToInitialDisplayMs の min / median / max) を見出し付きで出す
    echo "::group::起動時間 ($NAME)"
    grep -E "StartupBenchmark|timeToInitialDisplayMs|timeToFullDisplayMs" "$OUT/$NAME.txt"
    echo "::endgroup::"
    ;;
esac
