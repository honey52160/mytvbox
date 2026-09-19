#!/usr/bin/env bash
#
# tools/fetch-deps.sh
# ---------------------------------------------------------------------------
# 下载宿主服务编译/运行所需的第三方 jar 到 <project>/libs/。
# 依赖来源优先级：本地 maven 缓存(~/.m2) -> 镜像(默认阿里云) -> Maven Central。
#
# 用法：
#   bash tools/fetch-deps.sh
#   MAVEN_MIRROR=https://maven.aliyun.com/repository/public bash tools/fetch-deps.sh
#
# 说明：本工程不使用 maven/gradle，仅用 curl 拉取 jar。
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIBS="$ROOT/libs"
mkdir -p "$LIBS"

MIRROR="${MAVEN_MIRROR:-https://maven.aliyun.com/repository/public}"
CENTRAL="${MAVEN_CENTRAL:-https://repo1.maven.org/maven2}"
M2="${LOCAL_M2:-$HOME/.m2/repository}"
CURL_OPTS=(-fsSL --connect-timeout 20 --retry 2 --retry-delay 1)

# groupPath|artifactId|version|fileName
# groupPath|artifactId|version|fileName|subDir(可空, 相对 libs/)
DEPS=(
  # ---- 宿主自身运行依赖 ----
  "com/squareup/okhttp3|okhttp|3.12.11|okhttp-3.12.11.jar|"
  "com/squareup/okio|okio|1.17.5|okio-1.17.5.jar|"
  "com/google/code/gson|gson|2.10.1|gson-2.10.1.jar|"
  "org/json|json|20190722|json-20190722.jar|"
  "org/jsoup|jsoup|1.14.1|jsoup-1.14.1.jar|"
  "org/apache/commons|commons-lang3|3.12.0|commons-lang3-3.12.0.jar|"
  # ---- dex2jar 2.4.28（de/femtopedia 维护版）：把 Android dex jar 转成 JVM jar ----
  # 仅 jar 导入/转换时使用（Dex2JarTool 子进程），宿主运行期 classpath 不需要
  "de/femtopedia/dex2jar|d2j-base-cmd|2.4.28|d2j-base-cmd-2.4.28.jar|dex2jar"
  "de/femtopedia/dex2jar|d2j-external|2.4.28|d2j-external-2.4.28.jar|dex2jar"
  "de/femtopedia/dex2jar|d2j-jasmin|2.4.28|d2j-jasmin-2.4.28.jar|dex2jar"
  "de/femtopedia/dex2jar|d2j-smali|2.4.28|d2j-smali-2.4.28.jar|dex2jar"
  "de/femtopedia/dex2jar|dex-ir|2.4.28|dex-ir-2.4.28.jar|dex2jar"
  "de/femtopedia/dex2jar|dex-reader|2.4.28|dex-reader-2.4.28.jar|dex2jar"
  "de/femtopedia/dex2jar|dex-reader-api|2.4.28|dex-reader-api-2.4.28.jar|dex2jar"
  "de/femtopedia/dex2jar|dex-tools|2.4.28|dex-tools-2.4.28.jar|dex2jar"
  "de/femtopedia/dex2jar|dex-translator|2.4.28|dex-translator-2.4.28.jar|dex2jar"
  "de/femtopedia/dex2jar|dex-writer|2.4.28|dex-writer-2.4.28.jar|dex2jar"
  # dex2jar 的传递依赖：antlr4-runtime（d2j-smali 汇编 smali 时用）
  "org/antlr|antlr4-runtime|4.13.2|antlr4-runtime-4.13.2.jar|dex2jar"
  # dex2jar 的传递依赖：ASM（dex-translator 写 class 时用，版本与 dex2jar 2.4.28 pom 一致）
  "org/ow2/asm|asm|9.8|asm-9.8.jar|dex2jar"
  "org/ow2/asm|asm-commons|9.8|asm-commons-9.8.jar|dex2jar"
  "org/ow2/asm|asm-tree|9.8|asm-tree-9.8.jar|dex2jar"
  "org/ow2/asm|asm-analysis|9.8|asm-analysis-9.8.jar|dex2jar"
  "org/ow2/asm|asm-util|9.8|asm-util-9.8.jar|dex2jar"
)

FAILED=()

download_one() {
  local group="$1" artifact="$2" version="$3" file="$4" subdir="${5:-}"
  local dir="$LIBS"
  [ -n "$subdir" ] && dir="$LIBS/$subdir"
  mkdir -p "$dir"
  local target="$dir/$file"
  if [ -s "$target" ]; then
    echo "[skip] $file 已存在"
    return 0
  fi

  # 1) 本地 maven 缓存
  local m2path="$M2/$group/$artifact/$version/$file"
  if [ -s "$m2path" ]; then
    cp "$m2path" "$target"
    echo "[m2  ] $file  <- $m2path"
    return 0
  fi

  # 2) 镜像 / 3) 中央仓库
  local url
  for base in "$MIRROR" "$CENTRAL"; do
    url="$base/$group/$artifact/$version/$file"
    if curl "${CURL_OPTS[@]}" -o "$target.part" "$url"; then
      mv "$target.part" "$target"
      echo "[http ] $file  <- $url"
      return 0
    fi
    # 不使用 rm：把半成品重命名归档，便于排查
    [ -f "$target.part" ] && mv -f "$target.part" "$target.part.failed"
  done

  echo "[fail] $file 下载失败" >&2
  FAILED+=("$file")
  return 1
}

echo "== fetch-deps: mirror=$MIRROR"
for dep in "${DEPS[@]}"; do
  IFS='|' read -r g a v f d <<< "$dep"
  download_one "$g" "$a" "$v" "$f" "${d:-}" || true
done

echo
echo "== libs 目录 =="
ls -1sh "$LIBS"
if [ -d "$LIBS/dex2jar" ]; then
  echo "== libs/dex2jar（dex → JVM 转换工具链）=="
  ls -1sh "$LIBS/dex2jar"
fi

if [ "${#FAILED[@]}" -gt 0 ]; then
  echo
  echo "以下依赖未能获取：${FAILED[*]}" >&2
  echo "可尝试：MAVEN_MIRROR=<其它镜像> bash tools/fetch-deps.sh" >&2
  exit 1
fi

echo
echo "依赖下载完成：$LIBS"
