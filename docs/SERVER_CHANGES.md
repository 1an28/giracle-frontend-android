# サーバー (giracle-backend) 側に必要な変更・見つかった問題

このアプリの開発中に、サーバー側の変更が必要だと分かったことをまとめる。
参照した backend のコミット: `87be860` (2026-11-08)。

---

## S-1 チャンネル削除が 500 エラーになる (バグ)

- **現象**: 未読のメンション/返信通知 (Inbox) が残っているメッセージを含むチャンネルを
  `DELETE /channel/delete` すると、`500 somethin went wrong :(` になり削除できない。
  サーバーログ: `SQLiteError: FOREIGN KEY constraint failed` (`QueryMessage.removeByChannelInTx`)。
- **原因**: `src/db/schema.ts` の `Inbox.messageId` が `onDelete: "restrict"`。
  `ServiceChannel.Delete` のトランザクションは ReadTime / ViewableRole / FileAttached / Message …
  を消すが、Inbox を消していないため、メッセージ削除で外部キー制約に引っかかる。
- **再現**: ユーザー A がユーザー B をメンション → B が通知を既読にする前に、管理者がそのチャンネルを削除。
  (本リポジトリの `LiveServerTest` で検出)
- **直し方の案**: `ServiceChannel.Delete` で `Message` を消す前に、そのチャンネルのメッセージに紐づく
  Inbox 行を消す (`QueryInbox.removeByChannelInTx` を追加)。または `Inbox.messageId` を `onDelete: "cascade"` にする。
  単体のメッセージ削除 (`/message/delete`) は Inbox を先に消しているので、同じ扱いにそろえるのが自然。

---

## S-2 プッシュ通知 (アプリを閉じているときの通知)

### 結論: コード変更は不要。VAPID 鍵の設定だけ必要

このアプリは **UnifiedPush** に対応した。サーバーの既存の Web Push 送信 (`Utils/SendPushNotification.ts`) が
そのまま Android に届く。サーバー管理者にお願いすることは次の 1 点だけ。

1. `bunx web-push generate-vapid-keys` で鍵を作り、`.env` の `VAPID_PUBLIC_KEY` / `VAPID_PRIVATE_KEY` に設定して再起動する。
   (README の「プッシュ通知 (Web Push) セットアップ」と同じ。Web 版のプッシュ通知もこれで有効になる)
2. サーバーから UnifiedPush の配信サーバー (ntfy を使うなら `https://ntfy.sh` など) へ HTTPS で出られること。

利用者側は、端末に UnifiedPush の配信アプリ (例: ntfy) を入れ、アプリの「設定 > 通知 > プッシュ通知」で有効にする。

**本番サーバー (chat.girak.moe) の状況**: VAPID 鍵は設定済み。公開鍵を返す窓口
(`GET https://chat.girak.moe/api/notification/vapid-public-key`) が鍵を返すことを、リポジトリの持ち主が確認した
(2026-11-12)。そのため上の 1. は本番では対応済み。2. (配信サーバーへ出られるか) は未確認。

**仕組み**: アプリは UnifiedPush から受け取った宛先 URL と鍵 (P-256 公開鍵・auth) を
`POST /notification/device/register` に `platform: "web"` で登録する。サーバーは Web ブラウザと同じ方法
(RFC 8030 + RFC 8291 aes128gcm + VAPID) で送り、配信アプリ経由で届いた暗号文をアプリが復号して通知を出す。
ローカル環境で「登録 → メンション → 暗号化された通知の受信 → 復号」まで確認済み (`devtools/push-e2e.py`)。

### 任意: FCM (Google のプッシュ) にも対応したい場合に必要な変更

配信アプリを入れずに通知を受けたい場合は FCM が必要になる。その場合のサーバー側の変更:

1. Firebase プロジェクトを作り、サービスアカウントの鍵をサーバーに置く (環境変数でパスを渡す)。
2. `SendPushNotification.ts` の「未対応プラットフォーム (android/ios) は将来対応」の箇所に、
   `platform === "android"` の端末へ FCM HTTP v1 API (`https://fcm.googleapis.com/v1/projects/<id>/messages:send`)
   で送る処理を追加する。`device.token` に FCM 登録トークンが入る。`payload` の `title`/`body` を notification に、
   `data` (channelId, messageId) を data に入れる。無効トークン (`UNREGISTERED`) は削除する。
3. アプリ側は Firebase Messaging SDK と `google-services.json` を入れ、`platform: "android"` で登録する
   (このリポジトリでは未実装。Firebase のプロジェクトはリポジトリの持ち主が作る必要があるため)。

---

## S-3 WebSocket の Origin 確認について (情報)

- `/ws` は Origin ヘッダが無いと 403 を返し、`CORS_ORIGIN` 設定時は一覧と完全一致しないと 403 を返す。
  ネイティブアプリには本来 Origin が無いので、このアプリはサーバー URL から作った Origin を送っている。
- **Web 版と同じホスト名でサーバーを公開していれば変更は不要**。アプリで「リアルタイム接続が拒否されました」と
  出る場合は、アプリに入れた URL のスキーム・ホスト・ポートが `CORS_ORIGIN` に含まれているか確認する。
- README には「`?token` クエリでも認証できる」とあるが、実装 (`src/ws.ts`) は Cookie しか見ていない。
  アプリは Cookie で認証しているので影響はない (README と実装の食い違いとして報告)。

---

## S-4 そのほか気づいたこと (情報)

- `GET /server/banner` はバナー未設定時に 404 を返し、サーバーログにエラーとして出る (動作上の問題はない)。
- ユーザー名がログイン名を兼ねるため、プロフィールで名前を変えるとログインに使う名前も変わる。
  利用者が戸惑う可能性があるので、Web 版・アプリ側で注意書きを出すか、ログイン ID と表示名を分けることを検討してもよい。
