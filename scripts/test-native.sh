#!/bin/sh
set -eu

repo_dir=$(cd "$(dirname "$0")/.." && pwd)
native_test_dir=$(mktemp -d /tmp/gpu-native-tests.XXXXXX)
trap 'rm -r "$native_test_dir"' EXIT
cd "$repo_dir"
tests=module/src/main/cpp/tests

c++ -std=c++20 "$tests/hwui_property_override_test.cpp" -o "$native_test_dir/hwui-property"
"$native_test_dir/hwui-property"

c++ -std=c++20 "$tests/driver_stage_failure_test.cpp" -o "$native_test_dir/stage-failure"
"$native_test_dir/stage-failure"
c++ -std=c++20 "$tests/path_component_test.cpp" -o "$native_test_dir/path-component"
"$native_test_dir/path-component"
c++ -std=c++20 "$tests/driver_device_eligibility_test.cpp" -o "$native_test_dir/eligibility"
"$native_test_dir/eligibility"
c++ -std=c++20 -pthread "$tests/companion_fd_transfer_test.cpp" module/src/main/cpp/companion_fd.cpp -o "$native_test_dir/companion"
"$native_test_dir/companion"
c++ -std=c++20 -pthread "$tests/runtime_log_test.cpp" -o "$native_test_dir/runtime-log"
"$native_test_dir/runtime-log"
awk '/^static ssize_t write_all\(/ { capture=1 } /^static constexpr off_t LOG_MAX_BYTES/ { capture=1 } /^static void companion_appendLog\(/ { capture=1 } capture { print } capture && /^}/ { capture=0 } /^static constexpr off_t LOG_TRIM_BYTES/ { capture=0 }' module/src/main/cpp/example.cpp > "$native_test_dir/example_log_fixture.h"
c++ -std=c++20 -pthread -I"$native_test_dir" "$tests/persistent_log_test.cpp" -o "$native_test_dir/persistent-log"
"$native_test_dir/persistent-log"
c++ -std=c++20 "$tests/driver_bundle_test.cpp" -o "$native_test_dir/bundle"
"$native_test_dir/bundle"
c++ -std=c++20 "$tests/driver_cache_path_test.cpp" -o "$native_test_dir/cache-path"
"$native_test_dir/cache-path"
c++ -std=c++20 "$tests/vulkan_driver_route_test.cpp" -o "$native_test_dir/route"
"$native_test_dir/route"
c++ -shared -fPIC "$tests/hal_identity_fixture.cpp" -o "$native_test_dir/custom.so"
c++ -shared -fPIC "$tests/hal_identity_fixture.cpp" -o "$native_test_dir/stock.so"
dl_library=
if [ "$(uname)" != Darwin ]; then dl_library=-ldl; fi
c++ -std=c++20 "$tests/driver_identity_test.cpp" $dl_library -o "$native_test_dir/identity"
"$native_test_dir/identity" "$native_test_dir/custom.so" "$native_test_dir/stock.so"
printf 'Native eligibility, companion IPC, routing and loaded-file identity tests passed\n'
