#pragma once

#include "driver_loader.h"
#include <string_view>

namespace gpu {
enum class DriverIdentity { Selected, Other, Unknown };

struct RoutedDriver {
    void *handle;
    DriverLoadStatus status;
};

template <typename Custom, typename System, typename Identify>
RoutedDriver routeVulkanDriver(const char *filename, int flags, Custom &&custom,
                              System &&system, Identify &&identify) {
    if (!filename || !std::string_view(filename).starts_with("vulkan.")) {
        return {system(filename, flags), DriverLoadStatus::SystemFallback};
    }
    if (void *handle = custom(filename, flags)) {
        switch (identify(handle)) {
            case DriverIdentity::Selected: return {handle, DriverLoadStatus::Loaded};
            case DriverIdentity::Other: return {handle, DriverLoadStatus::SystemFallback};
            case DriverIdentity::Unknown: break;
        }
    }
    void *handle = system(filename, flags);
    return {handle, handle ? DriverLoadStatus::SystemFallback : DriverLoadStatus::AdrenotoolsFailed};
}
}
