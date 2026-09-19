#!/usr/bin/env bash
#
# start.sh —— 后台启动宿主服务
#   ./start.sh              使用 config/spiders.json
#   ./start.sh -c <file>    指定配置文件
#   ./start.sh -port 9978   覆盖端口
# 日志: run/host.log   PID: run/host.pid
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN="$ROOT/run"
mkdir -p "$RUN"
PID_FILE="$RUN/host.pid"
LOG_FILE="$RUN/host.log"
JAVA_BIN="${JAVA_BIN:-/usr/bin/java}"

if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "服务已在运行 (pid=$(cat "$PID_FILE"))"
  exit 0
fi

if [ ! -d "$ROOT/build/classes" ]; then
  echo "未找到编译产物，请先执行：./build.sh" >&2
  exit 1
fi

CP="$ROOT/build/classes:$ROOT/libs/*"

nohup "$JAVA_BIN" --add-modules jdk.httpserver \
  -Dfile.encoding=UTF-8 \
  -Dtvbox.home="$ROOT" \
  -cp "$CP" \
  com.github.catvod.host.HostMain "$@" > "$LOG_FILE" 2>&1 &

echo $! > "$PID_FILE"
sleep 1.5

if kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "已启动 pid=$(cat "$PID_FILE")"
  echo "日志: $LOG_FILE"
  tail -n 12 "$LOG_FILE" || true
else
  echo "启动失败，日志如下：" >&2
  tail -n 40 "$LOG_FILE" >&2 || true
  exit 1
fi
