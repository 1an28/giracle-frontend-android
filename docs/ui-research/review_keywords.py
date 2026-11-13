"""他社チャットアプリのストアレビュー (低評価) の傾向を見るための、粗いキーワード分類と標本の抽出 (D-045)。

使うデータ (どちらも GitHub 上の公開リポジトリ。このリポジトリには入れない):
  1. Telegram: https://github.com/Mad03633/Telegram-App-Reviews-Sentiment-Analysis の assets/Telegram.csv
     (Google Play、10,000 件、2021-09-21〜2022-03-17、版 8.1〜8.6)
  2. WhatsApp: https://github.com/RalphSaridar369/whatsapp-reviews-insights の app/src/data/reviews.json
     (3,927 件。取得時期は書かれていない)
使い方 (データは自分の作業場所に取ってくる):
  python3 -I review_keywords.py Telegram.csv reviews.json sample.json
sample.json に低評価から無作為に Telegram 100 件・WhatsApp 60 件を取り出す (seed=1)。
その 160 件を Claude が 1 件ずつ読んで付けた分類が review_labels.json (番号は sample.json の並び)。
キーワード分類は英語の決まった言い回しにしか当たらず、取りこぼしが多い (精度は確かめていない)。
"""
import csv,sys,json,re,random,collections
tg=[(r['content'],int(r['score'])) for r in csv.DictReader(open(sys.argv[1],encoding='utf-8'))]
wa=[(x['review'],x['score']) for t in json.load(open(sys.argv[2])) for x in t['reviews']]
CATS={
 'slow/lag':r'\b(slow|lag|laggy|lagging|freez|stuck|hang|hangs|takes? (so )?(long|forever)|loading|load(s)? slow|sluggish|delay(ed)?)\b',
 'crash/bug':r'\b(crash|crashes|crashing|bug|bugs|glitch|not working|doesn\'?t work|stopped working|close[sd]? (itself|automatically))\b',
 'notification':r'\b(notification|notifications|notify|notif)\b',
 'send/receive/connect':r'\b(connecting|connection|can\'?t send|cannot send|not sending|not (be )?delivered|messages? (are )?not|updating\.\.\.|waiting for network|offline)\b',
 'ui-change/layout':r'\b(new (ui|design|layout|interface|look)|old (ui|design|layout|version)|redesign|layout|interface|ui\b|bring back|revert|moved|where is|can\'?t find|hidden|hard to find|too many (clicks|taps|steps)|bottom (bar|nav))',
 'account/login/verify':r'\b(verif|verification|code|login|log in|sign in|banned|ban|account (deleted|blocked)|otp|sms)\b',
 'media/file':r'\b(video|photo|picture|image|media|file|download|upload|sticker|gif|voice (message|note)|camera)\b',
 'storage/battery/data':r'\b(storage|space|memory|battery|data usage|cache|heavy|size)\b',
 'privacy/spam/ads':r'\b(privacy|spam|scam|ads|advert|premium|subscription|pay|paid|porn)\b',
 'call':r'\b(call|calls|calling)\b',
}
cre={k:re.compile(v,re.I) for k,v in CATS.items()}
def run(name,data):
  neg=[t for t,s in data if s<=2]
  c=collections.Counter(); upd=0; none=0
  for t in neg:
    hit=[k for k,r in cre.items() if r.search(t)]
    for k in hit:c[k]+=1
    if not hit: none+=1
    if re.search(r'\b(update|updated|new version|latest version)\b',t,re.I): upd+=1
  print(f'## {name}: 全{len(data)} 低評価(1-2){len(neg)}')
  for k,v in c.most_common(): print(f'  {k}: {v} ({v*100/len(neg):.1f}%)')
  print(f'  (どれにも当たらない): {none} ({none*100/len(neg):.1f}%)  「update」に触れる: {upd} ({upd*100/len(neg):.1f}%)')
  # UI変更に当たったものの中で 遅い/操作が増えた を同時に言うもの
  ui=[t for t in neg if cre['ui-change/layout'].search(t)]
  both=sum(1 for t in ui if cre['slow/lag'].search(t))
  print(f'  ui-change のうち slow にも当たる: {both}/{len(ui)}')
  return neg
random.seed(1)
a=run('Telegram',tg); b=run('WhatsApp',wa)
if len(sys.argv)>3:
  s=random.sample(a,100)+random.sample(b,60)
  json.dump(s,open(sys.argv[3],'w'),ensure_ascii=False,indent=0)
