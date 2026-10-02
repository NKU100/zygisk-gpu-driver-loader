#!/bin/sh
set -e

ZIPFILE=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
install_test_dir=$(mktemp -d /tmp/gpu-module-install.XXXXXX)
trap 'rm -r "$install_test_dir"' EXIT
TMPDIR="$install_test_dir/tmp"
MODPATH="$install_test_dir/module"
mkdir "$TMPDIR" "$MODPATH"
export ZIPFILE TMPDIR MODPATH
unzip -oq "$ZIPFILE" module.prop customize.sh -d "$TMPDIR"

BOOTMODE=true
KSU=true
KSU_KERNEL_VER_CODE=0
KSU_VER_CODE=0
ARCH=arm64
ui_print() { printf '%s\n' "$*"; }
abort() { printf '%s\n' "$*" >&2; exit 1; }
grep_prop() { sed -n "s/^$1=//p" "$2"; }
getprop() { :; }
set_perm_recursive() { :; }
which() { return 1; }
sha256sum() {
    if [ "$1" = -c ] && [ "$2" = -s ]; then
        command sha256sum -c "$3" > /dev/null
    else
        command sha256sum "$@"
    fi
}

. "$TMPDIR/customize.sh"

for library in arm64-v8a.so libhook_impl.so libmain_hook.so; do
    if [ ! -s "$MODPATH/zygisk/$library" ]; then
        printf 'Missing installed native library: %s\n' "$library" >&2
        exit 1
    fi
done
printf 'arm64 installation exposes the module and both hook libraries\n'
