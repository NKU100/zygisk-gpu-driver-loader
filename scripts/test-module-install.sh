#!/bin/sh
set -e

ZIPFILE=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
test_arch=${2:-arm64}
test_has32bit=${3:-false}
case "$test_arch:$test_has32bit" in
    arm64:false) expected_libraries="arm64-v8a.so libhook_impl.so libmain_hook.so" ;;
    arm64:true) expected_libraries="arm64-v8a.so armeabi-v7a.so libhook_impl.so libmain_hook.so" ;;
    arm:*) expected_libraries="armeabi-v7a.so" ;;
    x64:false) expected_libraries="x86_64.so" ;;
    x64:true) expected_libraries="x86.so x86_64.so" ;;
    x86:*) expected_libraries="x86.so" ;;
    riscv64:*) expected_libraries="riscv64.so" ;;
    *) printf 'Unsupported test case: %s:%s\n' "$test_arch" "$test_has32bit" >&2; exit 1 ;;
esac
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
ARCH=$test_arch
ui_print() { printf '%s\n' "$*"; }
abort() { printf '%s\n' "$*" >&2; exit 1; }
grep_prop() { sed -n "s/^$1=//p" "$2"; }
getprop() {
    if [ "$test_has32bit" = true ]; then
        printf 'test-32-bit-abi\n'
    fi
}
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

for library in $expected_libraries; do
    if [ ! -s "$MODPATH/zygisk/$library" ]; then
        printf 'Missing installed native library: %s\n' "$library" >&2
        exit 1
    fi
done
for installed_library in "$MODPATH"/zygisk/*; do
    library=$(basename "$installed_library")
    case " $expected_libraries " in
        *" $library "*) ;;
        *) printf 'Unexpected installed library for %s: %s\n' "$ARCH" "$library" >&2; exit 1 ;;
    esac
done
printf '%s installation (32-bit=%s) exposes exactly: %s\n' "$ARCH" "$test_has32bit" "$expected_libraries"
