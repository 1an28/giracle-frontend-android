# 開発の手引き

## 必要なもの

- JDK 17 以上 (21 で確認)
- Android SDK (platforms;android-37.0, build-tools;37.0.0)。`local.properties` に `sdk.dir=...`
- ローカル検証用: [Bun](https://bun.sh) と Node.js (バックエンドと Web 版を動かす)

## ビルド

```bash
./gradlew :app:assembleDebug     # 開発版 (com.giracle.android.debug、「Giracle Native (dev)」)
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
devtools/start-local-stack.sh   # バックエンド :3000 と Web 版 :3333 (/api と /ws を転送) を起動
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
- `UiScreenshotTest`: `seed-demo.sh` のデータで実際の画面を描画し、`app/build/screenshots/` に PNG を出す。
- Robolectric はプロキシ環境で自前のダウンロードに失敗するため、Gradle で取得してオフラインで動かす設定にしてある。

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
│   ├── net/   Endpoint (URL 配置)・Http (OkHttp・トークン)・GiracleApi (全 API)・WsClient
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
