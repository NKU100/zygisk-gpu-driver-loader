#include "driver_loader.h"
#include "gpu_model_reader.h"
#include "zygisk.hpp"
#include "yyjson.h"
#include "companion_fd.h"
#include "vulkan_driver_route.h"
#include "driver_identity.h"
#include "driver_bundle.h"
#include "driver_cache_path.h"
#include "adrenotools/driver.h"
#if defined(__aarch64__)
#include "android_linker_ns.h"
#include "hook_impl.h"
#include "hook_impl_params.h"
#endif

#include <android/api-level.h>
#include <android/log.h>
#include <cerrno>
#include <cstring>
#include <dlfcn.h>
#include <cstdlib>
#include <cstdio>
#include <cstdint>
#include <elf.h>
#include <fcntl.h>
#include <limits.h>
#include <mutex>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

extern "C" void gpu_initialize_linker_symbols();

#ifndef MODULE_ID
#define MODULE_ID "zygisk_sample"
#endif

namespace gpu {
static void (*runtimeLogSink)(int, const char *) = nullptr;
void setRuntimeLogSink(void (*sink)(int, const char *)) { runtimeLogSink = sink; }
namespace {
constexpr off_t MaxLibraryBytes = 512 * 1024 * 1024;
constexpr size_t MaxMetadataBytes = 64 * 1024;

#if defined(__aarch64__)
using LoadSphalLibrary = void *(*)(const char *, int);
LoadSphalLibrary originalLoadSphalLibrary = nullptr;
struct stat selectedDriverIdentity{};
std::string selectedDriverPath;

DriverIdentity identifyDriver(void *handle) {
    return identifyLoadedDriver(handle, selectedDriverIdentity.st_dev, selectedDriverIdentity.st_ino);
}

void *loadSphalLibrary(const char *filename, int flags) {
    auto routed = routeVulkanDriver(filename, flags, hook_android_load_sphal_library,
        [](const char *name, int mode) -> void * {
            return originalLoadSphalLibrary ? originalLoadSphalLibrary(name, mode) : nullptr;
        }, identifyDriver);
    const int priority = routed.status == DriverLoadStatus::Loaded ? ANDROID_LOG_INFO : ANDROID_LOG_WARN;
    char message[2048];
    snprintf(message, sizeof(message), "driver request=%s status=%s selectedPath=%s handle=%p",
        filename ? filename : "<null>", statusName(routed.status), selectedDriverPath.c_str(), routed.handle);
    if (runtimeLogSink) runtimeLogSink(priority, message);
    else __android_log_print(priority, "ZygiskWebUI", "%s", message);
    return routed.handle;
}
#endif

struct Fd {
    int value;
    explicit Fd(int value = -1) : value(value) {}
    ~Fd() { if (value >= 0) close(value); }
    Fd(const Fd &) = delete;
    Fd &operator=(const Fd &) = delete;
};

struct Json {
    yyjson_doc *doc;
    explicit Json(const std::string &text) : doc(yyjson_read(text.data(), text.size(), 0)) {}
    ~Json() { if (doc) yyjson_doc_free(doc); }
    yyjson_val *root() const { return doc ? yyjson_doc_get_root(doc) : nullptr; }
};

std::string stringValue(yyjson_val *value) {
    return yyjson_is_str(value) ? std::string(yyjson_get_str(value), yyjson_get_len(value)) : "";
}

bool component(const std::string &name) {
    if (name.empty() || name.size() > NAME_MAX || name == "." || name == "..") return false;
    for (unsigned char c : name) {
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
              (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-' || c == ':')) return false;
    }
    return true;
}

int openDirectory(int parent, const std::string &name) {
    return openat(parent, name.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
}

int openAbsoluteDirectory(const std::string &path) {
    if (path.empty() || path[0] != '/') return -1;
    int fd = open("/", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    size_t start = 1;
    while (fd >= 0 && start < path.size()) {
        size_t end = path.find('/', start);
        if (end == std::string::npos) end = path.size();
        std::string part = path.substr(start, end - start);
        int next = component(part) ? openDirectory(fd, part) : -1;
        close(fd);
        fd = next;
        start = end + 1;
    }
    return fd;
}

int openSafeFile(int directory, const std::string &name, off_t limit) {
    if (!component(name)) {
        errno = EINVAL;
        return -1;
    }
    Fd fd(openat(directory, name.c_str(), O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC));
    struct stat st{};
    if (fd.value < 0) return -1;
    if (fstat(fd.value, &st)) return -1;
    if (!S_ISREG(st.st_mode) || st.st_nlink != 1 || st.st_size <= 0 || st.st_size > limit) {
        errno = EINVAL;
        return -1;
    }
    int copy = dup(fd.value);
    if (copy < 0) return -1;
    return copy;
}

#if defined(__aarch64__)
bool validReceivedFile(int fd, off_t limit) {
    struct stat st{};
    return fd >= 0 && fstat(fd, &st) == 0 && S_ISREG(st.st_mode) &&
        st.st_nlink == 0 && st.st_size > 0 && st.st_size <= limit &&
        fcntl(fd, F_GET_SEALS) == (F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL);
}

int regularFile(int parent, const std::string &name, off_t limit) {
    if (!component(name)) return -1;
    Fd fd(openat(parent, name.c_str(), O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC));
    struct stat st{};
    if (fd.value < 0 || fstat(fd.value, &st) || !S_ISREG(st.st_mode) ||
        st.st_nlink != 1 || st.st_size <= 0 || st.st_size > limit) return -1;
    return dup(fd.value);
}

bool readText(int fd, std::string &text, size_t limit = MaxMetadataBytes) {
    struct stat st{};
    if (fstat(fd, &st) || st.st_size <= 0 || static_cast<uint64_t>(st.st_size) > limit) return false;
    text.resize(st.st_size);
    size_t offset = 0;
    while (offset < text.size()) {
        ssize_t n = pread(fd, text.data() + offset, text.size() - offset, offset);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        offset += n;
    }
    return true;
}

bool arm64Library(int fd) {
    Elf64_Ehdr header{};
    struct stat st{};
    return fstat(fd, &st) == 0 && pread(fd, &header, sizeof(header), 0) == sizeof(header) &&
        memcmp(header.e_ident, ELFMAG, SELFMAG) == 0 &&
        header.e_ident[EI_CLASS] == ELFCLASS64 && header.e_ident[EI_DATA] == ELFDATA2LSB &&
        header.e_ident[EI_VERSION] == EV_CURRENT && header.e_version == EV_CURRENT &&
        header.e_machine == EM_AARCH64 && header.e_type == ET_DYN &&
        header.e_ehsize == sizeof(header) && header.e_phentsize == sizeof(Elf64_Phdr) &&
        header.e_phnum > 0 && header.e_phoff >= sizeof(header) &&
        header.e_phoff <= static_cast<uint64_t>(st.st_size) &&
        header.e_phnum <= (st.st_size - header.e_phoff) / sizeof(Elf64_Phdr);
}

int requestDriverFile(zygisk::Api *api, const std::string &driverId,
                      const std::string &fileName, uint8_t kind) {
    Fd socket(api->connectCompanion());
    if (socket.value < 0) {
        __android_log_print(ANDROID_LOG_WARN, "ZygiskWebUI",
                            "driver IPC stage=connect_companion failed errno=%d", errno);
        return -1;
    }
    timeval timeout{2, 0};
    if (setsockopt(socket.value, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) ||
        setsockopt(socket.value, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout))) {
        __android_log_print(ANDROID_LOG_WARN, "ZygiskWebUI",
                            "driver IPC stage=socket_timeout_setup failed errno=%d", errno);
        return -1;
    }
    companion_fd::FileRequest request{OpenDriverOpcode, kind, driverId, fileName};
    if (!companion_fd::sendFileRequest(socket.value, request)) {
        __android_log_print(ANDROID_LOG_WARN, "ZygiskWebUI",
                            "driver IPC stage=file_request_send failed errno=%d", errno);
        return -1;
    }
    Fd snapshot(static_cast<int>(syscall(SYS_memfd_create, "gpu-driver-snapshot", 3 /* CLOEXEC | ALLOW_SEALING */)));
    if (snapshot.value < 0 || !companion_fd::sendFileReply(socket.value, snapshot.value)) return -1;
    int file = companion_fd::receiveFileReply(socket.value);
    if (file < 0) {
        __android_log_print(ANDROID_LOG_WARN, "ZygiskWebUI",
                            "driver IPC stage=file_fd_reply_receive failed errno=%d", errno);
    }
    if (file >= 0 && fcntl(file, F_ADD_SEALS, F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL)) {
        close(file);
        return -1;
    }
    return file;
}

int privateDirectory(int parent, const std::string &name, uid_t uid, gid_t gid, bool appFiles = false) {
    bool created = mkdirat(parent, name.c_str(), 0700) == 0;
    if (!created && errno != EEXIST) return -1;
    Fd fd(openDirectory(parent, name));
    struct stat st{};
    if (fd.value < 0 || fstat(fd.value, &st)) return -1;
    if (created && st.st_uid != geteuid()) return -1;
    if (created && (fchown(fd.value, uid, gid) || fchmod(fd.value, 0700))) return -1;
    if (fstat(fd.value, &st)) return -1;
    const bool allowed = appFiles
        ? appFilesDirectoryMetadataAllowed(st.st_uid, st.st_gid, st.st_mode, uid, gid)
        : privateDirectoryMetadataAllowed(st.st_uid, st.st_gid, st.st_mode, uid, gid);
    if (!allowed) return -1;
    return dup(fd.value);
}

mode_t expectedMode(StagedFileKind kind) {
    return kind == StagedFileKind::NativeLibrary ? 0500 : 0400;
}

bool copyFile(int source, int directory, const std::string &name, uid_t uid, gid_t gid,
              StagedFileKind kind) {
    Fd target(openat(directory, name.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600));
    if (target.value < 0) return false;
    struct stat st{};
    if (fstat(source, &st)) return false;
    char buffer[16384];
    off_t offset = 0;
    while (offset < st.st_size) {
        ssize_t n = pread(source, buffer, sizeof(buffer), offset);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0 || n > st.st_size - offset) return false;
        ssize_t written = 0;
        while (written < n) {
            ssize_t count = write(target.value, buffer + written, n - written);
            if (count < 0 && errno == EINTR) continue;
            if (count <= 0) return false;
            written += count;
        }
        offset += n;
    }
    if (fchown(target.value, uid, gid) || fchmod(target.value, expectedMode(kind)) || fsync(target.value)) return false;
    struct stat staged{};
    return fstat(target.value, &staged) == 0 && staged.st_uid == uid && staged.st_gid == gid &&
        !(staged.st_mode & 0022) && stagedFileModeAllowed(kind, staged.st_mode);
}

bool sameFile(int source, int directory, const std::string &name, uid_t uid, gid_t gid,
              StagedFileKind kind) {
    Fd target(regularFile(directory, name, kind == StagedFileKind::Metadata ? MaxMetadataBytes : MaxLibraryBytes));
    struct stat a{}, b{};
    if (target.value < 0 || fstat(source, &a) || fstat(target.value, &b) ||
        a.st_size != b.st_size || b.st_uid != uid || b.st_gid != gid || (b.st_mode & 0022) ||
        !stagedFileModeAllowed(kind, b.st_mode)) return false;
    char left[16384], right[16384];
    for (off_t offset = 0; offset < a.st_size;) {
        ssize_t n = pread(source, left, sizeof(left), offset);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0 || pread(target.value, right, n, offset) != n || memcmp(left, right, n)) return false;
        offset += n;
    }
    return true;
}

bool stageDriver(const std::string &data, const DriverSelection &selection, uid_t uid, gid_t gid,
                 int metadata, const std::vector<BundleLibrary> &libraries, std::string &path, std::string &basePath) {
    // Android may alias /data/user/0 to /data/data; resolve only the Zygisk-provided base.
    char canonical[PATH_MAX];
    if (!appDataPathAllowed(data, selection.packageName, uid)) return false;
    if (!realpath(data.c_str(), canonical)) {
        // Direct Boot can launch apps before credential-encrypted storage is mounted.
        std::string deviceProtected = "/data/user_de/" + std::to_string(uid / 100000) + "/" + selection.packageName;
        if (!realpath(deviceProtected.c_str(), canonical)) return false;
    }
    std::string base(canonical);
    basePath = base;
    if (!appDataPathAllowed(base, selection.packageName, uid)) return false;
    Fd app(openAbsoluteDirectory(base));
    struct stat st{};
    if (app.value < 0 || fstat(app.value, &st) || st.st_uid != uid) return false;
    // Android-owned files directories can be 0771; module-owned descendants must remain 0700.
    Fd files(privateDirectory(app.value, "files", uid, gid, true));
    Fd module(privateDirectory(files.value, MODULE_ID, uid, gid));
    Fd parent(privateDirectory(module.value, "gpu-driver", uid, gid));
    if (parent.value < 0) return false;
    const std::string cache = driverCacheComponent(selection.driverId);
    if (cache.empty()) return false;
    path = base + "/files/" MODULE_ID "/gpu-driver/" + cache + "/";
    std::vector<std::string> expectedFiles{"meta.json"};
    for (const auto &library : libraries) expectedFiles.push_back(library.name);
    auto matches = [&](int directory) {
        if (!bundleCacheMatches(directory, expectedFiles)) return false;
        if (!sameFile(metadata, directory, "meta.json", uid, gid, StagedFileKind::Metadata)) return false;
        for (const auto &library : libraries) {
            if (!sameFile(library.fd, directory, library.name, uid, gid, StagedFileKind::NativeLibrary)) return false;
        }
        return true;
    };
    Fd existing(openDirectory(parent.value, cache));
    if (existing.value >= 0) {
        return fstat(existing.value, &st) == 0 && st.st_uid == uid && st.st_gid == gid &&
            (st.st_mode & 07777) == 0700 &&
            matches(existing.value);
    }
    std::string temporary = ".stage-" + std::to_string(getpid());
    if (mkdirat(parent.value, temporary.c_str(), 0700)) return false;
    Fd staging(openDirectory(parent.value, temporary));
    if (staging.value < 0 || fchmod(staging.value, 0700) || fstat(staging.value, &st) ||
        st.st_uid != geteuid() || (st.st_mode & 0077)) return false;
    bool ready = copyFile(metadata, staging.value, "meta.json", uid, gid, StagedFileKind::Metadata);
    for (const auto &library : libraries) {
        ready = ready && copyFile(library.fd, staging.value, library.name, uid, gid, StagedFileKind::NativeLibrary);
    }
    ready = ready && fsync(staging.value) == 0;
    if (!ready) {
        unlinkat(staging.value, "meta.json", 0);
        for (const auto &library : libraries) unlinkat(staging.value, library.name.c_str(), 0);
        unlinkat(parent.value, temporary.c_str(), AT_REMOVEDIR);
        return false;
    }
    // Never replace an app-controlled destination or publish into a concurrently created directory.
    if (fchown(staging.value, uid, gid)) return false;
    if (syscall(SYS_renameat2, parent.value, temporary.c_str(), parent.value,
                cache.c_str(), 1 /* RENAME_NOREPLACE */) == 0) {
        Fd published(openDirectory(parent.value, cache));
        struct stat actual{}, expected{};
        return published.value >= 0 && fstat(published.value, &actual) == 0 && actual.st_uid == uid &&
            actual.st_gid == gid && (actual.st_mode & 07777) == 0700 &&
            fstat(staging.value, &expected) == 0 && actual.st_dev == expected.st_dev &&
            actual.st_ino == expected.st_ino && fsync(parent.value) == 0 &&
            matches(published.value);
    }
    // Once app-owned, leave the temporary directory untouched on failure.
    return false;
}

uint64_t contentHash(int first, int second) {
    uint64_t hash = 14695981039346656037ull;
    char buffer[16384];
    int sources[] = {first, second};
    for (int fd : sources) {
        struct stat st{};
        if (fstat(fd, &st)) return 0;
        for (off_t offset = 0; offset < st.st_size;) {
            ssize_t count = pread(fd, buffer, sizeof(buffer), offset);
            if (count < 0 && errno == EINTR) continue;
            if (count <= 0) return 0;
            for (ssize_t index = 0; index < count; ++index) {
                hash = (hash ^ static_cast<unsigned char>(buffer[index])) * 1099511628211ull;
            }
            offset += count;
        }
        hash = (hash ^ static_cast<uint64_t>(st.st_size)) * 1099511628211ull;
    }
    return hash;
}

bool stageHookSet(int module, int first, int second, const std::string &base, uid_t uid, gid_t gid,
                  std::string &path) {
    Fd files(privateDirectory(module, "hooks", uid, gid));
    uint64_t hash = contentHash(first, second);
    struct stat firstStat{}, secondStat{};
    if (files.value < 0 || !hash || fstat(first, &firstStat) || fstat(second, &secondStat)) return false;
    char tag[64];
    snprintf(tag, sizeof(tag), "v1-%016llx-%llx-%llx", static_cast<unsigned long long>(hash),
             static_cast<unsigned long long>(firstStat.st_size),
             static_cast<unsigned long long>(secondStat.st_size));
    std::string directoryName(tag);
    path = base + "/files/" MODULE_ID "/hooks/" + directoryName + "/";
    auto matches = [&](int directory) {
        struct stat directoryStat{};
        return fstat(directory, &directoryStat) == 0 && directoryStat.st_uid == uid &&
            directoryStat.st_gid == gid && (directoryStat.st_mode & 07777) == 0700 &&
            sameFile(first, directory, "libhook_impl.so", uid, gid, StagedFileKind::NativeLibrary) &&
            sameFile(second, directory, "libmain_hook.so", uid, gid, StagedFileKind::NativeLibrary);
    };
    Fd existing(openDirectory(files.value, directoryName));
    if (existing.value >= 0) return matches(existing.value);
    std::string temporary = ".stage-" + std::to_string(getpid());
    if (mkdirat(files.value, temporary.c_str(), 0700)) return false;
    Fd staging(openDirectory(files.value, temporary));
    struct stat stagingStat{};
    bool ready = staging.value >= 0 && fchmod(staging.value, 0700) == 0 &&
        fstat(staging.value, &stagingStat) == 0 && stagingStat.st_uid == geteuid() &&
        (stagingStat.st_mode & 07777) == 0700 &&
        copyFile(first, staging.value, "libhook_impl.so", uid, gid, StagedFileKind::NativeLibrary) &&
        copyFile(second, staging.value, "libmain_hook.so", uid, gid, StagedFileKind::NativeLibrary) &&
        fsync(staging.value) == 0 && fchown(staging.value, uid, gid) == 0;
    if (!ready) {
        if (staging.value >= 0) {
            unlinkat(staging.value, "libhook_impl.so", 0);
            unlinkat(staging.value, "libmain_hook.so", 0);
        }
        unlinkat(files.value, temporary.c_str(), AT_REMOVEDIR);
        return false;
    }
    if (syscall(SYS_renameat2, files.value, temporary.c_str(), files.value,
                directoryName.c_str(), 1 /* RENAME_NOREPLACE */) != 0) return false;
    Fd published(openDirectory(files.value, directoryName));
    struct stat actual{}, expected{};
    return published.value >= 0 && fstat(published.value, &actual) == 0 &&
        fstat(staging.value, &expected) == 0 && actual.st_dev == expected.st_dev &&
        actual.st_ino == expected.st_ino && fsync(files.value) == 0 && matches(published.value);
}
#endif

DriverLoadResult prepareDriver([[maybe_unused]] zygisk::Api *api, [[maybe_unused]] const DriverSelection &selection,
                               [[maybe_unused]] const std::string &appDataDir, [[maybe_unused]] uid_t uid,
                               [[maybe_unused]] gid_t gid, std::string &libraryName,
                               std::string &driverDirectory, std::string &hookDirectory) {
    DriverLoadResult result{DriverLoadStatus::InvalidDriver, "unsupported ABI (requires arm64-v8a)", {}, {}};
#if defined(__aarch64__)
    if (android_get_device_api_level() < 28) {
        result.reason = "Android 9 or newer required";
        return result;
    }
    std::string gpuModel;
    GpuModelReadStatus modelStatus = readGpuModelFile("/sys/kernel/gpu/gpu_model", gpuModel);
    if (modelStatus != GpuModelReadStatus::Readable || gpuModel.empty()) {
        gpuModel.clear();
        modelStatus = readGpuModelFile("/sys/class/kgsl/kgsl-3d0/gpu_model", gpuModel);
    }
    int registryFd = -1;
    if (modelStatus != GpuModelReadStatus::Readable || !withAdrenoGpuModel(gpuModel, [&] {
            registryFd = requestDriverFile(api, selection.driverId, "meta.json", companion_fd::MetadataFile);
        })) {
        result.status = DriverLoadStatus::UnsupportedDevice;
        result.reason = modelStatus != GpuModelReadStatus::Readable || gpuModel.empty()
            ? "KGSL GPU model unavailable, empty, or invalid; custom driver skipped"
            : "KGSL GPU model did not identify Adreno; custom driver skipped";
        return result;
    }
    Fd meta(registryFd);
    std::string text;
    if (meta.value < 0) {
        result.reason = "companion failed to open or transfer registry metadata file";
        return result;
    }
    if (!validReceivedFile(meta.value, MaxMetadataBytes)) {
        __android_log_print(ANDROID_LOG_WARN, "ZygiskWebUI",
                            "driver receive stage=metadata_file_validation failed");
        result.reason = "received registry metadata file failed validation";
        return result;
    }
    if (!readText(meta.value, text)) {
        __android_log_print(ANDROID_LOG_WARN, "ZygiskWebUI",
                            "driver receive stage=metadata_read failed errno=%d", errno);
        result.reason = "received registry metadata file could not be read";
        return result;
    }
    Json doc(text);
    std::string name = stringValue(yyjson_obj_get(doc.root(), "libraryName"));
    std::string abi = stringValue(yyjson_obj_get(doc.root(), "abi"));
    if (!component(name) || name == "meta.json" || (!abi.empty() && abi != "arm64-v8a")) {
        result.reason = "invalid libraryName or metadata ABI";
        return result;
    }
    Fd library(requestDriverFile(api, selection.driverId, name, companion_fd::LibraryFile));
    if (library.value < 0) {
        result.reason = "companion failed to open or transfer selected library file";
        return result;
    }
    if (!validReceivedFile(library.value, MaxLibraryBytes) || !arm64Library(library.value)) {
        __android_log_print(ANDROID_LOG_WARN, "ZygiskWebUI",
                            "driver receive stage=library_file_validation failed");
        result.reason = "received custom driver failed file or arm64 ELF validation";
        return result;
    }
    __android_log_print(ANDROID_LOG_INFO, "ZygiskWebUI", "custom driver file found: %s/%s",
                        selection.driverId.c_str(), name.c_str());
    Fd stageSocket(api->connectCompanion());
    const uint8_t opcode = PrepareDriverOpcode;
    std::string request = "{\"packageName\":\"" + selection.packageName + "\",\"driverId\":\"" +
        selection.driverId + "\",\"uid\":" + std::to_string(uid) + ",\"gid\":" + std::to_string(gid) + "}";
    std::string reply;
    timeval timeout{5, 0};
    if (stageSocket.value < 0 ||
        setsockopt(stageSocket.value, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) ||
        setsockopt(stageSocket.value, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout)) ||
        send(stageSocket.value, &opcode, 1, MSG_NOSIGNAL) != 1 ||
        !companion_fd::sendText(stageSocket.value, request, 16384) ||
        !companion_fd::receiveText(stageSocket.value, reply, 16384)) {
        result.reason = "root companion preparation request failed";
        return result;
    }
    Json response(reply);
    std::string error = stringValue(yyjson_obj_get(response.root(), "error"));
    if (!error.empty()) {
        result.reason = error;
        return result;
    }
    driverDirectory = stringValue(yyjson_obj_get(response.root(), "driverDirectory"));
    hookDirectory = stringValue(yyjson_obj_get(response.root(), "hookDirectory"));
    if (driverDirectory.empty() || hookDirectory.empty() ||
        stringValue(yyjson_obj_get(response.root(), "libraryName")) != name) {
        result.reason = "root companion returned incomplete preparation paths";
        return result;
    }
    result.driverPath = driverDirectory + name;
    result.status = DriverLoadStatus::HookPathUnavailable;
    gpu_initialize_linker_symbols();
    if (!linkernsbypass_load_status()) {
        result.reason = "deferred linker symbol initialization failed";
        return result;
    }
    libraryName = name;
    result.hookPath = hookDirectory;
    result.status = DriverLoadStatus::Prepared;
    result.reason = "driver and hook set staged and verified; linker symbols initialized; activation deferred";
#endif
    return result;
}
}

void serveDriverPreparation(int socket) {
    auto fail = [&](const char *reason) {
        __android_log_print(ANDROID_LOG_WARN, "ZygiskWebUI", "root preparation failed: %s errno=%d", reason, errno);
        companion_fd::sendText(socket, std::string("{\"error\":\"") + reason + "\"}", 16384);
    };
#if defined(__aarch64__)
    std::string request;
    if (!companion_fd::receiveText(socket, request, 16384)) { fail("invalid preparation request"); return; }
    Json input(request);
    std::string package = stringValue(yyjson_obj_get(input.root(), "packageName"));
    std::string id = stringValue(yyjson_obj_get(input.root(), "driverId"));
    auto *uidValue = yyjson_obj_get(input.root(), "uid");
    auto *gidValue = yyjson_obj_get(input.root(), "gid");
    if (!component(package) || package.find(':') != std::string::npos || !component(id) ||
        !yyjson_is_uint(uidValue) || !yyjson_is_uint(gidValue) || yyjson_get_uint(uidValue) > UINT32_MAX ||
        yyjson_get_uint(uidValue) < 10000 || yyjson_get_uint(uidValue) != yyjson_get_uint(gidValue)) {
        fail("invalid package or target identity"); return;
    }
    uid_t uid = static_cast<uid_t>(yyjson_get_uint(uidValue));
    gid_t gid = static_cast<gid_t>(yyjson_get_uint(gidValue));
    // A bounded lock pool prevents sibling processes from publishing into the same app cache concurrently.
    static std::mutex preparationLocks[64];
    std::lock_guard guard(preparationLocks[uid % 64]);
    Fd config(open("/data/adb/" MODULE_ID "/config.json", O_RDONLY | O_NOFOLLOW | O_CLOEXEC));
    std::string configText;
    if (config.value < 0 || !readText(config.value, configText, MaxConfigBytes) ||
        selectDriver(configText, package).driverId != id) {
        fail("package driver binding changed or is not authorized"); return;
    }
    Fd registry(openAbsoluteDirectory("/data/adb/" MODULE_ID "/drivers"));
    Fd driver(openDirectory(registry.value, id));
    Fd metadata(openSafeFile(driver.value, "meta.json", MaxMetadataBytes));
    std::string text;
    if (metadata.value < 0 || !readText(metadata.value, text)) { fail("invalid driver metadata"); return; }
    Json meta(text);
    std::string name = stringValue(yyjson_obj_get(meta.root(), "libraryName"));
    std::string abi = stringValue(yyjson_obj_get(meta.root(), "abi"));
    if (!component(name) || name == "meta.json" || (!abi.empty() && abi != "arm64-v8a")) {
        fail("invalid driver library name or ABI"); return;
    }
    Fd library(openSafeFile(driver.value, name, MaxLibraryBytes));
    if (library.value < 0 || !arm64Library(library.value)) { fail("invalid arm64 driver library"); return; }
    std::vector<BundleLibrary> libraries;
    if (!readDriverBundle(driver.value, name, arm64Library, libraries)) {
        fail("invalid arm64 driver bundle"); return;
    }
    DriverSelection selection{{DriverLoadStatus::InvalidDriver, {}, {}, {}}, package, id};
    std::string data = "/data/user/" + std::to_string(uid / 100000) + "/" + package;
    std::string directory, base, hookDirectory;
    if (!stageDriver(data, selection, uid, gid, metadata.value, libraries, directory, base)) {
        fail("root private driver staging failed"); return;
    }
    Fd sourceModule(openAbsoluteDirectory("/data/adb/modules/" MODULE_ID));
    Fd sourceHooks(openDirectory(sourceModule.value, "zygisk"));
    Fd implementation(regularFile(sourceHooks.value, "libhook_impl.so", MaxLibraryBytes));
    Fd main(regularFile(sourceHooks.value, "libmain_hook.so", MaxLibraryBytes));
    Fd targetModule(openAbsoluteDirectory(base + "/files/" MODULE_ID));
    if (implementation.value < 0 || main.value < 0 || !arm64Library(implementation.value) ||
        !arm64Library(main.value) || targetModule.value < 0 ||
        !stageHookSet(targetModule.value, implementation.value, main.value, base, uid, gid, hookDirectory)) {
        fail("root private hook staging failed"); return;
    }
    companion_fd::sendText(socket, "{\"driverDirectory\":\"" + directory +
        "\",\"hookDirectory\":\"" + hookDirectory + "\",\"libraryName\":\"" + name + "\"}", 16384);
#else
    fail("unsupported preparation ABI");
#endif
}

const char *statusName(DriverLoadStatus status) {
    switch (status) {
        case DriverLoadStatus::NotTargeted: return "NotTargeted";
        case DriverLoadStatus::NoBinding: return "NoBinding";
        case DriverLoadStatus::UnsupportedDevice: return "UnsupportedDevice";
        case DriverLoadStatus::InvalidDriver: return "InvalidDriver";
        case DriverLoadStatus::HookPathUnavailable: return "HookPathUnavailable";
        case DriverLoadStatus::AdrenotoolsFailed: return "AdrenotoolsFailed";
        case DriverLoadStatus::Loaded: return "Loaded";
        case DriverLoadStatus::Prepared: return "Prepared";
        case DriverLoadStatus::HookInstalled: return "HookInstalled";
        case DriverLoadStatus::SystemFallback: return "SystemFallback";
        case DriverLoadStatus::LoadUnverified: return "LoadUnverified";
    }
    return "InvalidDriver";
}

DriverSelection selectDriver(const std::string &config, const std::string &process) {
    DriverSelection selection{{DriverLoadStatus::NotTargeted, "package not targeted", {}, {}},
                              process.substr(0, process.find(':')), {}};
    if (process == "system_server" || !component(selection.packageName)) return selection;
    Json doc(config);
    yyjson_val *root = doc.root();
    if (!yyjson_is_obj(root)) {
        selection.result.reason = "configuration missing or invalid";
        return selection;
    }
    auto *enabled = yyjson_obj_get(root, "enabled");
    if (enabled && (!yyjson_is_bool(enabled) || !yyjson_get_bool(enabled))) {
        selection.result.reason = "module disabled or enabled flag invalid";
        return selection;
    }
    bool targeted = false;
    size_t idx, max;
    yyjson_val *entry;
    yyjson_arr_foreach(yyjson_obj_get(root, "targetPackages"), idx, max, entry) {
        if (stringValue(entry) == selection.packageName) targeted = true;
    }
    if (!targeted) return selection;
    auto *settings = yyjson_obj_get(yyjson_obj_get(root, "packageSettings"), selection.packageName.c_str());
    auto *binding = yyjson_obj_get(settings, "driverId");
    if (!binding || yyjson_is_null(binding) || (yyjson_is_str(binding) && yyjson_get_len(binding) == 0)) {
        selection.result = {DriverLoadStatus::NoBinding, "no custom driver selected", {}, {}};
        return selection;
    }
    selection.driverId = stringValue(binding);
    selection.result = {DriverLoadStatus::InvalidDriver, "invalid driverId", {}, {}};
    if (!component(selection.driverId)) selection.driverId.clear();
    return selection;
}

void serveDriverFile(int socket, uint8_t opcode) {
    companion_fd::FileRequest request;
    if (!companion_fd::receiveFileRequest(socket, request, opcode) ||
        request.opcode != OpenDriverOpcode || !component(request.driverId) ||
        !component(request.fileName) ||
        (request.kind != companion_fd::MetadataFile && request.kind != companion_fd::LibraryFile) ||
        (request.kind == companion_fd::MetadataFile && request.fileName != "meta.json") ||
        (request.kind == companion_fd::LibraryFile && request.fileName == "meta.json")) {
        __android_log_print(ANDROID_LOG_WARN, "ZygiskWebUI",
                            "companion driver IPC stage=request_validation failed");
        companion_fd::sendFileReply(socket, -1);
        return;
    }
    auto fail = [&](const char *stage, int error) {
        __android_log_print(ANDROID_LOG_WARN, "ZygiskWebUI",
                            "companion driver IPC stage=%s failed errno=%d", stage, error);
        companion_fd::sendFileReply(socket, -1);
    };
    Fd snapshot(companion_fd::receiveFileReply(socket));
    if (snapshot.value < 0 || fcntl(snapshot.value, F_GET_SEALS) != 0) {
        fail("snapshot_validation", errno);
        return;
    }
    Fd drivers(openAbsoluteDirectory("/data/adb/" MODULE_ID "/drivers"));
    if (drivers.value < 0) {
        fail("registry_root_open", errno);
        return;
    }
    Fd directory(openDirectory(drivers.value, request.driverId));
    if (directory.value < 0) {
        fail("driver_directory_open", errno);
        return;
    }
    off_t limit = request.kind == companion_fd::MetadataFile ? MaxMetadataBytes : MaxLibraryBytes;
    Fd file(openSafeFile(directory.value, request.fileName, limit));
    if (file.value < 0) {
        fail("driver_file_open_or_validation", errno);
        return;
    }
    if (!companion_fd::populateSnapshot(file.value, snapshot.value, limit)) {
        fail("snapshot_copy", errno);
        return;
    }
    if (!companion_fd::sendFileReply(socket, snapshot.value)) {
        __android_log_print(ANDROID_LOG_WARN, "ZygiskWebUI",
                            "companion driver IPC stage=file_fd_send failed errno=%d", errno);
    } else {
        __android_log_print(ANDROID_LOG_INFO, "ZygiskWebUI",
                            "companion driver IPC stage=file_fd_sent kind=%s",
                            request.kind == companion_fd::MetadataFile ? "metadata" : "library");
    }
}

const DriverLoadResult &DriverLoader::load(zygisk::Api *api, const DriverSelection &selection,
                                          const std::string &appDataDir, uid_t uid, gid_t gid) {
    return prepare(api, selection, appDataDir, uid, gid).result;
}

const Prepared &DriverLoader::prepare(zygisk::Api *api, const DriverSelection &selection,
                                     const std::string &appDataDir, uid_t uid, gid_t gid) {
    if (attempted) return prepared;
    attempted = true;
    result = selection.result;
    if (!selection.driverId.empty()) {
        std::string libraryName, driverDirectory, hookDirectory;
        result = prepareDriver(api, selection, appDataDir, uid, gid,
                               libraryName, driverDirectory, hookDirectory);
        prepared = {result, libraryName, driverDirectory, hookDirectory};
    } else {
        prepared = {result, {}, {}, {}};
    }
    return prepared;
}

const DriverLoadResult &DriverLoader::activate(zygisk::Api *api, const Prepared &ready) {
    if (ready.result.status != DriverLoadStatus::Prepared) return result;
    if (activationAttempted) return result;
    activationAttempted = true;
    result = ready.result;
#if defined(__aarch64__)
    constexpr char loaderPath[] = "/system/lib64/libvulkan.so";
    struct stat loaderIdentity{};
    result.status = DriverLoadStatus::AdrenotoolsFailed;
    if (!api || stat(loaderPath, &loaderIdentity) ||
        stat(ready.result.driverPath.c_str(), &selectedDriverIdentity)) {
        result.reason = "system Vulkan loader or selected private driver unavailable";
        return result;
    }
    vulkanHandle = dlopen(loaderPath, RTLD_NOW | RTLD_LOCAL);
    if (!vulkanHandle) {
        result.reason = "system Vulkan loader could not be opened for interception";
        return result;
    }
    // Both hook instances retain this parameter pointer for the process lifetime.
    auto *parameters = new HookImplParams(ADRENOTOOLS_DRIVER_CUSTOM, nullptr,
        ready.hookDirectory.c_str(), ready.driverDirectory.c_str(),
        ready.driverLibraryName.c_str(), nullptr, nullptr);
    init_hook_param(parameters);
    selectedDriverPath = ready.result.driverPath;
    api->pltHookRegister(loaderIdentity.st_dev, loaderIdentity.st_ino,
        "android_load_sphal_library", reinterpret_cast<void *>(loadSphalLibrary),
        reinterpret_cast<void **>(&originalLoadSphalLibrary));
    bool committed = api->pltHookCommit();
    if (!committed || !originalLoadSphalLibrary) {
        result.reason = "system Vulkan loader PLT interception failed or symbol was not found";
        return result;
    }
    result.status = DriverLoadStatus::HookInstalled;
    result.reason = "system Vulkan loader interception installed; waiting for a verified driver request";
#else
    result.status = DriverLoadStatus::UnsupportedDevice;
    result.reason = "custom Vulkan drivers are supported only on arm64";
    (void)api;
#endif
    return result;
}
}
