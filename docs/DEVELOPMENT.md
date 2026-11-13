# 開発の手引き

## 必要なもの

- JDK 17 以上 (21 で確認)
- Android SDK (platforms;android-37.0, build-tools;37.0.0)。`local.properties` に `sdk.dir=...`
- ローカル検証用: [Bun](https://bun.sh) と Node.js (バックエンドと Web 版を動かす)

## ビルド

```bash
./gradlew :app:assembleDebug     # 開発版 (io.github.u1an28.giracle.kotlin.debug、「Giracle Native (dev)」)
./gradlew :app:assembleRelease   # 配布版 (R8 で縮小、約 2MB)
```

配布版の署名: リポジトリ直下に `keystore.properties` を置く (コミットしない)。無ければ debug 鍵で署名される。

```properties
storeFile=keystore/giracle-native.p12
storePassword=...
keyAlias=giracle
keyPassword=...
```

## リリース

`v0.1.0` のようなタグを push すると、GitHub Actions (`release.yml`) がリリースを作り APK を添付する。
署名鍵の扱いと手順は [releases/README.md](../releases/README.md)。

## ローカルでの動作確認

```bash
devtools/start-local-stack.sh   # バックエンド :3000 と Web 版 :3333 (/api と /ws を転送) を起動。VAPID 鍵も作って入れる
devtools/seed-demo.sh           # デモデータ (admin / password123 を先に Web 版かアプリで作る)
```

アプリからは `http://<PC の IP>:3333` に接続する (本番と同じ `/api` 配置)。
エミュレータからなら `http://10.0.2.2:3333`。バックエンドの `.env` の `CORS_ORIGIN` に、
アプリに入れた URL の Origin を加えないと WebSocket が 403 になる。

## テスト

```bash
./gradlew :app:testDebugUnitTest                                   # 単体テストのみ
GIRACLE_TEST_SERVER=http://localhost:3333 ./gradlew :app:testDebugUnitTest   # 実サーバー結合テストと画面描画も
```

- `LiveServerTest`: 管理者 (`GIRACLE_TEST_ADMIN_USER` / `GIRACLE_TEST_ADMIN_PASS`、既定 admin / password123) で
  招待コードを作り、2 ユーザーで送受信・WebSocket の反映などを確認する。
- `UiScreenshotTest`: 管理者でテストごとに専用のチャンネルを作り、実際の画面を描画して `app/build/screenshots/` に PNG を出す。
  デモデータ (`seed-demo.sh`) は要らない。要素は文言ではなく `ui/common/TestTags.kt` の名前 (testTag) で探す (D-016)。
- 管理者は、サーバーを立てた直後に最初に登録したユーザーがなる:
  `curl -X PUT localhost:3333/api/user/sign-up -H 'content-type: application/json' -d '{"username":"admin","password":"password123"}'`
- CI では `live-test.yml` が同じ手順でサーバーを立ててこれらを動かし、スクリーンショットを成果物 `screenshots` に残す。
- Robolectric はプロキシ環境で自前のダウンロードに失敗するため、Gradle で取得してオフラインで動かす設定にしてある。

## 配布版をエミュレータで確かめる (e2e)

CI (`e2e.yml`) が、配布版 (`assembleRelease`、debug 鍵で署名) をエミュレータに入れ、ランナー上のサーバーにつないで
UI Automator で外から操作する (D-018)。手元で動かす場合 (KVM のある Linux と Android SDK が必要):

```bash
./gradlew :app:assembleRelease :e2e:assembleDebug
devtools/start-local-stack.sh   # 管理者 admin / password123 も作っておく (上の「テスト」参照)
.github/scripts/emulator-start.sh
.github/scripts/e2e-run.sh      # 結果は e2e-out/ (画面・logcat・crash バッファ)
```

- CI は API 35 と API 37 (targetSdk と同じ Android 17) の 2 つのエミュレータで動かす。
- 端末の通信網を通る確認 (機内モードでの送信待ち #37、Android 17 のローカルネットワーク権限 #42) は、
  `adb reverse` の `localhost` ではなく `http://10.0.2.2:3333` (エミュレータから見たランナー) を使う。
  そのため `local-server` はバックエンドの `CORS_ORIGIN` に `http://10.0.2.2:3333` も入れる。`LAN_SERVER=` (空) で飛ばせる。
- 手順は `e2e/src/main/java/com/giracle/e2e/ReleaseAppTest.kt`。要素は testTag (resource-id) で探すので、
  `TestTags` の名前を変えるとここも直す必要がある。
- 失敗したときは、CI のログに logcat の要点・crash バッファ・終了時点の画面の要素一覧が出る。

## Baseline Profile の記録と起動時間の計測 (#8、D-021)

CI (`baseline-profile.yml`。main への push・`baselineprofile/` を変えた PR・手動実行) が、エミュレータで次を行う。
配布版のリリース (`release.yml`) は、main の同じコミットの記録 (成果物) を使う。無ければリリースの中で記録する
(失敗したらリポジトリの内容のまま出す)。

1. 難読化しない配布版 (`assembleNonMinifiedRelease`) を入れ、`BaselineProfileGenerator` で
   ログイン → ホーム → チャンネルを開いてスクロール → 設定、を繰り返して使われたコードを記録する
2. 記録を `app/src/main/baseline-prof.txt` に置き、配布版を作り直す (R8 がクラス名を合わせて取り込む)
3. `StartupBenchmark` で、記録前・記録後の配布版の起動時間 (コールドスタート) を測り、ログに出す

```bash
./gradlew :app:assembleNonMinifiedRelease :baselineprofile:assembleDebug
.github/scripts/baseline-profile.sh generate app/build/outputs/apk/nonMinifiedRelease/app-nonMinifiedRelease.apk
./gradlew :app:assembleRelease
.github/scripts/baseline-profile.sh measure app/build/outputs/apk/release/app-release.apk after
```

## プッシュ通知の確認 (サーバー側)

```bash
cd ~/backend-run && bunx web-push generate-vapid-keys --json   # 鍵を作る
VAPID_PUBLIC_KEY=... VAPID_PRIVATE_KEY=... NODE_TLS_REJECT_UNAUTHORIZED=0 bun run src/index.ts
python3 devtools/push-receiver.py &   # localhost:8443 で受ける (自己署名証明書 c.pem/k.pem が必要)
python3 devtools/push-e2e.py          # 登録 → メンション → 受信 → 復号 (pip install http_ece cryptography)
```

## 構成

```
app/src/main/java/com/giracle/android/
├── GiracleApp.kt / MainActivity.kt   起動・前面/背面の切り替え
├── AppGraph.kt                       依存関係の組み立て・ログイン状態・起動時スナップショット
├── data/
│   ├── net/   Endpoint (URL 配置)・Http (OkHttp・トークン)・GiracleApi (全 API)・WsClient・LocalNetwork (Android 17 の権限)
│   ├── model/ サーバーの応答モデル
│   ├── ChatStore.kt   状態と WebSocket イベントの反映 (Web 版の stores + WS)
│   ├── MessageParser.kt  本文の記法 (メンション・URL など) の解析
│   ├── Emojis.kt  ショートコード ⇔ 絵文字
│   └── Prefs.kt   端末内設定・トークン
├── notify/   通知・常時接続サービス・UnifiedPush 受信
└── ui/       画面 (Compose)
```

判断の経緯は [DECISIONS.md](DECISIONS.md)、サーバーへの要望は [SERVER_CHANGES.md](SERVER_CHANGES.md)、
Web 版との対応は [FEATURES.md](FEATURES.md)。
