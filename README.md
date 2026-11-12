# Giracle Native (Android)

セルフホスト型チャット [Giracle](https://github.com/NfoAlex/giracle-backend) の Android クライアント。
**軽さと速さ** を優先して、Kotlin + Jetpack Compose のネイティブアプリとして作っている。

- Web 版 ([giracle-frontend-solid](https://github.com/NfoAlex/giracle-frontend-solid)) でできることはすべてできる ([機能一覧](docs/FEATURES.md))
- 配布版 APK は約 2MB。起動時は前回の内容を即座に表示し、裏で最新に置き換える
- アプリを閉じていても通知を受け取れる (UnifiedPush。サーバーのコード変更なし)
- Flutter 版 ([giracle-frontend-flutter](https://github.com/ayok01/giracle-frontend-flutter)、`com.giracle.giracle`) とは
  別アプリ (`com.giracle.android`、表示名「Giracle Native」) なので、同じ端末に並べて入れられる

## 入れ方

1. 配布された `giracle-native-<版>.apk` をスマホにダウンロードする
2. ファイルを開き、「この提供元のアプリを許可」を求められたら、ダウンロードに使ったアプリ (Chrome やファイル) を許可する
3. 「インストール」を押す (Play プロテクトの確認が出たら「インストールする」を選ぶ)
4. アプリを開き、サーバーの URL (Web 版を開くときの URL。例 `https://chat.example.com`) を入れて「接続」→ ログイン

対応: Android 8.0 以上。

## 通知

- アプリを開いているとき: 画面上部に表示
- アプリを閉じているとき (おすすめ): 端末に [ntfy](https://ntfy.sh) などの UnifiedPush 配信アプリを入れ、
  「設定 > 通知 > プッシュ通知 > 有効にする」。サーバーに VAPID 鍵が設定されている必要がある ([詳細](docs/SERVER_CHANGES.md))
- 配信アプリを入れたくない場合: 「バックグラウンドでも接続を保つ」を ON (電池を多めに使う)

## 開発

[開発の手引き](docs/DEVELOPMENT.md) / [判断の記録](docs/DECISIONS.md) / [サーバーへの要望・不具合報告](docs/SERVER_CHANGES.md)

## ライセンス表記

- 絵文字データ: [emoji-picker-element-data](https://github.com/nolanlawson/emoji-picker-element-data) (Apache-2.0)、
  元データ [emojibase](https://emojibase.dev) (MIT)
- アイコンの一部: [Material Icons](https://github.com/google/material-design-icons) (Apache-2.0)
