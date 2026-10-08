#pragma once

#include <cstdint>
#include <string>
#include <string_view>
#include <sys/stat.h>
#include <sys/types.h>
#if defined(__ANDROID__)
#include <jni.h>
#else
struct JNIEnv;
#endif

namespace zygisk { struct Api; }

namespace gpu {
void setRuntimeLogSink(void (*sink)(int, const char *));

enum class DriverLoadStatus {
    NotTargeted, NoBinding, UnsupportedDevice, InvalidDriver, HookPathUnavailable, AdrenotoolsFailed,
    Loaded, Prepared, HookInstalled, SystemFallback, LoadUnverified
};

enum class DriverLifecycleAction {
    DropModuleLibrary,
    KeepModuleLibrary,
    UseSystemDriver
};

enum class StagedFileKind { NativeLibrary, Metadata };

inline bool appDataPathAllowed(const std::string &path, const std::string &package, uid_t uid) {
    if (uid < 10000) return false;
    const std::string user = std::to_string(uid / 100000);
    return path == "/data/user/" + user + "/" + package ||
        path == "/data/user_de/" + user + "/" + package ||
        (user == "0" && path == "/data/data/" + package);
}

inline constexpr bool stagedFileModeAllowed(StagedFileKind kind, mode_t mode) noexcept {
    mode_t expected = kind == StagedFileKind::NativeLibrary ? 0500 : 0400;
    return (mode & 07777) == expected;
}

inline constexpr bool privateDirectoryMetadataAllowed(uid_t actualUid, gid_t actualGid, mode_t mode,
                                                       uid_t expectedUid, gid_t expectedGid) noexcept {
    return actualUid == expectedUid && actualGid == expectedGid && (mode & 07777) == 0700;
}

inline constexpr bool appFilesDirectoryMetadataAllowed(uid_t actualUid, gid_t actualGid, mode_t mode,
                                                        uid_t expectedUid, gid_t expectedGid) noexcept {
    const mode_t permissions = mode & 07777;
    return actualUid == expectedUid && actualGid == expectedGid &&
        (permissions == 0700 || permissions == 0771);
}

inline constexpr DriverLifecycleAction driverLifecycleAction(bool targeted, bool hasBinding,
                                                               bool preparationSucceeded) noexcept {
    if (!targeted || !hasBinding) return DriverLifecycleAction::DropModuleLibrary;
    return preparationSucceeded ? DriverLifecycleAction::KeepModuleLibrary : DriverLifecycleAction::UseSystemDriver;
}

inline bool isAdrenoGpuModel(std::string_view model) noexcept {
    auto isWhitespace = [](char value) {
        return value == ' ' || value == '\t' || value == '\r' || value == '\n';
    };
    auto lowercase = [](char value) {
        return value >= 'A' && value <= 'Z' ? static_cast<char>(value - 'A' + 'a') : value;
    };
    auto hasPrefixIgnoreCase = [&](std::string_view value, std::string_view prefix) {
        if (value.size() < prefix.size()) return false;
        for (size_t index = 0; index < prefix.size(); ++index) {
            if (lowercase(value[index]) != prefix[index]) return false;
        }
        return true;
    };

    while (!model.empty() && isWhitespace(model.front())) model.remove_prefix(1);
    while (!model.empty() && isWhitespace(model.back())) model.remove_suffix(1);
    constexpr std::string_view brand = "adreno";
    if (model.size() <= brand.size() || !hasPrefixIgnoreCase(model, brand)) return false;
    model.remove_prefix(brand.size());
    while (!model.empty() && isWhitespace(model.front())) model.remove_prefix(1);
    if (hasPrefixIgnoreCase(model, "(tm)")) {
        model.remove_prefix(4);
        while (!model.empty() && isWhitespace(model.front())) model.remove_prefix(1);
    }
    if (model.empty() || model.front() < '0' || model.front() > '9') return false;
    for (char value : model) {
        bool alphanumeric = (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z') ||
            (value >= '0' && value <= '9');
        if (!alphanumeric && !isWhitespace(value) && value != '(' && value != ')' &&
            value != '-' && value != '_') return false;
    }
    return true;
}

template <typename Continue>
inline bool withAdrenoGpuModel(std::string_view model, Continue &&continueLoading) {
    if (!isAdrenoGpuModel(model)) return false;
    continueLoading();
    return true;
}

struct DriverLoadResult {
    DriverLoadStatus status;
    std::string reason;
    std::string driverPath;
    std::string hookPath;
};

struct Prepared {
    DriverLoadResult result;
    std::string driverLibraryName;
    std::string driverDirectory;
    std::string hookDirectory;

    explicit operator bool() const noexcept { return result.status == DriverLoadStatus::Prepared; }
};

struct DriverSelection {
    DriverLoadResult result;
    std::string packageName;
    std::string driverId;
};

inline constexpr unsigned char OpenDriverOpcode = 2;
inline constexpr unsigned char PrepareDriverOpcode = 3;
inline constexpr size_t MaxConfigBytes = 1024 * 1024;

const char *statusName(DriverLoadStatus status);
DriverSelection selectDriver(const std::string &config, const std::string &process);
void serveDriverFile(int socket, uint8_t opcode);
void serveDriverPreparation(int socket);

class DriverLoader {
public:
    const Prepared &prepare(zygisk::Api *api, const DriverSelection &selection,
                            const std::string &appDataDir, uid_t uid, gid_t gid);
    const DriverLoadResult &activate(zygisk::Api *api, const Prepared &prepared, JNIEnv *env = nullptr);
    const DriverLoadResult &load(zygisk::Api *api, const DriverSelection &selection,
                                 const std::string &appDataDir, uid_t uid, gid_t gid);
private:
    bool attempted = false;
    DriverLoadResult result{DriverLoadStatus::NotTargeted, "not attempted", {}, {}};
    Prepared prepared{{DriverLoadStatus::NotTargeted, "not attempted", {}, {}}, {}, {}, {}};
    void *vulkanHandle = nullptr;
    bool activationAttempted = false;
};

}
