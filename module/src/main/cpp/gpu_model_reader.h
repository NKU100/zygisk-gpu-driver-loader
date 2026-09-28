#pragma once

#include <cerrno>
#include <fcntl.h>
#include <string>
#include <string_view>
#include <unistd.h>

namespace gpu {

enum class GpuModelReadStatus { Unavailable, Readable, Invalid };
inline constexpr size_t MaxGpuModelBytes = 128;

inline GpuModelReadStatus readGpuModelFile(std::string_view path, std::string &model) {
    std::string filePath(path);
    int fd = open(filePath.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return GpuModelReadStatus::Unavailable;

    char buffer[MaxGpuModelBytes + 1];
    size_t length = 0;
    GpuModelReadStatus status = GpuModelReadStatus::Readable;
    while (length < sizeof(buffer)) {
        ssize_t count = read(fd, buffer + length, sizeof(buffer) - length);
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) {
            status = GpuModelReadStatus::Invalid;
            break;
        }
        if (count == 0) break;
        length += count;
    }
    if (close(fd) != 0 && status == GpuModelReadStatus::Readable) {
        status = GpuModelReadStatus::Invalid;
    }
    if (length > MaxGpuModelBytes) status = GpuModelReadStatus::Invalid;
    if (status == GpuModelReadStatus::Readable) model.assign(buffer, length);
    return status;
}

}
