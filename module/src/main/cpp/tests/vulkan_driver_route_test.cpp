#include "../vulkan_driver_route.h"
#include <cassert>
#include <cstring>

int main() {
    int customCalls = 0, systemCalls = 0;
    int customToken = 1, systemToken = 2;
    void *customResult = &customToken;
    auto custom = [&](const char *, int) { ++customCalls; return customResult; };
    auto system = [&](const char *, int) { ++systemCalls; return &systemToken; };
    auto identity = [&](void *handle) {
        return handle == &customToken ? gpu::DriverIdentity::Selected : gpu::DriverIdentity::Other;
    };

    auto loaded = gpu::routeVulkanDriver("vulkan.adreno.so", 2, custom, system, identity);
    assert(loaded.handle == &customToken);
    assert(loaded.status == gpu::DriverLoadStatus::Loaded);
    assert(customCalls == 1 && systemCalls == 0);

    customResult = &systemToken;
    auto internalFallback = gpu::routeVulkanDriver("vulkan.adreno.so", 2, custom, system, identity);
    assert(internalFallback.handle == &systemToken);
    assert(internalFallback.status == gpu::DriverLoadStatus::SystemFallback);
    assert(systemCalls == 0);

    int unknownToken = 3;
    customResult = &unknownToken;
    auto unverified = gpu::routeVulkanDriver("vulkan.adreno.so", 2, custom, system,
        [](void *) { return gpu::DriverIdentity::Unknown; });
    assert(unverified.handle == &systemToken);
    assert(unverified.status == gpu::DriverLoadStatus::SystemFallback);

    customResult = nullptr;
    auto fallback = gpu::routeVulkanDriver("vulkan.adreno.so", 2, custom, system, identity);
    assert(fallback.handle == &systemToken);
    assert(fallback.status == gpu::DriverLoadStatus::SystemFallback);
    assert(systemCalls == 2);

    auto failed = gpu::routeVulkanDriver("vulkan.adreno.so", 2, custom,
        [](const char *, int) -> void * { return nullptr; }, identity);
    assert(!failed.handle);
    assert(failed.status == gpu::DriverLoadStatus::AdrenotoolsFailed);

    int before = customCalls;
    auto passthrough = gpu::routeVulkanDriver("libother.so", 2, custom, system, identity);
    assert(passthrough.handle == &systemToken && customCalls == before);
    assert(passthrough.status == gpu::DriverLoadStatus::SystemFallback);
}
