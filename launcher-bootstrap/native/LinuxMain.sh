#!/bin/sh
set -eu

# This header is joined with the platform-specific JAR by portableLinux.
applicationDirectory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
data="$applicationDirectory/MoliFox"
mkdir -p "$data/dependencies/launcher" "$data/cache/tmp" "$data/cache/downloads/java" "$data/cache/metadata" "$data/logs" "$data/runtimes"
export MOLIFOX_DATA_DIR="$data"
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
    major=$("$1" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)
    [ "${major:-0}" -ge 17 ] 2>/dev/null
}
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

if [ -z "$javaExecutable" ]; then
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
"$javaExecutable" --enable-native-access=ALL-UNNAMED -Djava.net.useSystemProxies=true -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 "-Dmolifox.data.dir=$data" "-Djava.io.tmpdir=$TMPDIR" "-Dmolifox.temp.dir=$TMPDIR" -jar "$jar" "$@" &
javaPid=$!
set +e
wait "$javaPid"
result=$?
set -e
javaPid=""
exit "$result"
__MOLIFOX_PAYLOAD__
