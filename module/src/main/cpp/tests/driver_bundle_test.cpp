#include "../driver_bundle.h"
#include <cassert>
#include <fcntl.h>
#include <unistd.h>
#include <algorithm>

static bool validLibrary(int fd) {
    char bytes[4];
    return pread(fd, bytes, sizeof(bytes), 0) == 4 && memcmp(bytes, "ELF!", 4) == 0;
}

static void file(int directory, const char *name, const char *contents) {
    int fd = openat(directory, name, O_WRONLY | O_CREAT | O_EXCL, 0600);
    assert(fd >= 0);
    assert(write(fd, contents, strlen(contents)) == static_cast<ssize_t>(strlen(contents)));
    close(fd);
}

int main() {
    char path[] = "/tmp/gpu-driver-bundle.XXXXXX";
    assert(mkdtemp(path));
    int directory = open(path, O_RDONLY | O_DIRECTORY);
    assert(directory >= 0);
    file(directory, "vulkan.so", "ELF!driver");
    file(directory, "notgsl.so", "ELF!dependency");
    file(directory, "README", "ignored");
    std::vector<gpu::BundleLibrary> libraries;
    assert(gpu::readDriverBundle(directory, "vulkan.so", validLibrary, libraries));
    assert(libraries.size() == 2);
    assert(libraries[0].name == "notgsl.so");
    assert(libraries[1].name == "vulkan.so");
    char bytes[14];
    assert(pread(libraries[0].fd, bytes, 14, 0) == 14);
    assert(memcmp(bytes, "ELF!dependency", 14) == 0);
    libraries.clear();
    assert(unlinkat(directory, "README", 0) == 0);
    file(directory, "meta.json", "metadata");
    const std::vector<std::string> expected{"meta.json", "vulkan.so", "notgsl.so"};
    assert(gpu::bundleCacheMatches(directory, expected));
    file(directory, "injected.so", "ELF!unexpected");
    assert(!gpu::bundleCacheMatches(directory, expected));
    assert(unlinkat(directory, "injected.so", 0) == 0);
    assert(gpu::bundleCacheMatches(directory, expected));
    assert(symlinkat("vulkan.so", directory, "unsafe.so") == 0);
    assert(!gpu::readDriverBundle(directory, "vulkan.so", validLibrary, libraries));
    unlinkat(directory, "unsafe.so", 0);
    file(directory, "wrong.so", "not ELF");
    assert(!gpu::readDriverBundle(directory, "vulkan.so", validLibrary, libraries));
    unlinkat(directory, "wrong.so", 0);
    assert(!gpu::readDriverBundle(directory, "missing.so", validLibrary, libraries));
    unlinkat(directory, "vulkan.so", 0);
    unlinkat(directory, "notgsl.so", 0);
    unlinkat(directory, "README", 0);
    unlinkat(directory, "meta.json", 0);
    close(directory);
    assert(rmdir(path) == 0);
}
