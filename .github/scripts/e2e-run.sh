#!/usr/bin/env bash
# CI 用: 起動済みのエミュレータに配布版 APK と確認用 APK (:e2e) を入れ、通しの確認 (#12) を動かす。
# 前提: emulator-start.sh で起動済み、localhost:3333 にサーバー (local-server)、APK はビルド済み。
#   APP_APK : 確かめる APK (既定: assembleRelease の出力)
#   LAN_SERVER : 端末から LAN のアドレスで見たサーバー (既定 http://10.0.2.2:3333 = エミュレータから見たランナー)。
#                電波を切る確認 (#37) とローカルネットワーク権限の確認 (#42) に使う。空にすると飛ばす
# 結果は e2e-out/ (画面・logcat・crash バッファ) に置く。
set -uo pipefail
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
ADB="$SDK/platform-tools/adb"
AAPT2=$(ls -d "$SDK"/build-tools/*/aapt2 | sort -V | tail -1)
APP_APK=${APP_APK:-app/build/outputs/apk/release/app-release.apk}
LAN_SERVER=${LAN_SERVER-http://10.0.2.2:3333}
TEST_APK=$(ls e2e/build/outputs/apk/debug/*.apk | head -1)
APP_PKG=$("$AAPT2" dump packagename "$APP_APK")
TEST_PKG=$("$AAPT2" dump packagename "$TEST_APK")
OUT=e2e-out
mkdir -p "$OUT"
echo "app: $APP_PKG ($APP_APK)  test: $TEST_PKG ($TEST_APK)"

set -e
# 端末の localhost:3333 をランナーのサーバーにつなぐ (アプリが送る Origin が CORS_ORIGIN と一致する)
"$ADB" reverse tcp:3333 tcp:3333
# 権限は付けずに入れる (通知の権限の確認画面が出ることも確かめるため)
"$ADB" install -r "$APP_APK"
"$ADB" install -r -t "$TEST_APK"
"$ADB" shell pm list instrumentation
"$ADB" logcat -c || true
set +e

t0=$(date +%s)
LAN_ARGS=()
[ -n "$LAN_SERVER" ] && LAN_ARGS=(-e lanServer "$LAN_SERVER")
"$ADB" shell am instrument -w -e appPackage "$APP_PKG" -e server http://localhost:3333 "${LAN_ARGS[@]}" \
  "$TEST_PKG/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee "$OUT/instrument.txt"
echo "e2e: $(( $(date +%s) - t0 ))s"

"$ADB" logcat -d > "$OUT/logcat.txt" 2>&1

# よく使う作業を、遅い回線でも測る (D-046)。上の実行は端末の localhost (adb reverse) なので、通信をほぼ待たない。
# LAN のアドレス (エミュレータの仮想ネットワークを通る) につなぎ、エミュレータの遅延 (edge: 80〜400ms) を足す
if [ -n "$LAN_SERVER" ]; then
  "$ADB" logcat -c || true
  "$ADB" emu network delay edge
  t1=$(date +%s)
  "$ADB" shell am instrument -w -e appPackage "$APP_PKG" -e server http://localhost:3333 \
    -e class com.giracle.e2e.ReleaseAppTest#uxTasks -e uxServer "$LAN_SERVER" -e uxNet slow \
    "$TEST_PKG/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee "$OUT/instrument-slow.txt"
  echo "e2e (遅い回線の ux): $(( $(date +%s) - t1 ))s"
  "$ADB" emu network delay none
  "$ADB" logcat -d >> "$OUT/logcat.txt" 2>&1
fi
"$ADB" logcat -b crash -d > "$OUT/crash.txt" 2>&1
"$ADB" pull "/sdcard/Android/data/$TEST_PKG/files/screenshots" "$OUT/" > /dev/null 2>&1 || true

status=0
# 失敗したときの手がかりを、成果物を開かなくても CI のログで読めるように出す
show_clues() {
  echo "::group::logcat (アプリ・起動・クラッシュに関係する行)"
  grep -E "GiracleE2E|GiracleNav|AndroidRuntime|$APP_PKG|ActivityTaskManager: (START|Displayed)|ActivityManager: (Start proc|Process .* has died|Killing)" "$OUT/logcat.txt" | tail -n 150
  echo "::endgroup::"
  # アプリのプロセス (途中で作り直されたものも含む) の記録をすべて出す。プッシュ受信など、裏で起きたことを追うため
  pids=$(grep -oE "Start proc [0-9]+:$APP_PKG" "$OUT/logcat.txt" | grep -oE "[0-9]+" | tr '\n' '|' | sed 's/|$//')
  if [ -n "$pids" ]; then
    echo "::group::アプリのプロセスの記録 (pid: $pids)"
    grep -E "^[0-9-]+ [0-9:.]+ +($pids) " "$OUT/logcat.txt" | grep -vE "ImeTracker|FrameTracker|InputMethod" | tail -n 200
    echo "::endgroup::"
  fi
  echo "::group::プッシュ通知の受信 (UnifiedPush・Play 開発者サービス)"
  grep -E "GiraclePush|UnifiedPush|UP-|FirebaseReceiver|GCM|FCM" "$OUT/logcat.txt" | tail -n 80
  echo "::endgroup::"
  echo "::group::crash バッファ"; cat "$OUT/crash.txt"; echo "::endgroup::"
  # 終了時点の画面の要素 (resource-id・文字・クラス) の一覧
  echo "::group::画面の要素 (uiautomator dump)"
  "$ADB" shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  "$ADB" shell cat /sdcard/ui.xml > "$OUT/ui.xml" 2>&1
  grep -o '<node [^>]*' "$OUT/ui.xml" | sed -E 's/.*text="([^"]*)" resource-id="([^"]*)" class="([^"]*)" package="([^"]*)".*/id=\2 class=\3 text=\1 pkg=\4/' | head -n 120
  echo "::endgroup::"
}
# am instrument は失敗しても終了コードが 0 なので、出力で判断する
grep -q '^OK (' "$OUT/instrument.txt" || { echo "::error::配布版の確認 (e2e) が失敗した。成果物 e2e の instrument.txt と画面を見る"; status=1; }
if [ -n "$LAN_SERVER" ] && ! grep -q '^OK (' "$OUT/instrument-slow.txt"; then
  echo "::error::遅い回線でのよく使う作業の測定 (uxTasks) が失敗した。instrument-slow.txt を見る"
  tail -n 40 "$OUT/instrument-slow.txt"
  status=1
fi
# 条件付きの確認 (assume) は、飛ばされても「OK」になる。指定したのに動いていなければ失敗にする
echo "::group::e2e の結果の印"; grep -o "E2E-RESULT .*" "$OUT/logcat.txt"; echo "::endgroup::"
# よく使う作業の手数と時間 (D-046)。エミュレータの時間は絶対値ではなく、前の実行 (docs/UI_PRINCIPLES.md 9 章の基準) との比較に使う
echo "::group::よく使う作業の手数と時間 (ux)"
grep -oE "E2E-RESULT ux net=.*" "$OUT/logcat.txt" | sed "s/E2E-RESULT ux //"
echo "::endgroup::"
if [ $status = 0 ] && ! grep -q "E2E-RESULT ux_test=done net=fast" "$OUT/logcat.txt"; then
  echo "::error::よく使う作業の測定 (uxTasks) が最後まで動いていない"
  status=1
fi
if [ $status = 0 ] && [ -n "$LAN_SERVER" ] && ! grep -q "E2E-RESULT ux_test=done net=slow" "$OUT/logcat.txt"; then
  echo "::error::遅い回線でのよく使う作業の測定 (uxTasks) が最後まで動いていない"
  status=1
fi
if [ $status = 0 ] && [ -n "$LAN_SERVER" ] && ! grep -q "E2E-RESULT lan_test=done" "$OUT/logcat.txt"; then
  echo "::error::LAN のサーバーでの確認 (lanServerPermissionAndOfflineSend) が最後まで動いていない (飛ばされた?)"
  status=1
fi
[ $status = 0 ] || show_clues
if grep -q "Process: $APP_PKG" "$OUT/crash.txt"; then
  echo "::error::アプリが落ちた (crash.txt。R8 のスタックトレースは mapping.txt で戻せる)"
  cat "$OUT/crash.txt"
  status=1
fi
# 落ちはしないが、アプリ全体の裏の処理から想定外の例外が漏れた (D-036)。見逃さないよう失敗にする
if grep -q "GiracleUncaught" "$OUT/logcat.txt"; then
  echo "::error::想定外の例外が記録された (GiracleUncaught。D-036)"
  grep -A 30 "GiracleUncaught" "$OUT/logcat.txt" | head -n 80
  status=1
fi
exit $status
