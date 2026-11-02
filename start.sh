#!/usr/bin/env bash
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
echo "Working directory: $ROOT"

CMD="${1:-run}"
FORCE_JCEF=0
SKIP_JCEF=0

for arg in "$@"; do
    case "$arg" in
        --jcef) FORCE_JCEF=1 ;;
        --no-jcef) SKIP_JCEF=1 ;;
    esac
done

if [[ "$CMD" != "run" && "$CMD" != "build" ]]; then
    echo "Usage: ./start.sh [run|build] [--jcef|--no-jcef]"
    echo ""
    echo "  run       Compile and launch YunX-Desktop app"
    echo "  build     Compile only (no launch)"
    echo "  --jcef    Force enabling embedded Chromium (JCEF) on Linux"
    echo "  --no-jcef Disable embedded Chromium (login falls back to paste mode)"
    echo ""
    echo "Windows: run this from Git Bash, or double-click start.bat."
    exit 1
fi

# ---- 平台识别（macOS / Linux / Windows(Git Bash、MSYS2、Cygwin)）----
UNAME_S="$(uname -s)"
UNAME_M="$(uname -m)"
case "$UNAME_S" in
    Darwin) HOST_OS="mac" ;;
    Linux)  HOST_OS="linux" ;;
    MINGW*|MSYS*|CYGWIN*) HOST_OS="windows" ;;
    *) HOST_OS="unknown" ;;
esac
case "$UNAME_M" in
    arm64|aarch64) HOST_ARCH="arm64" ;;
    *) HOST_ARCH="x64" ;;
esac
echo "[i] Host: $HOST_OS/$HOST_ARCH ($UNAME_S $UNAME_M)"

# ---- Find JDK 17 ----
is_jdk() {
    [[ -n "$1" && -x "$1/bin/java" ]] || [[ -n "$1" && -x "$1/bin/java.exe" ]]
}

JAVA_HOME=""

find_jdk() {
    local c
    if is_jdk "${JAVA_HOME_ORIG:-}"; then
        JAVA_HOME="$JAVA_HOME_ORIG"
        return 0
    fi

    local candidates=(
        "/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home"
        "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home"
        "/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home"
        "/Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home"
        "/usr/lib/jvm/java-17-openjdk-amd64"
        "/usr/lib/jvm/java-17-openjdk"
        "/usr/lib/jvm/temurin-17-jdk-amd64"
        "/usr/lib/jvm/temurin-17-jdk-aarch64"
        "$HOME/.jdk/jdk-17"
        "$ROOT/.jdk/jdk-17"
    )
    for c in "${candidates[@]}"; do
        if is_jdk "$c"; then
            JAVA_HOME="$c"
            return 0
        fi
    done

    # 便携下载目录 + Windows 常见安装位置（未匹配的通配符原样保留，is_jdk 自然返回 false）
    for c in "$HOME"/.jdk-portable/jdk-17* \
             "/c/Program Files/Java/jdk-17"* \
             "/c/Program Files/Eclipse Adoptium/jdk-17"* \
             "/c/Program Files/Microsoft/jdk-17"* \
             "/c/Program Files/Zulu/zulu-17"* \
             "/c/Program Files/Amazon Corretto/jdk17"* ; do
        if is_jdk "$c"; then
            JAVA_HOME="$c"
            return 0
        fi
    done

    # 最后回退：PATH 里的 java
    # ★ 用 awk 而不是 grep -oP：-P 是 GNU grep 专有参数，macOS 自带的 BSD grep 不支持
    local cmd_java home
    cmd_java="$(command -v java 2>/dev/null || true)"
    if [[ -n "$cmd_java" ]]; then
        home="$("$cmd_java" -XshowSettings:properties -version 2>&1 \
            | awk -F'= ' '/java.home =/ { print $2; exit }' | tr -d '\r')"
        if is_jdk "$home"; then
            JAVA_HOME="$home"
            return 0
        fi
    fi
    return 1
}

JAVA_HOME_ORIG="${JAVA_HOME:-}"
find_jdk || true

# ---- Download portable JDK 17 ----
download() {
    if command -v curl >/dev/null 2>&1; then
        curl -fL --retry 3 --connect-timeout 20 -o "$2" "$1"
    elif command -v wget >/dev/null 2>&1; then
        wget -O "$2" "$1"
    else
        echo "[FAIL] Neither curl nor wget found" >&2
        return 1
    fi
}

extract() {
    case "$1" in
        *.zip)
            if command -v unzip >/dev/null 2>&1; then
                unzip -q -o "$1" -d "$2"
            elif command -v powershell.exe >/dev/null 2>&1; then
                local wsrc wdst
                wsrc="$(cygpath -w "$1" 2>/dev/null || echo "$1")"
                wdst="$(cygpath -w "$2" 2>/dev/null || echo "$2")"
                powershell.exe -NoProfile -Command \
                    "Expand-Archive -LiteralPath '$wsrc' -DestinationPath '$wdst' -Force" >/dev/null
            else
                echo "[FAIL] Need unzip or PowerShell to extract .zip" >&2
                return 1
            fi
            ;;
        *)
            tar -xzf "$1" -C "$2"
            ;;
    esac
}

if [[ -z "$JAVA_HOME" ]]; then
    echo "[!] No JDK found. Downloading portable Microsoft OpenJDK 17 (~200MB)..."
    JDK_DIR="$HOME/.jdk-portable"
    mkdir -p "$JDK_DIR"

    JDK_VER="17.0.14"
    case "$HOST_OS/$HOST_ARCH" in
        mac/arm64)   JDK_PKG="microsoft-jdk-$JDK_VER-macos-aarch64.tar.gz" ;;
        mac/x64)     JDK_PKG="microsoft-jdk-$JDK_VER-macos-x64.tar.gz" ;;
        linux/x64)   JDK_PKG="microsoft-jdk-$JDK_VER-linux-x64.tar.gz" ;;
        linux/arm64) JDK_PKG="microsoft-jdk-$JDK_VER-linux-aarch64.tar.gz" ;;
        windows/x64) JDK_PKG="microsoft-jdk-$JDK_VER-windows-x64.zip" ;;
        *)
            echo "[FAIL] Unsupported platform: $HOST_OS/$HOST_ARCH"
            exit 1
            ;;
    esac
    JDK_URL="https://aka.ms/download-jdk/$JDK_PKG"
    JDK_ARCHIVE="$JDK_DIR/$JDK_PKG"

    echo "    Downloading from: $JDK_URL"
    echo "    (This may take a few minutes - JDK is ~200MB)"
    download "$JDK_URL" "$JDK_ARCHIVE" || { echo "[FAIL] Download failed"; exit 1; }

    echo "    Extracting..."
    extract "$JDK_ARCHIVE" "$JDK_DIR" || { echo "[FAIL] Extraction failed"; exit 1; }
    rm -f "$JDK_ARCHIVE"

    for c in "$JDK_DIR"/jdk-17*; do
        if is_jdk "$c"; then
            JAVA_HOME="$c"
            break
        fi
    done
    if [[ -z "$JAVA_HOME" ]]; then
        echo "[FAIL] JDK extraction failed"
        exit 1
    fi
fi

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
if [[ -x "$JAVA_HOME/bin/java" ]]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
else
    JAVA_BIN="$JAVA_HOME/bin/java.exe"
fi
echo "[OK] Using JDK: $JAVA_HOME"
"$JAVA_BIN" -version 2>&1 | head -1

# ---- Run Gradle ----
echo ""
GRADLE_JAR="$ROOT/gradle/wrapper/gradle-wrapper.jar"
if [[ ! -f "$GRADLE_JAR" ]]; then
    echo "[FAIL] gradle-wrapper.jar not found: $GRADLE_JAR"
    exit 1
fi

# 首次运行需要下载 Gradle 发行包；wrapper 的 distributionUrl 已指向腾讯镜像
if ! ls "$HOME/.gradle/wrapper/dists/gradle-"*/ >/dev/null 2>&1; then
    echo "[i] First run: downloading Gradle distribution (mirror, ~130MB)..."
fi

# 内嵌浏览器（JCEF）开关：
#   Windows / macOS 默认启用（原生库按宿主平台自动引入，见 desktop/build.gradle.kts）。
#   Linux 默认仍跳过（未验证），需要时用 --jcef 强制启用；--no-jcef 可在任意平台关闭。
if [[ "$SKIP_JCEF" == "1" ]]; then
    export YUNXPC_SKIP_JCEF=1
    echo "[i] JCEF disabled (--no-jcef): 登录页使用粘贴凭据模式"
elif [[ "$HOST_OS" == "linux" && "$FORCE_JCEF" != "1" ]]; then
    export YUNXPC_SKIP_JCEF=1
    echo "[i] JCEF skipped on Linux by default. Use --jcef to force."
fi

if [[ "$CMD" == "run" ]]; then
    TARGET=":desktop:run"
    echo ">>> Compiling and launching YunX-Desktop app..."
else
    TARGET=":desktop:jar"
    echo ">>> Building project (compile only)..."
fi

# 全量编译（首次运行或改动源码后）会持续 1-2 分钟且几乎没有输出，属正常现象，
# 不要误判为卡死；中途 Ctrl+C 会让下次重新全量编译。
echo "[i] Full compile may take 1-2 min with little output - please wait, do not Ctrl+C."

set +e
"$JAVA_BIN" -Xmx64m -Xms64m \
    -Dorg.gradle.appname=gradlew \
    -jar "$GRADLE_JAR" \
    "$TARGET" --console=plain
STATUS=$?
set -e

if [[ "$STATUS" -ne 0 ]]; then
    echo ""
    echo "[FAIL] Gradle exited with code $STATUS"
    if [[ "$STATUS" -eq 130 || "$STATUS" -eq 143 ]]; then
        echo "        ($STATUS = 被 Ctrl+C / SIGTERM 中断；下次会重新全量编译)"
    elif [[ "$STATUS" -eq 1 ]]; then
        echo "        编译或运行失败，请向上翻看 Gradle 输出的具体报错。"
    fi
    exit "$STATUS"
fi

echo ""
echo "[DONE] Finished."