# 判断記録 (Decision Log)

このファイルには、開発中に自分で判断したことを時系列で残す。
各項目は「判断」「理由」「代替案」の順で書く。

---

## D-001 技術選定: Kotlin + Jetpack Compose (ネイティブ)

- **判断**: Kotlin によるネイティブ Android アプリとし、UI は Jetpack Compose (Material 3) で書く。
- **理由**
  - Flutter は Dart VM と描画エンジン (Impeller) をアプリに同梱するため、APK サイズとメモリが増え、
    起動時にエンジンの初期化が入る。同一アプリを Native(Compose)/KMP/Flutter で作り Macrobenchmark
    (Pixel 4a, 5 回) で比べた公開ベンチマーク
    (https://github.com/jacobras/flutter-vs-native-vs-kmp) では、起動時間の中央値が
    Native 413ms / Flutter 634ms、APK サイズが 1.46MB / 6.83MB だった。
  - Compose と従来の View (RecyclerView) の比較では、Google 公式の Hero benchmarks で
    「Compose 1.9 以降はスクロール時のジャンクが View と同等」とされている
    (https://developer.android.com/develop/ui/compose/performance)。Compose 単独の APK は
    View+AppCompat 構成と同程度のサイズ (公式 Sunflower 比較:
    https://developer.android.com/develop/ui/compose/migrate/compare-metrics)。
  - Compose は Google が推奨する現行の UI ツールキットで、今後も更新が続く。View(XML) は保守モード。
  - 「サポートが続いている技術」という条件に、Kotlin / Compose / AndroidX / OkHttp はいずれも合う。
- **代替案**
  - View(XML)+RecyclerView: 最速を突き詰められる余地はわずかにあるが、コード量が増え、保守モードの技術になる。
  - Kotlin Multiplatform / Compose Multiplatform: iOS も視野に入るが今回は Android のみなので不要。

## D-002 主要ライブラリとバージョン (2026-11 時点の最新安定版)

| 用途 | 採用 | バージョン |
| --- | --- | --- |
| ビルド | Android Gradle Plugin | 9.5.1 (Gradle 9.6 以上が必須) |
| 言語 | Kotlin (AGP 9 の組み込み Kotlin サポートを使用) | 2.4.21 |
| UI | Compose BOM / Material 3 | 2026.09.00 |
| HTTP / WebSocket | OkHttp | 5.5.1 |
| JSON | kotlinx.serialization | 1.13.0 |
| 画像 | Coil 3 (OkHttp を共有) | 3.6.3 |
| 起動高速化 | androidx.profileinstaller + Baseline Profile | 1.4.1 |

- DI フレームワーク (Hilt 等) は使わない。起動時の初期化コストとビルド時間を減らすため、
  手書きの小さな依存コンテナ (`AppGraph`) にする。
- HTTP と WebSocket と画像読み込みで **同じ OkHttpClient** を共有し、接続プール・
  Cookie (トークン) を共有する。アイコン画像などは認証が必要なため、これで追加の仕組みなしに読める。

## D-003 Flutter 版と同じ端末に並べて入れられるようにする

- Flutter 版の applicationId は `com.giracle.giracle`。本アプリは `com.giracle.android` とし、
  アプリ名も「Giracle Native」にしてホーム画面で区別できるようにする。

## D-004 署名鍵をリポジトリに入れない

- リポジトリは公開 (public) のため、release 署名鍵はコミットしない。
  `keystore.properties` (gitignore 済み) があればそれで署名し、無ければ debug 鍵で署名する。
- 配布用 APK に使った鍵ファイルはユーザーに直接渡し、以後の更新ビルドで同じ鍵を使えるようにする。

## D-005 画面遷移はライブラリを使わず自前の小さなスタックにする

- **判断**: Navigation Compose を使わず、`ui/Nav.kt` の `Navigator` (画面のリスト) で遷移する。
- **理由**: 画面数が少なく、深いリンクや引数の複雑な受け渡しが不要。依存と初期化コストを減らせる。
  システムの「戻る」は `BackHandler` で処理し、チャンネル間の移動はスタックを積まずに置き換える。

## D-006 サーバー URL は Web 版と同じ配置 (`/api/` と `/ws`) を前提にする

- **判断**: ユーザーは `https://chat.example.com` のようにホストだけ入れる。HTTP は `/api/...`、
  WebSocket は `/ws` に接続する。開発用に「バックエンドへ直接接続 (`/api` を付けない)」も選べる。
- **理由**: Web 版 (giracle-frontend-solid) の vite.config.ts がこの配置でリバースプロキシしており、
  本番も同じ構成と考えられる。Flutter 版も同じ扱い (`/api` を自動付与)。
- **WebSocket の Origin**: バックエンドは Origin ヘッダが無いと 403、CORS_ORIGIN 設定時は一覧と一致しないと 403。
  ネイティブアプリには本来 Origin が無いため、サーバー URL から作った Origin (`https://chat.example.com`) を送る。
  Web 版と同じホストで運用していれば CORS_ORIGIN に含まれているはず。含まれていない場合は画面上部に案内を出す。
- **Origin の扱いは暫定**: アプリが Web 版の Origin を名乗って接続するのは、今のサーバーで動かすための仮の方法。
  アプリ向けの正式な接続方法 (サーバー側の入口) は友人 (サーバーの作者) と相談して決める予定 ([#7](https://github.com/1an28/giracle-frontend-kotlin/issues/7))。

## D-007 トークンは Cookie Jar を使わず自前で付ける

- **判断**: ログイン応答の `Set-Cookie: token=...` を取り出して保存し、HTTP・WebSocket・画像・ダウンロードの
  すべてに `Cookie: token=...` を付ける (OkHttp のインターセプタ 1 か所)。
- **理由**: 4 種類の通信で同じ認証を確実に共有でき、Cookie Jar より単純で速い。
  サーバーが期限延長で Cookie を再発行したら、その値で上書きする。

## D-008 状態管理は 1 つのストア + チャンネルごとの StateFlow

- **判断**: Web 版の stores と WS ハンドラをまとめた `ChatStore` を作る。履歴はチャンネルごとに別の StateFlow。
- **理由**: 表示中のチャンネル以外の更新で再描画が起きないようにするため。ユーザー名などは「その人の分だけ」購読する
  (`rememberUser`) ので、他人のプロフィール更新でメッセージ一覧全体が描き直されない。
- 履歴はメモリ上に最大 600 件。超えたら反対側を捨てる (Web 版は 120 件で切る。ネイティブのリストは
  見えている行しか描かないので多めに持てる)。

## D-009 起動の高速化

- 前回の状態 (チャンネル・ユーザー・各チャンネル最新 40 件など) を背面に回ったときに保存し、次回起動時は
  それを即座に表示してからサーバーの最新に置き換える。開いたチャンネルは必ずサーバーから取り直す
  (保存内容が古くても誤った表示が残らない)。
- アプリ本体の Baseline Profile を同梱する。実機での記録 (Macrobenchmark) は端末が無いためできず、
  アプリのパッケージ全体を対象にするワイルドカード規則にした。
- R8 (縮小・難読化) とリソース縮小、翻訳リソースの日本語・英語限定で release APK は約 2MB。
- DI ライブラリなし、JSON は kotlinx.serialization (リフレクションなし)。

## D-010 通知の受け取り方 (未決定)

- **アプリが前面のとき**: WebSocket の受信を画面上部のバナーで知らせる (Web 版のブラウザ通知に相当)。これは確定。
- **アプリを閉じているとき**: どの方法を標準にするかは **未決定**。着手するときに決める ([#6](https://github.com/1an28/giracle-frontend-kotlin/issues/6))。
  今は次の 2 つを、利用者が設定で選べる形で実装してある。
  - **UnifiedPush + 配信アプリ**: サーバーは既に Web Push (VAPID + aes128gcm) で通知を送れる。UnifiedPush の宛先は
    Web Push と同じ形式なので、`platform: "web"` の端末として登録すれば サーバーのコード変更なしで 届く。
    端末側に配信アプリ (ntfy など) が必要。サーバー管理者は VAPID 鍵を設定するだけ。
  - **常時接続**: 前面サービスで WebSocket を保ち続ける設定。配信アプリは不要だが電池を使う。
- **FCM (Google のプッシュ)** は、Firebase プロジェクトとサーバー側の実装が必要なため未実装。選ぶ場合はサーバーの
  変更を友人と相談する。必要な変更は SERVER_CHANGES.md の S-2 にまとめてある。

## D-011 絵文字

- リアクションは Web 版 (emoji-picker-element) と同じ **ショートコード** で送受信する。対応表は
  emoji-picker-element-data (emojibase, Apache-2.0/MIT) から作った `assets/emoji.tsv`。日本語の検索語も含める。
- 端末のフォントで描けない新しい絵文字 (□ になるもの) はピッカーに出さない。表示は `:code:` にする。
- アイコンは巨大な material-icons-extended を入れず、必要なものだけパスを持つ (`ui/common/Icons.kt`)。

## D-012 検証方法

- 開発環境に Android 端末・エミュレータ (KVM なし) が無いため、次の 2 つで確かめる。
  - `LiveServerTest`: 実際のバックエンド (Web 版と同じ `/api` 構成) に 3 ユーザーでつなぎ、主要な操作と
    WebSocket の反映を確認する結合テスト。
  - `UiScreenshotTest`: Robolectric のネイティブ描画で、実サーバーにつないだ実際の画面を PNG に描き出して目視確認。
- 実機での操作感・タッチ・キーボード・通知の表示は確認できていない (最終報告に記載)。

## D-013 アプリ名とログイン名

- Giracle ではユーザー名がログイン名を兼ねる (プロフィールで名前を変えるとログインに使う名前も変わる)。
  アプリはサーバーの仕様に合わせ、特別な扱いはしない。

## D-014 CI とリリース

- **CI の SDK**: `android-actions/setup-android@v3` は既定で廃止済みの SDK パッケージ `tools` を入れようとして
  失敗する (PR #1 の失敗の原因)。ランナーに最初から入っている Android SDK を使い、足りないパッケージだけ
  `.github/scripts/sdk-install.sh` で入れる。
- **リリース**: `v*` タグの push で `release.yml` が GitHub のリリースを作り APK を添付する。
- **署名**: CI が毎回生成する debug 鍵で署名すると、版ごとに鍵が変わり上書きインストールできなくなる。
  そのため、Secrets に署名鍵があれば CI で署名し、無ければリポジトリに置いた署名済み APK (`releases/`) を使う。
  どちらでも、署名証明書の指紋が `releases/SIGNING_CERT_SHA256` と一致しないときは公開しない。
  署名鍵はリポジトリの持ち主が Secrets に登録し、v0.1.0 から CI が署名している。リポジトリに置いていた
  署名済み APK は不要になったので削除した (Secrets が使えないときの予備の仕組みはワークフローに残す)。
- タグの push ができない環境 (このセッションの Git 権限) でも出せるよう、release ワークフローは手動実行にも対応し、
  そのときはワークフロー自身がタグを付ける。
