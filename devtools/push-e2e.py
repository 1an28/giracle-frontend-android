# 開発用: サーバーの Web Push が「web として登録した端末」(UnifiedPush と同じ形式) に届き、
# 復号できることを確かめる。ローカルの HTTPS 受信口 (localhost:8443) と、VAPID 鍵を設定した
# バックエンド (NODE_TLS_REJECT_UNAUTHORIZED=0) が必要。手順は docs/DEVELOPMENT.md を参照。
import base64, json, os, subprocess, time, urllib.request, http_ece
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import serialization
B='http://localhost:3333/api'
def b64(b): return base64.urlsafe_b64encode(b).rstrip(b'=').decode()
def req(method, path, body=None, tok=None):
    r=urllib.request.Request(B+path, method=method, data=json.dumps(body).encode() if body is not None else None, headers={'content-type':'application/json', **({'Cookie':'token='+tok} if tok else {})})
    with urllib.request.urlopen(r) as resp:
        sc=resp.headers.get('set-cookie') or ''; return json.loads(resp.read() or b'{}'), sc
def login(u,p):
    _,sc=req('POST','/user/sign-in',{'username':u,'password':p}); return sc.split('token=')[1].split(';')[0]
admin=login('admin','password123'); taro=login('太郎','password123')
# 端末側の鍵 (UnifiedPush connector が生成するものと同じ種類: P-256 + 16 バイト auth)
priv=ec.generate_private_key(ec.SECP256R1()); pub=priv.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
auth=os.urandom(16)
endpoint='https://localhost:8443/up/'+b64(os.urandom(8))
print(req('POST','/notification/device/register',{'token':endpoint,'platform':'web','keys':{'p256dh':b64(pub),'auth':b64(auth)},'deviceName':'Android test (Giracle Native)'},admin)[0])
me=req('GET','/user/verify-token',tok=admin)[0]['data']['userId']
ch=[c for c in req('GET','/channel/list',tok=taro)[0]['data'] if c['name']=='雑談'][0]['id']
req('POST','/message/send',{'channelId':ch,'message':'@<%s> プッシュのテスト'%me},taro)
for _ in range(50):
    if os.path.exists('/tmp/pushtest/got.bin'): break
    time.sleep(0.2)
body=open('/tmp/pushtest/got.bin','rb').read()
plain=http_ece.decrypt(body, private_key=priv, auth_secret=auth, version='aes128gcm')
print('decrypted:', plain.decode())
print('headers:', open('/tmp/pushtest/got.hdr').read()[:300])
req('POST','/notification/device/unregister',{'token':endpoint},admin)
