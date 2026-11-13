# 取り込んだスキル

次の 3 つは [chrisbanes/skills](https://github.com/chrisbanes/skills) (コミット `b684174`、2026-11-12) から
そのまま (変更せずに) 取り込んだ。ライセンスは Apache License 2.0 で、全文は [LICENSE-chrisbanes-skills](LICENSE-chrisbanes-skills) にある。

- `compose-performance`: 再コンポーズの量・安定性・フレームごとの State の読み方
- `compose-state-and-effects`: 状態の持ち主・`LaunchedEffect` などの副作用・Flow の集め方
- `kotlin-concurrency-and-flow`: コルーチンの持ち主と寿命・StateFlow / SharedFlow / Channel の使い分け

選んだ理由は docs/DECISIONS.md の D-035 にある。

## このリポジトリで読むときの注意

- 本文から、取り込んでいないスキル (`gradle-run`・`compose-focus-navigation`・`compose-component-design`・
  `compose-ui-testing-patterns`・`kotlin-control-flow`) へのリンクがある。これらは無いので読み飛ばす。
- 「Gradle は `gradle-run` 経由でだけ動かす」とある箇所は、このリポジトリでは当てはまらない。
  `./gradlew` を docs/DEVELOPMENT.md の手順どおりに使う。
- 実機・エミュレータが無いので、計測は JVM のテスト・Compose コンパイラのレポート・CI の結果から行う。
