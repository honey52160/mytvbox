#!/usr/bin/env bash
# 合并应用停止脚本：停止本地 shell 部署的 spider(9978) + web(8888)
# 用法：
#   bash stop-merged.sh            # 停止 spider + web
#   bash stop-merged.sh --tunnel   # 同时停止 cloudflared 穿透进程
#   bash stop-merged.sh --docker   # 改用 docker compose down 停止容器部署
#
# 回收策略：按命令行匹配(pkill) + 按端口释放(lsof)，先 SIGTERM 优雅退出，
#          端口仍占用再 SIGKILL 兜底；并清理 spider 残留 PID 文件，避免下次启动被守卫误判“已在运行”。
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"

STOP_TUNNEL=0
USE_DOCKER=0
for arg in "$@"; do
  case "$arg" in
    --tunnel) STOP_TUNNEL=1 ;;
    --docker) USE_DOCKER=1 ;;
    -h|--help) echo "用法: bash stop-merged.sh [--tunnel] [--docker]"; exit 0 ;;
    *) echo "未知参数: $arg（用 -h 查看帮助）"; exit 1 ;;
  esac
done

# 容器部署：直接交给 docker compose
if [ "$USE_DOCKER" -eq 1 ]; then
  cd "$ROOT"
  if command -v docker >/dev/null 2>&1 && docker compose ps 2>/dev/null | grep -q 'tvbox'; then
    echo "停止 docker 部署 (tvbox-web / tvbox-spider) ..."
    docker compose down
  else
    echo "未检测到运行中的 tvbox 容器，跳过。"
  fi
  exit 0
fi

# 按端口释放：先 SIGTERM，1s 后若仍占用则 SIGKILL
stop_port() {
  local port="$1"; local label="$2"
  if command -v lsof >/dev/null 2>&1; then
    local pids
    pids="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
    if [ -n "$pids" ]; then
      echo "停止 $label (端口 $port, pid: $pids) ..."
      kill $pids 2>/dev/null || true
      sleep 1
      pids="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
      if [ -n "$pids" ]; then
        echo "  ... 仍被占用，强制 SIGKILL"
        kill -9 $pids 2>/dev/null || true
        sleep 1
      fi
    else
      echo "$label (端口 $port) 未运行"
    fi
  else
    echo "未检测到 lsof，跳过按端口检测 ($label)"
  fi
}

# 按命令行匹配杀（macOS / Linux 通用）
kill_pattern() {
  local pattern="$1"; local label="$2"
  if command -v pkill >/dev/null 2>&1; then
    if pkill -f "$pattern" 2>/dev/null; then
      echo "已发送停止信号: $label ($pattern)"
    else
      echo "$label 未运行"
    fi
  fi
}

echo "== 停止合并应用 (spider + web) =="
# web：先按命令行匹配，再按端口兜底
kill_pattern 'server.js' 'tvbox-web'
stop_port 8888 'tvbox-web'
# spider：进程名匹配 + 端口兜底
kill_pattern 'com.github.catvod.host.HostMain' 'tvbox-spider-server'
stop_port 9978 'tvbox-spider-server'
# 清理 spider PID 文件，避免下次 start-merged.sh 被 start.sh 守卫误判“已在运行”
rm -f "$ROOT/tvbox-spider-server/run/host.pid" 2>/dev/null || true
echo "  已清理 spider PID 文件"

# 可选：停止穿透隧道（避免域名残留指向已停服务）
if [ "$STOP_TUNNEL" -eq 1 ]; then
  kill_pattern 'cloudflared' 'cloudflared 穿透'
  echo "  已尝试停止 cloudflared（未运行则忽略）"
fi

echo "== 完成 =="
echo "提示：若用 Docker 部署，请用  bash stop-merged.sh --docker"
