#!/usr/bin/env bash
# 导入前静态预检：对第三方 spider jar 输出「格式判定 / dex 转换 / spider 类清单 / 缺失类 / 能否直接跑」报告。
# 与服务端运行期诊断（GET /api/diagnose/{siteKey}）复用同一套逻辑，报告结论与实际加载行为一致。
#
# 用法:
#   bash tools/precheck-jar.sh <jar> [--api csp_Xxx] [--json] [--no-probe]
#     --api csp_Xxx  指定站点 api；不指定时若 jar 内只有一个非抽象 Spider 子类会自动推断
#     --json         以 JSON 输出（与 /api/diagnose 同构）
#     --no-probe     跳过实例化与 homeContent 探针，仅做静态检查
#
# 退出码: 0=主链路可跑; 1=存在致命问题（无法加载/实例化/HOME 探针失败）; 2=参数错误; 3=jar 不存在; 4=未编译
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [ "$#" -lt 1 ]; then
  echo "用法: bash tools/precheck-jar.sh <jar> [--api csp_Xxx] [--json] [--no-probe]" >&2
  exit 2
fi

JAR="$1"
shift
case "$JAR" in
  /*) ;;
  *) JAR="$ROOT/$JAR" ;;
esac
if [ ! -f "$JAR" ]; then
  echo "jar 不存在: $JAR" >&2
  exit 3
fi

if [ ! -d "$ROOT/build/classes" ]; then
  echo "未找到 build/classes，请先执行: bash build.sh" >&2
  exit 4
fi

JAVA_BIN="java"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
fi

exec "$JAVA_BIN" -Dtvbox.home="$ROOT" \
  -cp "$ROOT/build/classes:$ROOT/libs/*" \
  com.github.catvod.tools.PrecheckTool "$JAR" "$@"
