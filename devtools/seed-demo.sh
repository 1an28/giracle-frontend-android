#!/usr/bin/env bash
# 開発用: 画面確認のためのデモデータを入れる (管理者 admin / password123 が居る前提)。
set -euo pipefail
B=${B:-http://localhost:3333/api}
J='content-type: application/json'
tok(){ curl -s -i -X POST $B/user/sign-in -H "$J" -d "{\"username\":\"$1\",\"password\":\"$2\"}" | grep -o 'token=[0-9a-f]*'; }
id(){ python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["'$1'"])'; }
A="Cookie: $(tok admin password123)"
curl -s -X PUT $B/server/create-invite -H "$A" -H "$J" -d '{"inviteCode":"demo"}' >/dev/null || true
for u in hanako taro; do curl -s -X PUT $B/user/sign-up -H "$J" -d "{\"username\":\"$u\",\"password\":\"password123\",\"inviteCode\":\"demo\"}" >/dev/null; done
H="Cookie: $(tok hanako password123)"; T="Cookie: $(tok taro password123)"
HID=$(curl -s $B/user/verify-token -H "$H" | id userId); TID=$(curl -s $B/user/verify-token -H "$T" | id userId)
curl -s -X POST $B/user/profile-update -H "$H" -H "$J" -d '{"name":"花子","selfIntroduction":"デザイン担当です"}' >/dev/null
curl -s -X POST $B/user/profile-update -H "$T" -H "$J" -d '{"name":"太郎","selfIntroduction":"サーバー担当"}' >/dev/null
GEN=$(curl -s -X PUT $B/channel/create -H "$A" -H "$J" -d '{"channelName":"雑談","description":"なんでも話そう"}' | id channelId)
DEV=$(curl -s -X PUT $B/channel/create -H "$A" -H "$J" -d '{"channelName":"開発","description":"Android 版の開発"}' | id channelId)
for c in "$A" "$H" "$T"; do for ch in $GEN $DEV; do curl -s -X POST $B/channel/join -H "$c" -H "$J" -d "{\"channelId\":\"$ch\"}" >/dev/null; done; done
send(){ curl -s -X POST $B/message/send -H "$1" -H "$J" -d "$2" | id id; }
M1=$(send "$H" "{\"channelId\":\"$GEN\",\"message\":\"おはようございます！今日もよろしくお願いします\"}")
send "$T" "{\"channelId\":\"$GEN\",\"message\":\"おはよう〜 \`./gradlew assembleRelease\` が通りました\"}" >/dev/null
M3=$(send "$T" "{\"channelId\":\"$GEN\",\"message\":\"資料はこちら https://example.com\"}")
send "$H" "{\"channelId\":\"$GEN\",\"message\":\"ありがとう！ @<$TID> さん\",\"replyingMessageId\":\"$M3\"}" >/dev/null
for e in thumbsup heart joy; do curl -s -X POST $B/message/emoji-reaction -H "$A" -H "$J" -d "{\"messageId\":\"$M1\",\"channelId\":\"$GEN\",\"emojiCode\":\"$e\"}" >/dev/null; done
curl -s -X POST $B/message/emoji-reaction -H "$T" -H "$J" -d "{\"messageId\":\"$M1\",\"channelId\":\"$GEN\",\"emojiCode\":\"thumbsup\"}" >/dev/null
python3 -c "
import zlib,struct
def ch(t,d): return struct.pack('>I',len(d))+t+d+struct.pack('>I',zlib.crc32(t+d)&0xffffffff)
w,h=160,90
raw=b''.join(b'\x00'+b''.join(bytes((int(255*x/w),int(255*y/h),200)) for x in range(w)) for y in range(h))
open('/tmp/grad.png','wb').write(b'\x89PNG\r\n\x1a\n'+ch(b'IHDR',struct.pack('>IIBBBBB',w,h,8,2,0,0,0))+ch(b'IDAT',zlib.compress(raw))+ch(b'IEND',b''))"
F=$(curl -s -X POST $B/message/file/upload -H "$H" -F channelId=$GEN -F "file=@/tmp/grad.png;type=image/png" | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["fileId"]["id"])')
send "$H" "{\"channelId\":\"$GEN\",\"message\":\"新しいアイコン案です\",\"fileIds\":[\"$F\"]}" >/dev/null
send "$T" "{\"channelId\":\"$GEN\",\"message\":\"いいですね！ #<$DEV> で続きを話しましょう &<$GEN:$M1>\"}" >/dev/null
LONG=$(python3 -c 'import json;print(json.dumps("\n".join("長文テスト %d 行目" % i for i in range(1,12))))')
send "$H" "{\"channelId\":\"$DEV\",\"message\":$LONG}" >/dev/null
send "$T" "{\"channelId\":\"$GEN\",\"message\":\"@<$(curl -s $B/user/verify-token -H "$A" | id userId)> 管理者さん確認お願いします\"}" >/dev/null
echo "seeded: general=$GEN dev=$DEV"
