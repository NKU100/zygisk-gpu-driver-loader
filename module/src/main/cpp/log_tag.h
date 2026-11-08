#pragma once

#ifndef MODULE_LOG_TAG
#error "MODULE_LOG_TAG must be defined by the build from module.gradle.kts"
#endif

namespace gpu {
inline constexpr char LogTag[] = MODULE_LOG_TAG;
}
