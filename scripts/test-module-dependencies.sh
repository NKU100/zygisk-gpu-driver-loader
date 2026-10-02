#!/bin/sh
set -eu

readelf_tool=${1:?Provide llvm-readelf path}
module_library=${2:?Provide built Zygisk module library}
dependencies=$("$readelf_tool" -d "$module_library")
if printf '%s\n' "$dependencies" | grep -F 'Shared library: [libandroid.so]' >/dev/null; then
    printf 'Zygisk module must not require libandroid.so in the companion namespace\n' >&2
    exit 1
fi
printf 'Zygisk module has no libandroid.so dependency\n'
