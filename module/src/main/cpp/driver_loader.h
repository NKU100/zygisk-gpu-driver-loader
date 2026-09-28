#pragma once

#include <string>
#include <string_view>
#include <sys/types.h>

namespace zygisk { struct Api; }

namespace gpu {

enum class DriverLoadStatus {
    NotTargeted, NoBinding, UnsupportedDevice, InvalidDriver, HookPathUnavailable, AdrenotoolsFailed, Loaded
};

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

struct DriverSelection {
    DriverLoadResult result;
    std::string packageName;
    std::string driverId;
};

inline constexpr unsigned char OpenDriverOpcode = 2;
inline constexpr size_t MaxConfigBytes = 1024 * 1024;

const char *statusName(DriverLoadStatus status);
DriverSelection selectDriver(const std::string &config, const std::string &process);
void serveDriverDirectory(int socket);

class DriverLoader {
public:
    const DriverLoadResult &load(zygisk::Api *api, const DriverSelection &selection,
                                 const std::string &appDataDir, uid_t uid, gid_t gid);
private:
    bool attempted = false;
    DriverLoadResult result{DriverLoadStatus::NotTargeted, "not attempted", {}, {}};
};

}
