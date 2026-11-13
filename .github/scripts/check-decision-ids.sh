#!/usr/bin/env bash
# docs/DECISIONS.md の判断の番号 (## D-xxx) が重なっていないか確かめる (D-052)。
# 並行する PR が同じ番号を使い、main を取り込んだときに重なるのを見つける。
set -euo pipefail
file="${1:-docs/DECISIONS.md}"
dups=$(grep -oE '^## D-[0-9]+' "$file" | sort | uniq -d || true)
if [ -n "$dups" ]; then
  echo "判断の番号が重なっています。後から足した方を、main と開いている PR の最大の次の番号に付け直してください:"
  echo "$dups"
  exit 1
fi
max=$(grep -oE '^## D-[0-9]+' "$file" | grep -oE '[0-9]+' | sort -n | tail -1)
echo "重なりなし (最大 D-$max)"
