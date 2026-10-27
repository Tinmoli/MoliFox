#!/bin/sh
set -eu
task_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
for launcher in "$task_dir"/release/MoliFox-*-linux-*.run; do
    if [ -f "$launcher" ]; then exec sh "$launcher" "$@"; fi
done
exec sh "$task_dir/gradlew" -p "$task_dir" :desktop-app:run "$@"
