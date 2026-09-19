#!/usr/bin/env bash
#
# stop.sh —— 停止宿主服务（读取 run/host.pid）
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PID_FILE="$ROOT/run/host.pid"

if [ ! -f "$PID_FILE" ]; then
  echo "未发现 run/host.pid，服务未在运行"
  exit 0
fi

PID="$(cat "$PID_FILE")"
if kill -0 "$PID" 2>/dev/null; then
  kill "$PID" 2>/dev/null || true
  for _ in 1 2 3 4 5; do
    kill -0 "$PID" 2>/dev/null || break
    sleep 0.5
  done
  if kill -0 "$PID" 2>/dev/null; then
    kill -9 "$PID" 2>/dev/null || true
  fi
  echo "已停止 pid=$PID"
else
  echo "进程 $PID 不存在"
fi
# 不使用 rm：把 pid 文件归档，便于排查
mv -f "$PID_FILE" "$PID_FILE.stopped" 2>/dev/null || true
