#!/usr/bin/env bash
# 合并应用一键启动：spider(9978) + web(8888, 反代 spider)
# 用法：
#   bash start-merged.sh
# 可选环境变量：
#   PORT       对外端口（默认 8888）
#   SITE_TOKEN 访问令牌（默认自动生成随机串，并打印到终端）
#   SPIDER_UPSTREAM 采集宿主地址（默认 http://127.0.0.1:9978）
#
# 兼容性：脚本会先自动停止之前运行的同名进程（端口占用 / 残留 PID / 孤儿进程），
#         再启动新实例，避免重复进程与“地址已被占用”的问题。
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"

# 自动停止之前运行的同名进程：按命令行匹配 + 按端口释放
stop_old() {
  local port="$1"; local pattern="$2"
  # 1) 按进程命令行匹配杀（macOS / Linux 通用）
  if command -v pkill >/dev/null 2>&1; then
    pkill -f "$pattern" 2>/dev/null || true
  fi
  # 2) 按端口释放（macOS / 部分 Linux 有 lsof）
  if command -v lsof >/dev/null 2>&1; then
    local pids
    pids="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
    if [ -n "$pids" ]; then kill $pids 2>/dev/null || true; fi
    sleep 1
    pids="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
    if [ -n "$pids" ]; then kill -9 $pids 2>/dev/null || true; fi
  fi
  sleep 1
}

# 清理函数（Ctrl+C / kill 时联动收尾；普通执行完毕不触发，以保证服务常驻）
cleanup() {
  echo
  echo "收到中断，正在停止服务..."
  stop_old 9978 'com.github.catvod.host.HostMain'
  stop_old 8888 'server.js'
  rm -f "$ROOT/tvbox-spider-server/run/host.pid"
}
trap cleanup INT TERM

SPIDER_RUN="$ROOT/tvbox-spider-server/run"

echo "[0/3] 停止之前运行的实例（如有）..."
# spider：清掉残留 PID 文件 + 释放 9978，避免 start.sh 守卫误判“已在运行”
rm -f "$SPIDER_RUN/host.pid" 2>/dev/null || true
stop_old 9978 'com.github.catvod.host.HostMain'
# web：释放 8888
stop_old 8888 'server.js'

echo "[1/3] 启动 tvbox-spider-server (9978) ..."
cd "$ROOT/tvbox-spider-server"
if [ -x ./start.sh ]; then
  ./start.sh > /tmp/spider.log 2>&1 &
else
  echo "缺少 start.sh，请先在 tvbox-spider-server 执行 ./build.sh && ./start.sh"
  exit 1
fi

# 等待 9978 就绪（最多 30s）
for i in $(seq 1 30); do
  if (exec 3<>/dev/tcp/127.0.0.1/9978) 2>/dev/null; then exec 3>&-; echo "      spider 已就绪"; break; fi
  sleep 1
done

# 生成/读取访问令牌（上线防护）
TOKEN="${SITE_TOKEN:-$(openssl rand -hex 16)}"
echo "[2/3] 访问令牌（公网访问需携带 ?t=$TOKEN）: $TOKEN"

# 启动 web（反代 spider，带令牌保护）
echo "[3/3] 启动 tvbox-web (${PORT:-8888}) ..."
cd "$ROOT/tvbox-web"
PORT="${PORT:-8888}" \
SPIDER_UPSTREAM="${SPIDER_UPSTREAM:-http://127.0.0.1:9978}" \
SITE_TOKEN="$TOKEN" \
node server.js > /tmp/web.log 2>&1 &

echo "      已启动。本地访问: http://127.0.0.1:${PORT:-8888}/?t=$TOKEN"
echo "      后台配置台:       http://127.0.0.1:${PORT:-8888}/admin?t=$TOKEN"
echo "      日志: /tmp/web.log  /tmp/spider.log"
echo "      穿透示例: cloudflared tunnel --url http://localhost:${PORT:-8888}"
