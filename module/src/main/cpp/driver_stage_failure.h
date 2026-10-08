#pragma once

#include <string>
#include <string_view>

namespace gpu {
struct DriverStageFailure {
    std::string location;
    int error = 0;

    bool reject(std::string_view location, int error) {
        this->location.assign(location);
        this->error = error;
        return false;
    }

    std::string message() const {
        return "stage=" + (location.empty() ? std::string("unknown") : location) +
            " errno=" + std::to_string(error);
    }
};
}
