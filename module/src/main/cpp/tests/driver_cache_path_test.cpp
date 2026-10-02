#include "../driver_cache_path.h"
#include <cassert>

int main() {
    const auto path = gpu::driverCacheComponent("abc:arm64-v8a:1234");
    assert(!path.empty() && path.find(':') == std::string::npos);
    assert(path != gpu::driverCacheComponent("abc_3aarm64-v8a_3a1234"));
    assert(gpu::driverCacheComponent("validation-driver") == "validation-driver");
    assert(gpu::driverCacheComponent("../bad").empty());
    assert(gpu::driverCacheComponent(".").empty());
    assert(gpu::driverCacheComponent(std::string(255, ':')).empty());
}
