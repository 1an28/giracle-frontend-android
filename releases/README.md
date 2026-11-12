# releases/

GitHub のリリースに添付する APK の置き場と、署名証明書の指紋。

- `SIGNING_CERT_SHA256`: 配布する APK の署名証明書 (SHA-256)。リリース時に CI がこれと一致することを確かめる。
  一致しない APK (別の鍵で署名したもの) は、前の版に上書きインストールできないため公開しない。
- `giracle-native-vX.Y.Z.apk`: 署名済み APK。リポジトリの Secrets に署名鍵が無いときだけ使う。

## リリースの出し方

1. `app/build.gradle.kts` の `versionCode` を 1 増やし、`versionName` を新しい版にする
2. 署名済み APK を用意する (どちらか)
   - おすすめ: リポジトリの Settings > Secrets and variables > Actions に
     `KEYSTORE_BASE64` (`base64 -w0 giracle-native.p12` の出力) と `KEYSTORE_PASSWORD` を登録する。以後は CI がビルド・署名する
   - Secrets を使わない場合: 手元で `./gradlew :app:assembleRelease` し、`releases/giracle-native-vX.Y.Z.apk` として置いてコミット
3. `git tag vX.Y.Z && git push origin vX.Y.Z` (または Actions の release ワークフローを手動実行)
