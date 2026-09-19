#!/usr/bin/env bash
# tvbox-web 停止脚本：仅回收 web 服务（默认 8888），不影响 spider 宿主。
# 用法：
#   bash stop.sh          正常停止
#   bash stop.sh --force  跳过重试，直接 SIGKILL
# 注意：本脚本不使用 set -u，避免 nounset 在状态播报行误判变量未绑定而中断。
WEB_DIR="$(cd "$(dirname "$0")" && pwd)"
PORT="${TVBOX_WEB_PORT:-8888}"
FORCE=0
[ "${1:-}" = "--force" ] && FORCE=1

echo "== tvbox-web 停止脚本 =="
echo "目录: $WEB_DIR"
echo "端口: $PORT"

# ---------------------------------------------------------------------------
# 1) 按进程名回收（兜底）
# ---------------------------------------------------------------------------
pkill -f "node $WEB_DIR/server.js" 2>/dev/null || true
pkill -f "tvbox-web/server.js" 2>/dev/null || true

# ---------------------------------------------------------------------------
# 2) 按端口回收（首选，精确只停本服务）
# ---------------------------------------------------------------------------
kill_by_port() {
  local port="$1"
  local pids
  # macOS：lsof 取 LISTEN 状态 pid；Linux 回退到 ss
  pids=$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)
  if [ -z "$pids" ]; then
    pids=$(command -v ss >/dev/null 2>&1 && ss -ltnp 2>/dev/null | grep ":$port " | grep -oE 'pid=[0-9]+' | cut -d= -f2 || true)
  fi
  [ -z "$pids" ] && return 0
  echo "端口 $port 占用进程: $pids"
  # 优雅退出
  for pid in $pids; do
    kill -TERM "$pid" 2>/dev/null || true
  done
  # 等待退出（最多 5 秒）
  local i=0
  while [ "$i" -lt 5 ]; do
    sleep 1
    pids=$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)
    [ -z "$pids" ] && break
    i=$((i + 1))
  done
  # 仍未退出 → 强制
  pids=$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)
  if [ -n "$pids" ]; then
    echo "优雅退出超时，强制 SIGKILL: $pids"
    for pid in $pids; do
      kill -KILL "$pid" 2>/dev/null || true
    done
    sleep 1
  fi
}

if [ "$FORCE" = "1" ]; then
  pids=$(lsof -tiTCP:"$PORT" -sTCP:LISTEN 2>/dev/null || true)
  if [ -n "$pids" ]; then
    kill -KILL $pids 2>/dev/null || true
  fi
else
  kill_by_port "$PORT"
fi

# ---------------------------------------------------------------------------
# 3) 结果确认
# ---------------------------------------------------------------------------
sleep 1
left=$(lsof -tiTCP:"$PORT" -sTCP:LISTEN 2>/dev/null || true)
if [ -z "$left" ]; then
  echo "web 服务（$PORT）已停止"
else
  echo "端口 $PORT 仍有进程: $left（可重试 bash stop.sh --force）"
  exit 1
fi
