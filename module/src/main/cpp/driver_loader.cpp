#include "driver_loader.h"
#include "gpu_model_reader.h"
#include "zygisk.hpp"
#include "yyjson.h"

#include <android/api-level.h>
#include <android/log.h>
#include <cerrno>
#include <cstring>
#include <cstdlib>
#include <elf.h>
#include <fcntl.h>
#include <limits.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef MODULE_ID
#define MODULE_ID "zygisk_sample"
#endif

namespace gpu {
namespace {
constexpr off_t MaxLibraryBytes = 512 * 1024 * 1024;
constexpr size_t MaxMetadataBytes = 64 * 1024;

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

bool transfer(int fd, void *buffer, size_t size, bool writing) {
    auto *p = static_cast<char *>(buffer);
    while (size) {
        ssize_t n = writing ? send(fd, p, size, MSG_NOSIGNAL) : read(fd, p, size);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        p += n;
        size -= n;
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

#if defined(__aarch64__)
int regularFile(int parent, const std::string &name, off_t limit) {
    if (!component(name)) return -1;
    Fd fd(openat(parent, name.c_str(), O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC));
    struct stat st{};
    if (fd.value < 0 || fstat(fd.value, &st) || !S_ISREG(st.st_mode) ||
        st.st_nlink != 1 || st.st_size <= 0 || st.st_size > limit) return -1;
    return dup(fd.value);
}

bool readText(int fd, std::string &text) {
    struct stat st{};
    if (fstat(fd, &st) || st.st_size <= 0 || st.st_size > MaxMetadataBytes) return false;
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

int requestDriverDirectory(zygisk::Api *api, const std::string &driverId) {
    Fd socket(api->connectCompanion());
    if (socket.value < 0) return -1;
    timeval timeout{2, 0};
    if (setsockopt(socket.value, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) ||
        setsockopt(socket.value, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout))) return -1;
    unsigned char op = OpenDriverOpcode;
    uint32_t size = driverId.size();
    if (!transfer(socket.value, &op, sizeof(op), true) ||
        !transfer(socket.value, &size, sizeof(size), true) ||
        !transfer(socket.value, const_cast<char *>(driverId.data()), size, true)) return -1;
    char status = 0;
    iovec io{&status, 1};
    alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int))]{};
    msghdr message{};
    message.msg_iov = &io;
    message.msg_iovlen = 1;
    message.msg_control = control;
    message.msg_controllen = sizeof(control);
    if (recvmsg(socket.value, &message, MSG_CMSG_CLOEXEC) != 1) return -1;
    auto *cmsg = CMSG_FIRSTHDR(&message);
    if (!cmsg || cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS ||
        cmsg->cmsg_len != CMSG_LEN(sizeof(int))) return -1;
    int fd;
    memcpy(&fd, CMSG_DATA(cmsg), sizeof(fd));
    if (status != 1 || (message.msg_flags & MSG_CTRUNC)) { close(fd); return -1; }
    return fd;
}

int privateDirectory(int parent, const std::string &name, uid_t uid, gid_t gid) {
    bool created = mkdirat(parent, name.c_str(), 0700) == 0;
    if (!created && errno != EEXIST) return -1;
    Fd fd(openDirectory(parent, name));
    struct stat st{};
    if (fd.value < 0 || fstat(fd.value, &st) ||
        st.st_uid != (created ? geteuid() : uid) || (st.st_mode & 0022)) return -1;
    if (created && (fchown(fd.value, uid, gid) || fchmod(fd.value, 0700))) return -1;
    return dup(fd.value);
}

bool copyFile(int source, int directory, const std::string &name, uid_t uid, gid_t gid) {
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
    return fchown(target.value, uid, gid) == 0 && fchmod(target.value, 0400) == 0 && fsync(target.value) == 0;
}

bool sameFile(int source, int directory, const std::string &name, uid_t uid) {
    Fd target(regularFile(directory, name, MaxLibraryBytes));
    struct stat a{}, b{};
    if (target.value < 0 || fstat(source, &a) || fstat(target.value, &b) ||
        a.st_size != b.st_size || b.st_uid != uid || (b.st_mode & 0022)) return false;
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
                 int metadata, int library, const std::string &name, std::string &path) {
    // Android may alias /data/user/0 to /data/data; resolve only the Zygisk-provided base.
    char canonical[PATH_MAX];
    if (uid < 10000 || !realpath(data.c_str(), canonical)) return false;
    std::string base(canonical);
    const std::string user = std::to_string(uid / 100000);
    if (base != "/data/user/" + user + "/" + selection.packageName &&
        base != "/data/user_de/" + user + "/" + selection.packageName &&
        !(user == "0" && base == "/data/data/" + selection.packageName)) return false;
    Fd app(openAbsoluteDirectory(base));
    struct stat st{};
    if (app.value < 0 || fstat(app.value, &st) || st.st_uid != uid) return false;
    Fd files(privateDirectory(app.value, "files", uid, gid));
    Fd module(privateDirectory(files.value, MODULE_ID, uid, gid));
    Fd parent(privateDirectory(module.value, "gpu-driver", uid, gid));
    if (parent.value < 0) return false;
    path = base + "/files/" MODULE_ID "/gpu-driver/" + selection.driverId + "/";
    Fd existing(openDirectory(parent.value, selection.driverId));
    if (existing.value >= 0) {
        return fstat(existing.value, &st) == 0 && st.st_uid == uid && !(st.st_mode & 0077) &&
            sameFile(metadata, existing.value, "meta.json", uid) && sameFile(library, existing.value, name, uid);
    }
    std::string temporary = ".stage-" + std::to_string(getpid());
    if (mkdirat(parent.value, temporary.c_str(), 0700)) return false;
    Fd staging(openDirectory(parent.value, temporary));
    if (staging.value < 0 || fstat(staging.value, &st) || st.st_uid != geteuid() || (st.st_mode & 0077)) return false;
    bool ready = copyFile(metadata, staging.value, "meta.json", uid, gid) &&
        copyFile(library, staging.value, name, uid, gid) && fsync(staging.value) == 0;
    if (!ready) {
        unlinkat(staging.value, "meta.json", 0);
        unlinkat(staging.value, name.c_str(), 0);
        unlinkat(parent.value, temporary.c_str(), AT_REMOVEDIR);
        return false;
    }
    // Never replace an app-controlled destination or publish into a concurrently created directory.
    if (fchown(staging.value, uid, gid)) return false;
    if (syscall(SYS_renameat2, parent.value, temporary.c_str(), parent.value,
                selection.driverId.c_str(), 1 /* RENAME_NOREPLACE */) == 0) {
        Fd published(openDirectory(parent.value, selection.driverId));
        struct stat actual{}, expected{};
        return published.value >= 0 && fstat(published.value, &actual) == 0 &&
            fstat(staging.value, &expected) == 0 && actual.st_dev == expected.st_dev &&
            actual.st_ino == expected.st_ino && fsync(parent.value) == 0;
    }
    // Once app-owned, leave the temporary directory untouched on failure.
    return false;
}
#endif

DriverLoadResult prepare([[maybe_unused]] zygisk::Api *api, [[maybe_unused]] const DriverSelection &selection,
                         [[maybe_unused]] const std::string &appDataDir, [[maybe_unused]] uid_t uid,
                         [[maybe_unused]] gid_t gid) {
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
            registryFd = requestDriverDirectory(api, selection.driverId);
        })) {
        result.status = DriverLoadStatus::UnsupportedDevice;
        result.reason = modelStatus != GpuModelReadStatus::Readable || gpuModel.empty()
            ? "KGSL GPU model unavailable, empty, or invalid; custom driver skipped"
            : "KGSL GPU model did not identify Adreno; custom driver skipped";
        return result;
    }
    Fd registry(registryFd);
    Fd meta(regularFile(registry.value, "meta.json", MaxMetadataBytes));
    std::string text;
    if (meta.value < 0 || !readText(meta.value, text)) {
        result.reason = "driver registry/meta.json unavailable or unsafe";
        return result;
    }
    Json doc(text);
    std::string name = stringValue(yyjson_obj_get(doc.root(), "libraryName"));
    std::string abi = stringValue(yyjson_obj_get(doc.root(), "abi"));
    if (!component(name) || name == "meta.json" || (!abi.empty() && abi != "arm64-v8a")) {
        result.reason = "invalid libraryName or metadata ABI";
        return result;
    }
    Fd library(regularFile(registry.value, name, MaxLibraryBytes));
    if (library.value < 0 || !arm64Library(library.value)) {
        result.reason = "custom driver missing, unsafe, or not an arm64 ELF shared object";
        return result;
    }
    __android_log_print(ANDROID_LOG_INFO, "ZygiskWebUI", "custom driver file found: %s/%s",
                        selection.driverId.c_str(), name.c_str());
    std::string directory;
    if (!stageDriver(appDataDir, selection, uid, gid, meta.value, library.value, name, directory)) {
        result.reason = "private driver staging failed (permissions, existing content, or atomic publication)";
        return result;
    }
    result.driverPath = directory + name;
    result.status = DriverLoadStatus::HookPathUnavailable;
    Fd module(api->getModuleDir());
    Fd hooks(openDirectory(module.value, "zygisk"));
    Fd implementation(regularFile(hooks.value, "libhook_impl.so", MaxLibraryBytes));
    Fd main(regularFile(hooks.value, "libmain_hook.so", MaxLibraryBytes));
    bool validHooks = implementation.value >= 0 && main.value >= 0 &&
        arm64Library(implementation.value) && arm64Library(main.value);
    result.reason = validHooks
        ? "module hook files validated; target nativeLibraryDir and namespace visibility unproven; AdrenoTools not called"
        : "module hook files unavailable/invalid; target nativeLibraryDir unproven; AdrenoTools not called";
    // AppSpecializeArgs has no nativeLibraryDir. Neither this module FD nor the private
    // driver directory proves the hook path required by adrenotools_open_libvulkan.
#endif
    return result;
}
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

void serveDriverDirectory(int socket) {
    uint32_t size = 0;
    if (!transfer(socket, &size, sizeof(size), false) || size == 0 || size > NAME_MAX) return;
    std::string id(size, '\0');
    if (!transfer(socket, id.data(), size, false) || !component(id)) return;
    Fd drivers(openAbsoluteDirectory("/data/adb/" MODULE_ID "/drivers"));
    Fd directory(openDirectory(drivers.value, id));
    char status = directory.value >= 0 ? 1 : 0;
    iovec io{&status, 1};
    alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int))]{};
    msghdr message{};
    message.msg_iov = &io;
    message.msg_iovlen = 1;
    if (directory.value >= 0) {
        message.msg_control = control;
        message.msg_controllen = sizeof(control);
        auto *cmsg = CMSG_FIRSTHDR(&message);
        cmsg->cmsg_level = SOL_SOCKET;
        cmsg->cmsg_type = SCM_RIGHTS;
        cmsg->cmsg_len = CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(cmsg), &directory.value, sizeof(int));
    }
    sendmsg(socket, &message, MSG_NOSIGNAL);
}

const DriverLoadResult &DriverLoader::load(zygisk::Api *api, const DriverSelection &selection,
                                          const std::string &appDataDir, uid_t uid, gid_t gid) {
    if (attempted) return result;
    attempted = true;
    result = selection.result;
    if (!selection.driverId.empty()) result = prepare(api, selection, appDataDir, uid, gid);
    return result;
}
}
