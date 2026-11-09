#pragma once
#include <cstring>

namespace gpu {
inline constexpr char HwuiRendererProperty[] = "debug.hwui.renderer";
inline constexpr char HwuiGlesRenderer[] = "skiagl";

// Forwards the GLES renderer through the platform implementation with a key that never exists,
// so the returned string is constructed by the platform C++ runtime rather than the module's.
template <typename String>
String hwuiRendererProperty(const String &key, const String &fallback,
                            String (*original)(const String &, const String &)) {
    if (key == HwuiRendererProperty) return original(String(), String(HwuiGlesRenderer));
    return original(key, fallback);
}

template <typename PropertyInfo>
class HwuiPropertyOverride {
public:
    using Find = const PropertyInfo *(*)(const char *);
    using Callback = void (*)(void *, const char *, const char *, unsigned);
    using Read = void (*)(const PropertyInfo *, Callback, void *);
    const PropertyInfo *find(const char *name, Find original) const {
        if (name && std::strcmp(name, HwuiRendererProperty) == 0)
            return reinterpret_cast<const PropertyInfo *>(&rendererToken);
        return original(name);
    }
    void read(const PropertyInfo *info, Callback callback, void *cookie, Read original) const {
        if (info == reinterpret_cast<const PropertyInfo *>(&rendererToken)) {
            callback(cookie, HwuiRendererProperty, HwuiGlesRenderer, 0);
            return;
        }
        original(info, callback, cookie);
    }
private:
    // This token is consumed only by the paired libbase read interception, never by Bionic.
    char rendererToken = 0;
};
}
