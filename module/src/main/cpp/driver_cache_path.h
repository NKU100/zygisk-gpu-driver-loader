#pragma once

#include <string>

namespace gpu {
inline std::string driverCacheComponent(const std::string &id) {
    if (id.empty() || id == "." || id == "..") return {};
    std::string component;
    for (unsigned char byte : id) {
        if (byte == ':' || byte == '_') {
            static constexpr char hex[] = "0123456789abcdef";
            component += '_';
            component += hex[byte >> 4];
            component += hex[byte & 15];
        } else if ((byte >= 'a' && byte <= 'z') || (byte >= 'A' && byte <= 'Z') ||
                   (byte >= '0' && byte <= '9') || byte == '-' || byte == '.') {
            component += static_cast<char>(byte);
        } else {
            return {};
        }
        if (component.size() > 255) return {};
    }
    return component;
}
}
