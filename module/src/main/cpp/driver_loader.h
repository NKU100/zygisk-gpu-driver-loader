#pragma once

#include <string>
#include <sys/types.h>

namespace zygisk { struct Api; }

namespace gpu {

enum class DriverLoadStatus {
    NotTargeted, NoBinding, InvalidDriver, HookPathUnavailable, AdrenotoolsFailed, Loaded
};

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
