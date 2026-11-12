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
