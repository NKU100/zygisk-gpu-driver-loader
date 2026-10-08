#pragma once

#include <string>

namespace gpu {
inline bool safePathComponent(const std::string &name) {
    if (name.empty() || name.size() > 255 || name == "." || name == "..") return false;
    for (unsigned char byte : name) {
        if (!((byte >= 'a' && byte <= 'z') || (byte >= 'A' && byte <= 'Z') ||
              (byte >= '0' && byte <= '9') || byte == '.' || byte == '_' ||
              byte == '-' || byte == ':')) return false;
    }
    return true;
}

inline bool safeFileName(const std::string &name) {
    if (name.empty() || name.size() > 255 || name == "." || name == "..") return false;
    for (unsigned char byte : name) {
        if (!((byte >= 'a' && byte <= 'z') || (byte >= 'A' && byte <= 'Z') ||
              (byte >= '0' && byte <= '9') || byte == '.' || byte == '_' ||
              byte == '-' || byte == ':' || byte == '@')) return false;
    }
    return true;
}
}
