# Giracle Native

セルフホスト型チャット Giracle の Android クライアント (Kotlin + Jetpack Compose)。パフォーマンスを重視している。
文書・コミット・Issue は日本語で書く。

## 前提となる事実

- 参照するリポジトリ (giracle-backend / giracle-frontend-solid / giracle-frontend-flutter) は **読むだけで変更しない**。
  API の正確な仕様は backend の `src/components/*/*.module.ts` を見る。
- 本番サーバー (chat.girak.moe) には開発環境からつながらない。動作確認はローカルに立てたサーバーで行う
  (`devtools/start-local-stack.sh`、手順は docs/DEVELOPMENT.md)。
- 開発環境には実機もエミュレータも無い。確認は JVM 上のテスト (`LiveServerTest` / `UiScreenshotTest`) と、
  CI のエミュレータで配布版 (R8 適用後) を外から操作する確認 (`e2e.yml`、D-018) で行う。実機での操作感は未確認。
- CI の成果物 (zip) はこの環境から取得できない。失敗の手がかりは CI のログに出すようにしてある。
- 配布 APK は CI (release.yml) が Secrets の鍵で署名する。署名鍵はリポジトリに入れない。
  この環境からはタグを push できないので、リリースは release ワークフローの手動実行で出す。
- applicationId は `io.github.u1an28.giracle.kotlin` (D-015)。変えると、入れてあるアプリに上書き更新できなくなる。

## 進め方

- 次に何をやるかは docs/ROADMAP.md を見て決め、終わったら書き換える。
- 持ち主は判断を任せている。迷う点は自分で決めて DECISIONS.md に残し、質問しない。
  CI が通った PR のマージと、リリースを出すかどうかも任されている (出すときは releases/notes/ に利用者向けの案内を書く)。

## 書き方

- 確かめたことと、推測・判断したことを分けて書く。確かめていないことを「できる」と書かない。
- 自分では決められないこと (サーバー側の変更など) は、推奨にせず選択肢として書き、Issue にする。
- 判断したことは docs/DECISIONS.md に、サーバー側に必要な変更は docs/SERVER_CHANGES.md に残す。

## 報告

- push したら CI の結果まで見届けてから報告する。
- 持ち主が既に知っていることは繰り返さない。持ち主にしかできない作業は docs/ROADMAP.md の一覧に置き、報告には新しく増えたものだけ書く。
- 報告の最後に、確かめていないことを書く。
