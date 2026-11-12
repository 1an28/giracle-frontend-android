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

## S-2 Android 向けプッシュ通知 (機能追加の要望)

(詳細は後述の節で追記)
