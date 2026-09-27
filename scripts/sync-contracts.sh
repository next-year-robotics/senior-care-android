#!/usr/bin/env bash
# mobile-robot의 계약 정본(software/contracts/phone-bridge)을 이 저장소로 복사한다.
# 사용: scripts/sync-contracts.sh [정본 contracts 디렉터리]
#   기본 탐색 순서: ../mobile-robot-care/software/contracts → ../mobile-robot/software/contracts
# 복사본(contracts/)은 직접 편집하지 않는다. CI가 정본과 diff한다.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SRC=${1:-}
if [ -z "$SRC" ]; then
  for c in "$ROOT/../mobile-robot-care/software/contracts" "$ROOT/../mobile-robot/software/contracts"; do
    if [ -d "$c/phone-bridge" ]; then SRC=$(cd "$c" && pwd); break; fi
  done
fi
[ -n "$SRC" ] && [ -d "$SRC/phone-bridge" ] || { echo "정본 contracts 디렉터리를 찾지 못했다: $SRC" >&2; exit 1; }
rm -rf "$ROOT/contracts/phone-bridge"
mkdir -p "$ROOT/contracts"
cp -R "$SRC/phone-bridge" "$ROOT/contracts/phone-bridge"
{
  echo "repo=next-year-robotics/mobile-robot"
  echo "path=software/contracts/phone-bridge"
  echo "commit=$(git -C "$SRC" rev-parse HEAD 2>/dev/null || echo unknown)"
  echo "dirty=$( [ -n "$(git -C "$SRC" status --short -- . 2>/dev/null)" ] && echo yes || echo no )"
  echo "synced_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$ROOT/contracts/SOURCE"
echo "synced from $SRC"; cat "$ROOT/contracts/SOURCE"
