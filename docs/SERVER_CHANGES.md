# サーバー (giracle-backend) への要望と報告

Android ネイティブアプリ (Giracle Native) を作るなかで分かった、サーバー側への要望と不具合の報告。
**giracle-backend の作者が読む前提** で書いている。

- 参照した backend のコミット: `87be860` (2026-11-08)
- アプリの作りの前提: HTTP は `/api/...`、WebSocket は `/ws` に接続する (Web 版と同じ配置)。
  認証はログイン応答の Cookie `token` を取り出して保存し、すべての通信に付けている。

## まとめ

| ID | 種類 | 内容 | 急ぎ度 | 関連 Issue |
| --- | --- | --- | --- | --- |
| S-1 | 不具合 | 通知が残っているチャンネルを削除すると 500 になる | 直してほしい | - |
| S-3 | 相談 | アプリからの WebSocket 接続の正式な入口 | 急がない (今は暫定の方法で動いている) | [#7](https://github.com/1an28/giracle-frontend-kotlin/issues/7) |
| S-5 | 要望 | ログインのトークンを Cookie 以外でも受け渡せるようにする | 急がない | [#7](https://github.com/1an28/giracle-frontend-kotlin/issues/7) |
| S-2 | 確認 | プッシュ通知: 本番から配信サーバーへ出られるか / FCM に対応するか | 確認だけ先にほしい | [#6](https://github.com/1an28/giracle-frontend-kotlin/issues/6) |
| S-6 | 要望 | サーバーのバージョンを返す窓口 | あると助かる | - |
| S-4 | 情報 | そのほか気づいたこと | 対応不要 | - |

### 話すときのチェックリスト

- [ ] S-1 を直してもらえるか
- [ ] S-2: 本番サーバーから `https://ntfy.sh` へ HTTPS で出られるか
- [ ] S-2: FCM に対応する気があるか (無ければ UnifiedPush と常時接続だけで続ける)
- [ ] S-3 / S-5: アプリ向けの認証・接続方法をどうするか (案は下に)

---

## S-1 通知が残っているチャンネルを削除すると 500 になる (不具合)

- **現象**: 未読のメンション/返信通知 (Inbox) が残っているメッセージを含むチャンネルを
  `DELETE /channel/delete` すると、`500 somethin went wrong :(` になり削除できない。
  サーバーログ: `SQLiteError: FOREIGN KEY constraint failed` (`QueryMessage.removeByChannelInTx`)。
- **再現手順**: ユーザー A がユーザー B をメンションする → B が通知を既読にする前に、管理者がそのチャンネルを削除する。
  (このアプリの結合テスト `LiveServerTest` で見つけた)
- **原因**: `src/db/schema.ts` の `Inbox.messageId` が `onDelete: "restrict"`。
  `ServiceChannel.Delete` (`channel.service.ts` の 470 行目付近) のトランザクションは
  ReadTime / ViewableRole / FileAttached / Message / ChannelJoin … を消すが、Inbox を消していない。
- **直し方の案** (どちらか):
  - `Message` を消す前に、そのチャンネルのメッセージに紐づく Inbox 行を消す (`QueryInbox.removeByChannelInTx` を足す)。
    単体のメッセージ削除 (`/message/delete`) は Inbox を先に消しているので、それとそろう。
  - `Inbox.messageId` を `onDelete: "cascade"` にする。

---

## S-3 アプリからの WebSocket 接続の正式な入口 (相談)

### 今の状況

`src/ws.ts` の `/ws` は次の条件で接続を受ける。

- Origin ヘッダが無いと 403。`CORS_ORIGIN` を設定している場合は、一覧と完全一致しないと 403
- 認証は Cookie の `token` だけを見る

ネイティブアプリには本来 Origin が無い。そのためアプリは、利用者が入力したサーバー URL から Origin を作って名乗っている
(例: `https://chat.example.com`)。Web 版と同じホスト名で公開していれば `CORS_ORIGIN` に含まれるので、今はこれで動く。

### 困っていること

- ブラウザ向けの Origin 確認を、アプリが Web 版のふりをして通っている形になっている
- Web 版の Origin とサーバーの公開ホスト名が違う運用だと、アプリから接続できない
  (アプリは「リアルタイム接続が拒否されました」と表示する)

### 入口の案

| 案 | サーバーの変更 | アプリの変更 |
| --- | --- | --- |
| A. Origin が無い接続は Cookie (または S-5 のヘッダ) の認証だけで通す | `beforeHandle` の条件を変える | Origin を送るのをやめる |
| B. `?token=` クエリでの認証を実装する (README には既に書いてある) | `open` で query も見る | URL にトークンを付ける |
| C. `CORS_ORIGIN` とは別に、アプリ用に許可する Origin の設定を足す | 環境変数を 1 つ足す | アプリ専用の Origin を名乗る |

- アプリ側はどの案でも数行の変更で合わせられる。
- B はトークンが URL に載るので、リバースプロキシのアクセスログに残りやすい点に注意。
- A を選ぶ理由: Origin 確認の目的は、ブラウザ上の他サイトからの接続 (CSWSH) を防ぐこと。
  ブラウザは Origin を必ず付けるので、Origin が無い接続はブラウザ以外からのものと判断できる。
  この判断は推測で、作者の意図とは確かめていない。

### 情報: README と実装の食い違い

README には「`?token` クエリでも認証できる」とあるが、実装は Cookie しか見ていない。

---

## S-5 ログインのトークンを Cookie 以外でも受け渡せるようにする (要望)

### 今の状況

- `POST /user/sign-in` はトークンを `Set-Cookie` でだけ返す。応答の本文には `userId` しか無い
- 通常の API の認証 (`Middleware.CheckToken`) は Cookie の `token` だけを見る。
  `Authorization` ヘッダは Bot 用 (`CheckBotToken`) だけで使われている
- Cookie の期限は 15 日で、使うたびに `Set-Cookie` で延長される

アプリは `Set-Cookie` から値を取り出して保存し、自分で `Cookie: token=...` を付けている。今はこれで問題なく動く。

### 要望

Web 以外のクライアントでよく使われる形にも対応してほしい。

- `sign-in` の応答の本文にもトークン (と期限) を入れる
- `Authorization: Bearer <token>` でも、Cookie と同じように認証する (HTTP と `/ws` の両方)

S-3 の案 A と組み合わせると、アプリは Cookie も Origin も使わずに済む。
Cookie での認証はそのまま残せば、Web 版には影響しない。

---

## S-2 プッシュ通知 (確認と相談)

### 結論: UnifiedPush 方式ならサーバーのコード変更は不要

アプリは **UnifiedPush** に対応している。UnifiedPush の宛先は Web Push と同じ形式なので、
サーバーの既存の Web Push 送信 (`Utils/SendPushNotification.ts`) がそのまま Android に届く。

**仕組み**: アプリは UnifiedPush から受け取った宛先 URL と鍵 (P-256 公開鍵・auth) を
`POST /notification/device/register` に `platform: "web"` で登録する。サーバーは Web ブラウザと同じ方法
(RFC 8030 + RFC 8291 aes128gcm + VAPID) で送り、配信アプリ (ntfy など) 経由で届いた暗号文をアプリが復号して通知を出す。
ローカル環境で「登録 → メンション → 暗号化された通知の受信 → 復号」まで確認した (`devtools/push-e2e.py`)。実機では未確認。

サーバー側に要るもの:

1. VAPID 鍵の設定 (`VAPID_PUBLIC_KEY` / `VAPID_PRIVATE_KEY`)。
   **本番 (chat.girak.moe) は設定済み**。`GET /api/notification/vapid-public-key` が鍵を返すことを確認した (2026-11-12)。
2. サーバーから配信サーバー (ntfy を使うなら `https://ntfy.sh` など) へ HTTPS で出られること。**本番では未確認**。

### 相談: FCM (Google のプッシュ) に対応するか

UnifiedPush は利用者が配信アプリ (ntfy など) を入れる必要がある。配信アプリ無しで受けたい場合は FCM が要る。
対応しない場合、アプリは UnifiedPush と常時接続 (アプリが WebSocket を保ち続ける) の 2 つで続ける。

対応する場合のサーバー側の変更:

1. Firebase プロジェクトを作り、サービスアカウントの鍵をサーバーに置く (環境変数でパスを渡す)
2. `SendPushNotification.ts` の「未対応プラットフォーム (android/ios) は将来対応」の箇所に、
   `platform === "android"` の端末へ FCM HTTP v1 API (`https://fcm.googleapis.com/v1/projects/<id>/messages:send`)
   で送る処理を足す
   - `device.token` に FCM の登録トークンが入る
   - `payload` の `title`/`body` を notification に、`data` (channelId, messageId) を data に入れる
   - 無効なトークン (`UNREGISTERED`) は削除する
3. アプリ側は Firebase Messaging SDK と `google-services.json` を入れ、`platform: "android"` で登録する
   (アプリ側は未実装)

---

## S-6 サーバーのバージョンを返す窓口 (要望・あると助かる)

今は、サーバーのバージョンや、どの機能に対応しているかをアプリから知る方法が無い。
S-3 や S-5 のように入口が増えたとき、アプリは「このサーバーは新しい方法に対応しているか」を見て使い分けたい。

- 例: `GET /server/config` の応答に `version` (や対応している機能の一覧) を足す
- 無い場合、アプリは新しい方法を試して、失敗したら古い方法に戻る形で対応する (できるが、少し手間がかかる)

---

## S-4 そのほか気づいたこと (情報・対応不要)

- `GET /server/banner` は、バナーが未設定だと 404 を返し、サーバーログにエラーとして出る (動作上の問題はない)。
- ユーザー名がログイン名を兼ねるため、プロフィールで名前を変えるとログインに使う名前も変わる。
  利用者が戸惑うかもしれないので、注意書きを出すか、ログイン ID と表示名を分けることを考えてもよい。
