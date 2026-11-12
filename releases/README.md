# releases/

GitHub のリリースに添付する APK の置き場と、署名証明書の指紋。

- `SIGNING_CERT_SHA256`: 配布する APK の署名証明書 (SHA-256)。リリース時に CI がこれと一致することを確かめる。
  一致しない APK (別の鍵で署名したもの) は、前の版に上書きインストールできないため公開しない。
- 署名鍵はリポジトリの Secrets (`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD`) に登録済みで、リリース時に CI が
  ビルド・署名する。Secrets を使えないときだけ、署名済み APK を `giracle-native-vX.Y.Z.apk` として置けば代わりに使われる (`*.apk` は git の対象外なので `git add -f` で追加する)。

## リリースの出し方

1. `app/build.gradle.kts` の `versionCode` を 1 増やし、`versionName` を新しい版にする
2. `git tag vX.Y.Z && git push origin vX.Y.Z`
   - または GitHub の Actions > release > Run workflow で、main を選び `vX.Y.Z` を入れて実行
     (main の最新コミットにタグが付く)
