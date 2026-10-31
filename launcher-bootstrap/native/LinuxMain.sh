#!/bin/sh
set -eu

# This header is joined with the platform-specific JAR by portableLinux.
applicationDirectory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
data="$applicationDirectory/MoliFox"
mkdir -p "$data/dependencies/launcher" "$data/cache/tmp" "$data/cache/downloads/java" "$data/cache/metadata" "$data/logs" "$data/runtimes"
export MOLIFOX_DATA_DIR="$data"
export MOLIFOX_BUNDLE_PATH="$0"
export MOLIFOX_WRAPPER_PID=$$
runTemporary=$(mktemp -d "$data/cache/tmp/session-XXXXXX")
export TMPDIR="$runTemporary"
export XDG_CACHE_HOME="$data/cache"
jar="$data/dependencies/launcher/MoliFox-@VERSION@.jar"
payloadHash="@PAYLOAD_SHA256@"
javaPid=""
cleanup() {
    case "$runTemporary" in "$data/cache/tmp/session-"*) rm -rf -- "$runTemporary" ;; esac
}
stopJava() {
    if [ -n "$javaPid" ]; then kill -TERM "$javaPid" 2>/dev/null || true; wait "$javaPid" 2>/dev/null || true; fi
    exit "$1"
}
trap cleanup EXIT
trap 'stopJava 130' INT
trap 'stopJava 143' TERM

fail() { printf '%s\n' "MoliFox: $*" >&2; printf '%s\n' "$*" > "$data/logs/bootstrap.log"; exit 1; }
verify() { printf '%s  %s\n' "$1" "$2" | sha256sum -c - >/dev/null 2>&1; }
command -v sha256sum >/dev/null || fail "需要 sha256sum 来验证运行文件。"
if ! verify "$payloadHash" "$jar"; then
    payloadLine=$(awk '/^__MOLIFOX_PAYLOAD__$/ { print NR + 1; exit }' "$0")
    tail -n +"$payloadLine" "$0" > "$runTemporary/launcher-payload.part"
    verify "$payloadHash" "$runTemporary/launcher-payload.part" || fail "启动器文件不完整，请重新下载。"
    mv -f "$runTemporary/launcher-payload.part" "$jar"
fi

usableJava() {
    [ -x "$1" ] || return 1
    if versionText=$("$1" -Xms32m -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=20 -XX:G1PeriodicGCInterval=30000 -version 2>&1); then
        memoryTuningSupported=true
    else
        memoryTuningSupported=false
        versionText=$("$1" -version 2>&1) || return 1
    fi
    major=$(printf '%s\n' "$versionText" | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)
    [ "${major:-0}" -ge 17 ] 2>/dev/null
}
memoryTuningSupported=false
javaExecutable=""
for candidate in "${JAVA_HOME:-/nonexistent}/bin/java" "$(command -v java || true)" "$data"/runtimes/*/bin/java; do
    if usableJava "$candidate"; then javaExecutable="$candidate"; break; fi
done

fetch() {
    case "$1" in https://*) ;; *) fail "下载地址必须使用 HTTPS。" ;; esac
    if command -v curl >/dev/null; then curl --fail --location --retry 2 --proto '=https' --proto-redir '=https' "$1" -o "$2"
    elif command -v wget >/dev/null; then wget --https-only "$1" -O "$2"
    else fail "首次准备 Java 需要 curl 或 wget。"; fi
}

# 缺少系统 Java 时先征求同意；下载 Java 属于网络与磁盘操作，不能静默执行。
confirmJavaDownload() {
    message='没有找到可用的 Java 17 或更高版本。
是否从 Eclipse Temurin 官方源下载 Java 21 JRE（约 40–60 MB）？
文件会保存在旁边的 MoliFox/runtimes/，不会修改系统环境变量。
选择「取消」将退出；你也可以先自行安装 Java 17+ 后重试。'
    if command -v zenity >/dev/null 2>&1; then
        zenity --question --title='MoliFox · 需要 Java' --text="$message" \
            --ok-label='下载 Java 21' --cancel-label='退出' 2>/dev/null
    elif command -v kdialog >/dev/null 2>&1; then
        kdialog --title 'MoliFox · 需要 Java' --yesno "$message" \
            --yes-label '下载 Java 21' --no-label '退出' 2>/dev/null
    elif [ -t 0 ]; then
        printf '%s\n是否下载 Java 21？[y/N] ' "$message" >&2
        read -r answer || answer=
        case "$answer" in y|Y|yes|YES) return 0 ;; *) return 1 ;; esac
    else
        return 1
    fi
}

if [ -z "$javaExecutable" ]; then
    if ! confirmJavaDownload; then
        fail "未下载 Java，启动已取消。请先安装 Java 17 或更高版本，然后重试。"
    fi
    case "$(uname -m)" in x86_64) architecture=x64 ;; aarch64|arm64) architecture=aarch64 ;; *) fail "暂不支持此 CPU 架构。" ;; esac
    printf '%s\n' 'MoliFox 正在首次准备 Java 21，文件将保存在旁边的 MoliFox 文件夹。'
    metadata="$data/cache/metadata/java21.json"
    fetch "https://api.adoptium.net/v3/assets/latest/21/hotspot?os=linux&architecture=$architecture&image_type=jre&vendor=eclipse" "$metadata"
    checksum=$(sed -n 's/.*"checksum"[[:space:]]*:[[:space:]]*"\([a-fA-F0-9]*\)".*/\1/p' "$metadata" | head -n 1)
    link=$(sed -n 's/.*"link"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$metadata" | head -n 1)
    [ "${#checksum}" -eq 64 ] || fail "未取得有效的 Java 校验值。"
    archive="$data/cache/downloads/java/java21.tar.gz.part"
    fetch "$link" "$archive"
    verify "$checksum" "$archive" || fail "Java 下载校验失败，请重试。"
    staging="$runTemporary/java-extract"
    mkdir -p "$staging"
    tar -xzf "$archive" -C "$staging" --no-same-owner
    for candidate in "$staging"/*/bin/java; do
        if usableJava "$candidate"; then
            runtimeDirectory=$(dirname -- "$(dirname -- "$candidate")")
            mv "$runtimeDirectory" "$data/runtimes/launcher-java21"
            javaExecutable="$data/runtimes/launcher-java21/bin/java"
            break
        fi
    done
    [ -n "$javaExecutable" ] || fail "下载的 Java 环境无法运行。"
    rm -f "$archive"
fi

cd "$applicationDirectory"
# 先保留应用参数，再前置启动器选项；不通过环境变量把回收策略传给游戏。
set -- --enable-native-access=ALL-UNNAMED -Djava.net.useSystemProxies=true -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 "-Dmolifox.data.dir=$data" "-Djava.io.tmpdir=$TMPDIR" "-Dmolifox.temp.dir=$TMPDIR" -jar "$jar" "$@"
if [ "$memoryTuningSupported" = true ]; then
    set -- -Xms32m -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=20 -XX:G1PeriodicGCInterval=30000 "$@"
else
    set -- -Xms32m "$@"
fi
"$javaExecutable" "$@" &
javaPid=$!
set +e
wait "$javaPid"
result=$?
set -e
javaPid=""
exit "$result"
__MOLIFOX_PAYLOAD__
