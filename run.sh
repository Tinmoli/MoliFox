#!/bin/sh
set -eu
task_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec sh "$task_dir/gradlew" -p "$task_dir" :desktop-app:run "$@"
