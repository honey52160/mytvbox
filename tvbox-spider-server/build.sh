#!/usr/bin/env bash
#
# build.sh —— 纯 javac 构建（不依赖 maven/gradle）
# ---------------------------------------------------------------------------
# 1. 检查 libs/ 依赖是否齐备（缺失则提示先跑 tools/fetch-deps.sh）
# 2. javac 编译 src/ 下全部 java 源到 build/classes
# 3. 打包可执行 jar 到 build/tvbox-spider-server.jar（classpath 仍使用 libs/）
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIBS="$ROOT/libs"
SRC="$ROOT/src"
BUILD="$ROOT/build"
CLASSES="$BUILD/classes"
JAVA_BIN="${JAVA_BIN:-/usr/bin/java}"
JAVAC_BIN="${JAVAC_BIN:-/usr/bin/javac}"

if [ ! -d "$LIBS" ] || [ -z "$(ls -A "$LIBS" 2>/dev/null || true)" ]; then
  echo "libs/ 为空，请先执行：bash tools/fetch-deps.sh" >&2
  exit 1
fi

# 清理旧产物：不删除，仅重命名备份（避免误删，便于回滚）
if [ -d "$CLASSES" ]; then
  mv "$CLASSES" "$BUILD/classes.bak.$(date +%s)"
fi
mkdir -p "$CLASSES"

CP="$(find "$LIBS" -maxdepth 1 -name '*.jar' | tr '\n' ':')"
D2J_CP="$(find "$LIBS/dex2jar" -maxdepth 1 -name '*.jar' 2>/dev/null | tr '\n' ':')"
# 编译 Dex2JarTool 需要 dex2jar（仅编译期），宿主运行时不需要（转换走独立子进程）
CP="$CP$D2J_CP"

SOURCES="$BUILD/sources.txt"
# 每行路径用双引号包裹：工程路径含空格时 javac @argfile 仍能正确解析
find "$SRC" -name '*.java' -print | sed 's/^/"/; s/$/"/' > "$SOURCES"
COUNT="$(wc -l < "$SOURCES" | tr -d ' ')"

echo "== 编译源码：$COUNT 个 .java"
echo "== 依赖 jar 数：$(find "$LIBS" -maxdepth 1 -name '*.jar' | wc -l | tr -d ' ') + dex2jar $(find "$LIBS/dex2jar" -maxdepth 1 -name '*.jar' 2>/dev/null | wc -l | tr -d ' ')"

# --add-modules jdk.httpserver: 使用 JDK 内置 HTTP 服务器（无需第三方 web 容器）
"$JAVAC_BIN" -encoding UTF-8 -nowarn \
  --add-modules jdk.httpserver \
  -cp "$CP" \
  -d "$CLASSES" \
  "@$SOURCES"

echo "== 生成可执行 jar"
"$JAVA_BIN" -version >/dev/null 2>&1 || true
MANIFEST="$BUILD/MANIFEST.MF"
cat > "$MANIFEST" <<'EOF'
Manifest-Version: 1.0
Main-Class: com.github.catvod.host.HostMain
EOF
( cd "$CLASSES" && /usr/bin/jar --create --file "$BUILD/tvbox-spider-server.jar" --manifest "$MANIFEST" . )

echo "== 构建成功"
echo "   classes: $CLASSES"
echo "   jar    : $BUILD/tvbox-spider-server.jar"
