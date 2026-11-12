# Giracle Native

セルフホスト型チャット Giracle の Android クライアント (Kotlin + Jetpack Compose)。パフォーマンスを重視している。
文書・コミット・Issue は日本語で書く。

## 前提となる事実

- 参照するリポジトリ (giracle-backend / giracle-frontend-solid / giracle-frontend-flutter) は **読むだけで変更しない**。
  API の正確な仕様は backend の `src/components/*/*.module.ts` を見る。
- 本番サーバー (chat.girak.moe) には開発環境からつながらない。動作確認はローカルに立てたサーバーで行う
  (`devtools/start-local-stack.sh`、手順は docs/DEVELOPMENT.md)。
- 実機もエミュレータも無い。確認は JVM 上のテスト (`LiveServerTest` / `UiScreenshotTest`) で行う。
  配布版 (R8 適用後) が動くかは確かめられていない (#12)。
- 配布 APK は CI (release.yml) が Secrets の鍵で署名する。署名鍵はリポジトリに入れない。
  この環境からはタグを push できないので、リリースは release ワークフローの手動実行で出す。
- applicationId を変えると、入れてあるアプリに上書き更新できなくなる (#9)。

## 書き方

- 確かめたことと、推測・判断したことを分けて書く。確かめていないことを「できる」と書かない。
- 未決定のことは推奨にせず、選択肢として書き、Issue にする (後で閉じればよい)。
- 判断したことは docs/DECISIONS.md に、サーバー側に必要な変更は docs/SERVER_CHANGES.md に残す。

## 報告

- push したら CI の結果まで見届けてから報告する。
- 報告の最後に、確かめていないことと、持ち主にしかできない作業 (Secrets の登録など) をまとめて書く。
