#!/bin/bash
# TVBox Web 启动脚本（零依赖，仅需 Node >= 18）
cd "$(dirname "$0")" || exit 1
PORT="${PORT:-8888}" HOST="${HOST:-0.0.0.0}" exec node server.js
