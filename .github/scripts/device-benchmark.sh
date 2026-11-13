#!/usr/bin/env bash
# CI 用: Firebase Test Lab の実機で起動時間を測り、結果をログに出す (D-046)。
# 前提: gcloud は認証済み (サービスアカウント)、APK はビルド済み。DEVICE が空なら端末を自動で選ぶ。
# サーバーには届かないので、ログインせずに最初の画面 (ログイン画面) までを測る (login=false)。
set -euo pipefail
APP_APK=app/build/outputs/apk/release/app-release.apk
TEST_APK=$(ls baselineprofile/build/outputs/apk/debug/*.apk | head -1)
PKG=com.giracle.baselineprofile
OUT=dbench-out
mkdir -p "$OUT"

# 端末の一覧 (枠を使わない)。どの端末で測ったかを後で比べられるよう、毎回出す
echo "::group::実機の一覧"
gcloud firebase test android models list --filter=form=PHYSICAL
echo "::endgroup::"
if [ -z "${DEVICE:-}" ]; then
  gcloud firebase test android models list --filter=form=PHYSICAL --format=json > "$OUT/models.json"
  DEVICE=$(jq -r '[.[] | select((.brand // "" | ascii_downcase) == "google")
      | {id, v: ([.supportedVersionIds[] | tonumber] | max)} | select(.v >= 28)]
      | sort_by(-.v, .id) | .[0] // empty | "model=\(.id),version=\(.v)"' "$OUT/models.json")
  [ -n "$DEVICE" ] || { echo "::error::使える実機が見つからない"; exit 1; }
fi
echo "device: $DEVICE"

set +e
gcloud firebase test android run \
  --type instrumentation \
  --app "$APP_APK" \
  --test "$TEST_APK" \
  --device "$DEVICE,locale=ja,orientation=portrait" \
  --test-targets "class $PKG.StartupBenchmark" \
  --environment-variables "appPackage=io.github.u1an28.giracle.kotlin,login=false,additionalTestOutputDir=/sdcard/Download,no-isolated-storage=true" \
  --directories-to-pull /sdcard/Download \
  --no-record-video \
  --timeout 30m \
  --client-details matrixLabel="device-benchmark ${GITHUB_SHA:0:7}" \
  2>&1 | tee "$OUT/gcloud.txt"
status=${PIPESTATUS[0]}
set -e

# 結果の置き場所 (Test Lab が用意する無料のバケット) を gcloud の出力から読む
dir=$(grep -oE 'test-lab-[a-z0-9-]+/[^]/ ]+' "$OUT/gcloud.txt" | head -1 || true)
if [ -z "$dir" ]; then echo "::error::結果の置き場所が分からない (gcloud の終了コード $status)"; exit 1; fi
echo "results: gs://$dir"
gsutil -m cp -r "gs://$dir" "$OUT/results" > /dev/null 2>&1 \
  || echo "::warning::結果を取り出せなかった (サービスアカウントの権限を確かめる)"
json=$(find "$OUT/results" -name '*benchmarkData.json' 2>/dev/null | head -1 || true)
if [ -n "$json" ]; then
  echo "::group::benchmarkData.json"
  cat "$json"
  echo "::endgroup::"
  {
    echo "## 実機の計測 ($DEVICE、ログイン前の画面までのコールドスタート)"
    jq -r '"端末: \(.context.build.brand) \(.context.build.model) (API \(.context.build.version.sdk))",
      (.benchmarks[] | "\(.className | split(".") | last)#\(.name)",
        (.metrics // {} | to_entries[] | "  \(.key): min \(.value.minimum) / median \(.value.median) / max \(.value.maximum)"),
        (.sampledMetrics // {} | to_entries[] | "  \(.key): P50 \(.value.P50) / P90 \(.value.P90) / P95 \(.value.P95) / P99 \(.value.P99)"))' "$json"
  } | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
else
  echo "::warning::benchmarkData.json が無い。計測の出力とログの末尾を出す"
  find "$OUT/results" -name 'instrumentation.results' -exec tail -n 60 {} \; 2>/dev/null || true
  find "$OUT/results" -name 'logcat' -exec sh -c 'grep -E "Benchmark|TestRunner|AndroidRuntime|GiracleNav|GiracleUncaught" "$1" | tail -n 80' _ {} \; 2>/dev/null || true
fi
if [ "$status" != 0 ]; then echo "::error::Test Lab の実行が失敗した (gcloud の終了コード $status)"; exit "$status"; fi
