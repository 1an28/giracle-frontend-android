#!/usr/bin/env bash
# 開発用: バックエンドと Web 版 (リバースプロキシ代わり) をローカルで起動する。
#   バックエンド  : http://localhost:3000
#   Web 版 (vite) : http://localhost:3333  (/api と /ws をバックエンドへ転送 = 本番と同じ URL 配置)
# アプリからは「サーバーの URL」に http://<PCのIP>:3333 を入れて接続する。
# 参照リポジトリは変更しないよう、作業用コピー (BACKEND_DIR / WEB_DIR) で動かす。
set -euo pipefail
REF=${REF:-$HOME/ref}
BACKEND_DIR=${BACKEND_DIR:-$HOME/backend-run}
WEB_DIR=${WEB_DIR:-$HOME/web-run}
ORIGIN=${ORIGIN:-http://localhost:3333}

if [ ! -d "$BACKEND_DIR" ]; then
  git clone --depth 1 https://github.com/NfoAlex/giracle-backend.git "$BACKEND_DIR"
  (cd "$BACKEND_DIR" && cp .env.sample .env \
    && sed -i "s#^CORS_ORIGIN=.*#CORS_ORIGIN=$ORIGIN#; s/^RATE_LIMIT_ENABLED=true/RATE_LIMIT_ENABLED=false/" .env \
    && bun i && bun run db:migrate && bun run db:seed)
fi
if [ ! -d "$WEB_DIR" ]; then
  git clone --depth 1 https://github.com/NfoAlex/giracle-frontend-solid.git "$WEB_DIR"
  (cd "$WEB_DIR" && npx -y pnpm@10 install)
fi
(cd "$BACKEND_DIR" && nohup bun run src/index.ts > "$HOME/backend.log" 2>&1 &)
(cd "$WEB_DIR" && nohup npx vite --port 3333 --strictPort --host > "$HOME/web.log" 2>&1 &)
sleep 6
curl -sf localhost:3333/api/server/config > /dev/null && echo "stack up: $ORIGIN"
