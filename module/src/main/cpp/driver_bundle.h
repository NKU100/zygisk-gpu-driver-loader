#pragma once

#include <algorithm>
#include <cerrno>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <utility>
#include <vector>

namespace gpu {
inline bool bundleCacheMatches(int directory, const std::vector<std::string> &expected) {
    int scan = openat(directory, ".", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (scan < 0) return false;
    DIR *entries = fdopendir(scan);
    if (!entries) { close(scan); return false; }
    size_t count = 0;
    bool matches = true;
    while (matches) {
        errno = 0;
        dirent *entry = readdir(entries);
        if (!entry) { matches = errno == 0; break; }
        std::string name(entry->d_name);
        if (name == "." || name == "..") continue;
        if (std::find(expected.begin(), expected.end(), name) == expected.end()) { matches = false; break; }
        ++count;
    }
    closedir(entries);
    return matches && count == expected.size();
}

struct BundleLibrary {
    std::string name;
    int fd;
    BundleLibrary(std::string name, int fd) : name(std::move(name)), fd(fd) {}
    BundleLibrary(BundleLibrary &&other) noexcept : name(std::move(other.name)), fd(std::exchange(other.fd, -1)) {}
    BundleLibrary &operator=(BundleLibrary &&other) noexcept {
        if (this != &other) {
            if (fd >= 0) close(fd);
            name = std::move(other.name);
            fd = std::exchange(other.fd, -1);
        }
        return *this;
    }
    ~BundleLibrary() { if (fd >= 0) close(fd); }
};

inline bool readDriverBundle(int directory, const std::string &mainName, bool (*validate)(int),
                             std::vector<BundleLibrary> &libraries) {
    libraries.clear();
    int scan = openat(directory, ".", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (scan < 0) return false;
    DIR *entries = fdopendir(scan);
    if (!entries) { close(scan); return false; }
    bool valid = true, mainFound = false;
    off_t total = 0;
    while (valid) {
        errno = 0;
        dirent *entry = readdir(entries);
        if (!entry) { valid = errno == 0; break; }
        std::string name(entry->d_name);
        if (name != mainName && !name.ends_with(".so")) continue;
        if (libraries.size() >= 128 || name.find('\\') != std::string::npos) { valid = false; break; }
        int fd = openat(directory, name.c_str(), O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC);
        struct stat info{};
        if (fd < 0 || fstat(fd, &info) || !S_ISREG(info.st_mode) || info.st_nlink != 1 ||
            info.st_uid != geteuid() || info.st_size <= 0 || info.st_size > 512LL * 1024 * 1024 - total ||
            !validate(fd)) {
            if (fd >= 0) close(fd);
            valid = false;
            break;
        }
        total += info.st_size;
        mainFound = mainFound || name == mainName;
        libraries.emplace_back(std::move(name), fd);
    }
    closedir(entries);
    if (!valid || !mainFound) { libraries.clear(); return false; }
    std::sort(libraries.begin(), libraries.end(), [](const auto &a, const auto &b) { return a.name < b.name; });
    return true;
}
}
