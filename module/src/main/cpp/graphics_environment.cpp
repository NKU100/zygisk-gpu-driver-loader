#include "graphics_environment.h"
#include "zygisk.hpp"
#include <dlfcn.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include "hwui_property_override.h"

namespace gpu {
namespace {
using NativeSetter = void (*)(JNIEnv *, jclass, jstring, jstring);
NativeSetter originalSetter = nullptr;
HwuiPropertyOverride<prop_info> hwuiProperties;
HwuiPropertyOverride<prop_info>::Find originalPropertyFind = nullptr;
HwuiPropertyOverride<prop_info>::Read originalPropertyRead = nullptr;
void *(*originalEglDriverNamespace)(void *) = nullptr;

void *systemEglDriverNamespace(void *) {
    // The module replaces Vulkan only; EGL must not search a Vulkan-only package for GLES.
    return nullptr;
}

const prop_info *findProperty(const char *name) {
    return hwuiProperties.find(name, originalPropertyFind);
}

void readProperty(const prop_info *info, HwuiPropertyOverride<prop_info>::Callback callback,
                  void *cookie) {
    hwuiProperties.read(info, callback, cookie, originalPropertyRead);
}

void forwardSetter(JNIEnv *env, jclass type, jstring path, jstring libraries) {
    if (originalSetter) originalSetter(env, type, path, libraries);
}
}

bool configureHwuiGles(zygisk::Api *api, std::string &reason) {
    struct stat identity{};
    struct stat eglIdentity{};
    if (!api || stat("/system/lib64/libbase.so", &identity) ||
        stat("/system/lib64/libEGL.so", &eglIdentity)) {
        reason = "HWUI property library unavailable";
        return false;
    }
    api->pltHookRegister(identity.st_dev, identity.st_ino, "__system_property_find",
        reinterpret_cast<void *>(findProperty), reinterpret_cast<void **>(&originalPropertyFind));
    api->pltHookRegister(identity.st_dev, identity.st_ino, "__system_property_read_callback",
        reinterpret_cast<void *>(readProperty), reinterpret_cast<void **>(&originalPropertyRead));
    api->pltHookRegister(eglIdentity.st_dev, eglIdentity.st_ino,
        "_ZN7android11GraphicsEnv18getDriverNamespaceEv",
        reinterpret_cast<void *>(systemEglDriverNamespace),
        reinterpret_cast<void **>(&originalEglDriverNamespace));
    bool ready = api->pltHookCommit() && originalPropertyFind && originalPropertyRead &&
        originalEglDriverNamespace;
    reason = ready ? "per-app HWUI GLES override installed; EGL retains system driver" :
        "per-app HWUI GLES property override unavailable";
    return ready;
}

bool configureGraphicsEnvironment(zygisk::Api *api, JNIEnv *env,
                                  const std::string &driverDirectory, std::string &reason) {
    if (!api || !env) {
        reason = "graphics environment JNI unavailable";
        return false;
    }
    // Resolve only parameter-free platform C++ methods; strings cross the JNI boundary.
    void *library = dlopen("/system/lib64/libgraphicsenv.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) {
        reason = "framework graphics environment library unavailable";
        return false;
    }
    auto instance = reinterpret_cast<void *(*)()>(dlsym(library,
        "_ZN7android11GraphicsEnv11getInstanceEv"));
    auto driverNamespace = reinterpret_cast<void *(*)(void *)>(dlsym(library,
        "_ZN7android11GraphicsEnv18getDriverNamespaceEv"));
    if (!instance || !driverNamespace) {
        reason = "framework graphics environment namespace API unavailable";
        dlclose(library);
        return false;
    }
    jclass type = env->FindClass("android/os/GraphicsEnvironment");
    if (!type) {
        env->ExceptionClear();
        reason = "framework graphics environment class unavailable";
        dlclose(library);
        return false;
    }
    jstring path = env->NewStringUTF(driverDirectory.c_str());
    jstring libraries = env->NewStringUTF("");
    if (!path || !libraries) {
        env->ExceptionClear();
        if (path) env->DeleteLocalRef(path);
        if (libraries) env->DeleteLocalRef(libraries);
        env->DeleteLocalRef(type);
        dlclose(library);
        reason = "graphics environment JNI strings unavailable";
        return false;
    }
    jmethodID method = env->GetStaticMethodID(type, "setDriverPathAndSphalLibraries",
                                             "(Ljava/lang/String;Ljava/lang/String;)V");
    bool invoked = false;
    if (method) {
        env->CallStaticVoidMethod(type, method, path, libraries);
        invoked = !env->ExceptionCheck();
    } else {
        env->ExceptionClear();
        JNINativeMethod entry{const_cast<char *>("setDriverPathAndSphalLibraries"),
            const_cast<char *>("(Ljava/lang/String;Ljava/lang/String;)V"),
            reinterpret_cast<void *>(forwardSetter)};
        api->hookJniNativeMethods(env, "android/os/GraphicsEnvironment", &entry, 1);
        originalSetter = reinterpret_cast<NativeSetter>(entry.fnPtr);
        if (originalSetter) {
            originalSetter(env, type, path, libraries);
            invoked = !env->ExceptionCheck();
        }
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(path);
    env->DeleteLocalRef(libraries);
    env->DeleteLocalRef(type);
    const bool ready = invoked && driverNamespace(instance()) != nullptr;
    reason = ready ? "per-app framework driver namespace configured" :
        invoked ? "framework driver namespace creation failed" : "framework driver path setter unavailable";
    dlclose(library);
    return ready;
}
}
