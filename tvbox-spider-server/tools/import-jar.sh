#!/usr/bin/env bash
#
# tools/import-jar.sh —— 把 Android 的 dex 格式 spider jar 转成 JVM 可加载的 class jar
# ---------------------------------------------------------------------------
# 用法：
#   bash tools/import-jar.sh <input.jar> [output.jar]
#   不传 output 时输出到 plugins/<input 名>-jvm.jar
#
# 背景：Android 的 spider.jar 里是 classes.dex，桌面 JVM 的 URLClassLoader 无法直接加载，
#       必须先用 dex2jar（libs/dex2jar/ 下的 d2j 系列 jar）转换成标准 class jar。
# 说明：转换用的 classpath 只包含 libs/dex2jar/*，避免 asm 等依赖污染宿主运行时 classpath。
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_BIN="${JAVA_BIN:-/usr/bin/java}"
JAVAC_BIN="${JAVAC_BIN:-/usr/bin/javac}"

if [ $# -lt 1 ]; then
  echo "用法: bash tools/import-jar.sh <input.jar> [output.jar]" >&2
  exit 2
fi

INPUT="$1"
if [ ! -f "$INPUT" ]; then
  echo "输入文件不存在: $INPUT" >&2
  exit 3
fi
INPUT_ABS="$(cd "$(dirname "$INPUT")" && pwd)/$(basename "$INPUT")"

if [ $# -ge 2 ]; then
  OUTPUT="$2"
else
  OUTPUT="$ROOT/plugins/$(basename "${INPUT%.jar}")-jvm.jar"
fi
mkdir -p "$(dirname "$OUTPUT")"

D2J_CP="$(find "$ROOT/libs/dex2jar" -maxdepth 1 -name '*.jar' 2>/dev/null | tr '\n' ':')"
if [ -z "$D2J_CP" ]; then
  echo "未找到 libs/dex2jar/*.jar，请先执行: bash tools/fetch-deps.sh" >&2
  exit 4
fi

TOOL_CLASS="$ROOT/build/classes/com/github/catvod/tools/Dex2JarTool.class"
if [ ! -f "$TOOL_CLASS" ]; then
  echo "== 首次使用，编译 Dex2JarTool"
  mkdir -p "$ROOT/build/classes"
  "$JAVAC_BIN" -encoding UTF-8 -nowarn -cp "$D2J_CP" -d "$ROOT/build/classes" \
    "$ROOT/src/com/github/catvod/tools/Dex2JarTool.java"
fi

echo "== dex -> jvm 转换"
echo "   input : $INPUT_ABS"
echo "   output: $OUTPUT"
"$JAVA_BIN" -cp "$ROOT/build/classes:$D2J_CP" com.github.catvod.tools.Dex2JarTool "$INPUT_ABS" "$OUTPUT"

echo "== 完成：$OUTPUT"
