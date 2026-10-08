#pragma once

#include <jni.h>
#include <string>

namespace zygisk { struct Api; }

namespace gpu {
bool configureHwuiGles(zygisk::Api *api, std::string &reason);
bool configureGraphicsEnvironment(zygisk::Api *api, JNIEnv *env,
                                  const std::string &driverDirectory, std::string &reason);
}
