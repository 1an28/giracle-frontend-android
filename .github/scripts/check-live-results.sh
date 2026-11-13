#!/usr/bin/env bash
# 実サーバーを使うテストが、スキップされずに全部動いたかを確かめる (live-test.yml と手元の両方で使う)。
# サーバーが立っていないと、これらのテストは assume でスキップされて「成功」になってしまうため。
# テストの結果を「スキップ」で表してはいけない (例: S-1 の確認はログに出して成功にしている)。
set -euo pipefail
for c in LiveServerTest UiScreenshotTest; do
  f=app/build/test-results/testDebugUnitTest/TEST-com.giracle.android.$c.xml
  grep -o '<testsuite [^>]*>' "$f"
  grep -q 'skipped="0"' "$f" || { echo "$c にスキップされたテストがある" >&2; exit 1; }
done
