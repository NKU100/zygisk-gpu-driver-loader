#pragma once

#include "vulkan_driver_route.h"
#include <dlfcn.h>
#include <sys/stat.h>

namespace gpu {
inline DriverIdentity identifyLoadedDriver(void *handle, dev_t device, ino_t inode) {
    void *entry = dlsym(handle, "vkGetInstanceProcAddr");
    if (!entry) entry = dlsym(handle, "vk_icdGetInstanceProcAddr");
    if (!entry) entry = dlsym(handle, "HMI");
    Dl_info info{};
    struct stat mapped{};
    if (!entry || !dladdr(entry, &info) || !info.dli_fname || stat(info.dli_fname, &mapped)) {
        return DriverIdentity::Unknown;
    }
    return mapped.st_dev == device && mapped.st_ino == inode
        ? DriverIdentity::Selected : DriverIdentity::Other;
}
}
