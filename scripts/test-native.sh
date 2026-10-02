#!/bin/sh
set -eu

repo_dir=$(cd "$(dirname "$0")/.." && pwd)
native_test_dir=$(mktemp -d /tmp/gpu-native-tests.XXXXXX)
trap 'rm -r "$native_test_dir"' EXIT
cd "$repo_dir"
tests=module/src/main/cpp/tests

c++ -std=c++20 "$tests/driver_device_eligibility_test.cpp" -o "$native_test_dir/eligibility"
"$native_test_dir/eligibility"
c++ -std=c++20 -pthread "$tests/companion_fd_transfer_test.cpp" module/src/main/cpp/companion_fd.cpp -o "$native_test_dir/companion"
"$native_test_dir/companion"
c++ -std=c++20 -pthread "$tests/runtime_log_test.cpp" -o "$native_test_dir/runtime-log"
"$native_test_dir/runtime-log"
c++ -std=c++20 "$tests/vulkan_driver_route_test.cpp" -o "$native_test_dir/route"
"$native_test_dir/route"
c++ -shared -fPIC "$tests/hal_identity_fixture.cpp" -o "$native_test_dir/custom.so"
c++ -shared -fPIC "$tests/hal_identity_fixture.cpp" -o "$native_test_dir/stock.so"
dl_library=
if [ "$(uname)" != Darwin ]; then dl_library=-ldl; fi
c++ -std=c++20 "$tests/driver_identity_test.cpp" $dl_library -o "$native_test_dir/identity"
"$native_test_dir/identity" "$native_test_dir/custom.so" "$native_test_dir/stock.so"
printf 'Native eligibility, companion IPC, routing and loaded-file identity tests passed\n'
