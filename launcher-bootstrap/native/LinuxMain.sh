#!/bin/sh
set -eu

# 更新助手先于运行目录创建、负载解包和 Java 探测，不依赖 JVM。
if [ "${1:-}" = '--molifox-apply-update' ]; then
    shift
    updateFail() { printf 'Launcher update failed: %s\n' "$*" >&2; exit 1; }
    [ "$#" -eq 5 ] || updateFail 'Invalid argument count'
    target=$1 staged=$2 expected=$3 pids=$4 ready=$5
    for path in "$target" "$staged" "$ready"; do
        case "$path" in /*) ;; *) updateFail 'Absolute paths required' ;; esac
    done
    [ -f "$target" ] && [ ! -L "$target" ] && [ -f "$staged" ] && [ ! -L "$staged" ] || updateFail 'Invalid update files'
    [ "$target" != "$staged" ] && [ ! "$target" -ef "$0" ] && [ ! "$staged" -ef "$0" ] || updateFail 'Distinct files required'
    parent=$(CDPATH= cd -- "$(dirname -- "$target")" && pwd -P)
    stagedParent=$(CDPATH= cd -- "$(dirname -- "$staged")" && pwd -P)
    [ "$parent" = "$stagedParent" ] || updateFail 'Same-directory staging required'
    prefix=".${target##*/}.update-"
    case "${staged##*/}" in "$prefix"*) identifier=${staged##*/}; identifier=${identifier#"$prefix"} ;; *) updateFail 'Invalid staging name' ;; esac
    case "$identifier" in ????????-????-????-????-????????????) ;; *) updateFail 'Invalid staging identifier' ;; esac
    case "$identifier" in *[!0-9a-f-]*) updateFail 'Invalid staging identifier' ;; esac
    [ "${ready##*/}" = "apply-$identifier.ready" ] && [ ! -e "$ready" ] && [ ! -L "$ready" ] || updateFail 'Invalid readiness path'
    pendingBackup="$staged.backup"
    [ ! -e "$pendingBackup" ] && [ ! -L "$pendingBackup" ] || updateFail 'Backup staging path already exists'
    trap 'rm -f -- "$staged" "$ready" "$pendingBackup"' 0
    [ "${#expected}" -eq 64 ] || updateFail 'Invalid digest'
    case "$expected" in *[!0-9a-f]*) updateFail 'Invalid digest' ;; esac
    for tool in sha256sum cp mv chmod setsid sleep; do command -v "$tool" >/dev/null || updateFail "Missing $tool"; done
    updateHash() { value=$(sha256sum < "$1") || return 1; printf '%s' "${value%% *}"; }
    [ "$(updateHash "$staged")" = "$expected" ] && [ "$(updateHash "$0")" = "$expected" ] || updateFail 'Digest mismatch before exit'
    original=$(updateHash "$target")
    [ -w "$target" ] && [ -w "$parent" ] || updateFail 'Launcher location is not writable'
    chmod +x -- "$staged"
    backup="$target.previous"
    [ ! -L "$backup" ] && { [ ! -e "$backup" ] || [ -f "$backup" ]; } || updateFail 'Invalid backup file'
    # 记录进程启动时间，避免 PID 复用或僵尸进程使助手永久等待。
    updateProcess() {
        [ -d "/proc/$1" ] || return 0
        [ -r "/proc/$1/stat" ] || updateFail 'Cannot observe old process'
        line=$(cat "/proc/$1/stat" 2>/dev/null) || { [ ! -d "/proc/$1" ] && return 0; updateFail 'Cannot read old process'; }
        set -- ${line##*) }
        [ "$#" -ge 20 ] || return 0
        case "$1" in Z|X) return 0 ;; esac
        printf '%s' "${20}"
    }
    case "$pids" in *[!0-9,]*) updateFail 'Invalid process list' ;; esac
    oldIFS=$IFS; IFS=,; set -- $pids; IFS=$oldIFS
    [ "$#" -le 2 ] || updateFail 'Too many old processes'
    observed=
    for pid do
        [ -n "$pid" ] && [ "$pid" -gt 0 ] && [ "$pid" != "$$" ] || updateFail 'Invalid process ID'
        started=$(updateProcess "$pid")
        if [ -n "$started" ]; then observed="$observed $pid:$started"; fi
    done
    umask 077
    (set -C; printf ready > "$ready")
    attempt=0
    while :; do
        alive=false
        for process in $observed; do
            pid=${process%%:*}; started=${process#*:}
            current=$(updateProcess "$pid") || updateFail 'Cannot observe old process'
            if [ "$current" = "$started" ]; then alive=true; fi
        done
        [ "$alive" = true ] || break
        [ "$attempt" -lt 120 ] || updateFail 'Timed out waiting for old launcher'
        attempt=$((attempt + 1))
        sleep 1
    done
    [ ! -L "$target" ] && [ ! -L "$staged" ] || updateFail 'Update paths changed'
    [ "$(updateHash "$staged")" = "$expected" ] && [ "$(updateHash "$target")" = "$original" ] || updateFail 'Update files changed'
    cp -p -- "$target" "$pendingBackup"
    mv -f -- "$pendingBackup" "$backup"
    mv -f -- "$staged" "$target"
    updated=$target
    case "${target##*/}" in
        MoliFox-[0-9]*-linux-x86_64.run)
            # 旧发布名带版本与平台段：更新成功后迁移成固定名，用户自己改的名字保持不动。
            canonical="$parent/MoliFox.run"
            if [ "$target" != "$canonical" ] && rm -f -- "$canonical" 2>/dev/null; then
                if mv -f -- "$target" "$canonical" 2>/dev/null; then updated=$canonical; fi
            fi
            ;;
    esac
    cd -- "$parent"
    setsid sh "$updated" </dev/null &
    # 新版本已经起来，旧版本副本没有回滚价值了，不再留着。
    rm -f -- "$backup"
    exit 0
fi

# This header is joined with the platform-specific JAR by portableLinux.
applicationDirectory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
data="$applicationDirectory/MoliFox"
mkdir -p "$data/dependencies/launcher" "$data/cache/tmp" "$data/cache/downloads/java" "$data/cache/metadata" "$data/logs" "$data/runtimes"
@NATIVE_MESSAGES@
export MOLIFOX_DATA_DIR="$data"
export MOLIFOX_BUNDLE_PATH="$0"
export MOLIFOX_WRAPPER_PID=$$
# 更新成功后旧版本副本会被删除；上次中断留下的也在这里清掉，避免目录里堆积 .previous。
rm -f -- "$0.previous" 2>/dev/null || true
runTemporary=$(mktemp -d "$data/cache/tmp/session-XXXXXX")
export TMPDIR="$runTemporary"
export XDG_CACHE_HOME="$data/cache"
jar="$data/dependencies/launcher/MoliFox.jar"
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
command -v sha256sum >/dev/null || fail "$shaMissing"
if ! verify "$payloadHash" "$jar"; then
    payloadLine=$(awk '/^__MOLIFOX_PAYLOAD__$/ { print NR + 1; exit }' "$0")
    tail -n +"$payloadLine" "$0" > "$runTemporary/launcher-payload.part"
    verify "$payloadHash" "$runTemporary/launcher-payload.part" || fail "$bundleIncomplete"
    mv -f "$runTemporary/launcher-payload.part" "$jar"
fi
# 当前载荷验证通过后只删除官方旧版 JAR；启动器 .run 的用户自定义文件名不参与匹配。
for oldJar in "$data/dependencies/launcher"/MoliFox-*.jar; do
    if [ "$oldJar" != "$jar" ] && [ -f "$oldJar" ] && [ ! -L "$oldJar" ]; then
        rm -f -- "$oldJar" 2>/dev/null || true
    fi
done

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
    case "$1" in https://*) ;; *) fail "$httpsRequired" ;; esac
    if command -v curl >/dev/null; then curl --fail --location --retry 2 --proto '=https' --proto-redir '=https' "$1" -o "$2"
    elif command -v wget >/dev/null; then wget --https-only "$1" -O "$2"
    else fail "$downloadToolMissing"; fi
}

# 缺少系统 Java 时先征求同意；下载 Java 属于网络与磁盘操作，不能静默执行。
confirmJavaDownload() {
    message=$javaRequiredMessage
    if command -v zenity >/dev/null 2>&1; then
        zenity --question --title="$javaRequiredTitle" --text="$message" \
            --ok-label="$downloadButton" --cancel-label="$exitButton" 2>/dev/null
    elif command -v kdialog >/dev/null 2>&1; then
        kdialog --title "$javaRequiredTitle" --yesno "$message" \
            --yes-label "$downloadButton" --no-label="$exitButton" 2>/dev/null
    elif [ -t 0 ]; then
        printf '%s\n%s ' "$message" "$terminalPrompt" >&2
        read -r answer || answer=
        case "$answer" in y|Y|yes|YES) return 0 ;; *) return 1 ;; esac
    else
        return 1
    fi
}

if [ -z "$javaExecutable" ]; then
    if ! confirmJavaDownload; then
        fail "$javaCancelled"
    fi
    case "$(uname -m)" in x86_64) architecture=x64 ;; aarch64|arm64) architecture=aarch64 ;; *) fail "$archUnsupported" ;; esac
    printf '%s\n' "$javaPreparingLinux"
    # Linux x64 与 aarch64 都有 Temurin 17，首次下载统一用 17（兼容性更保守）；
    # 缓存、归档与安装目录相应改名 java17/launcher-java17，旧版已装的 launcher-java21
    # 仍由前面 runtimes/*/bin/java 的全子目录扫描识别，升级用户不会重复下载。
    metadata="$data/cache/metadata/java17.json"
    fetch "https://api.adoptium.net/v3/assets/latest/17/hotspot?os=linux&architecture=$architecture&image_type=jre&vendor=eclipse" "$metadata"
    checksum=$(sed -n 's/.*"checksum"[[:space:]]*:[[:space:]]*"\([a-fA-F0-9]*\)".*/\1/p' "$metadata" | head -n 1)
    link=$(sed -n 's/.*"link"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$metadata" | head -n 1)
    [ "${#checksum}" -eq 64 ] || fail "$checksumMissing"
    archive="$data/cache/downloads/java/java17.tar.gz.part"
    fetch "$link" "$archive"
    verify "$checksum" "$archive" || fail "$checksumFailed"
    staging="$runTemporary/java-extract"
    mkdir -p "$staging"
    tar -xzf "$archive" -C "$staging" --no-same-owner
    for candidate in "$staging"/*/bin/java; do
        if usableJava "$candidate"; then
            runtimeDirectory=$(dirname -- "$(dirname -- "$candidate")")
            mv "$runtimeDirectory" "$data/runtimes/launcher-java17"
            javaExecutable="$data/runtimes/launcher-java17/bin/java"
            break
        fi
    done
    [ -n "$javaExecutable" ] || fail "$runtimeUnusable"
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
