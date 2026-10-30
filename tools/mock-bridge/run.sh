#!/usr/bin/env bash
# mock bridge는 mobile-robot 저장소(mr_phone_bridge)에 있다. 이 스크립트는 그것을 찾아 띄운다.
# 사용: tools/mock-bridge/run.sh [--scenario default6] [--port 8765]
set -euo pipefail
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
MEASURE=false
ARGS=()
for arg in "$@"; do
  if [ "$arg" = "--measure-latency" ]; then MEASURE=true; else ARGS+=("$arg"); fi
done
for c in "$HOME/dev/mobile-robot" "$HOME/dev/mobile-robot-care"; do
  if [ -d "$c/software/ros_pkgs/mr_phone_bridge" ]; then MR=$c; break; fi
done
[ -n "${MR:-}" ] || { echo "mobile-robot 저장소를 찾지 못했다 (~/dev/mobile-robot 또는 ~/dev/mobile-robot-care)" >&2; exit 1; }
cd "$MR/software/ros_pkgs/mr_phone_bridge"
export PYTHONDONTWRITEBYTECODE=1
if "$MEASURE"; then
  export PYTHONPATH="$PWD${PYTHONPATH:+:$PYTHONPATH}"
  exec uv run --no-project --with websockets --with jsonschema --with pyyaml python3 "$SCRIPT_DIR/measure.py" "${ARGS[@]}"
fi
exec uv run --no-project --with websockets --with jsonschema --with pyyaml python3 -m mr_phone_bridge.mock "${ARGS[@]}"
