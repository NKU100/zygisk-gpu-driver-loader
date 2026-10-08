#pragma once
#include <cstring>

namespace gpu {
template <typename PropertyInfo>
class HwuiPropertyOverride {
public:
    using Find = const PropertyInfo *(*)(const char *);
    using Callback = void (*)(void *, const char *, const char *, unsigned);
    using Read = void (*)(const PropertyInfo *, Callback, void *);
    const PropertyInfo *find(const char *name, Find original) const {
        if (name && std::strcmp(name, "debug.hwui.renderer") == 0)
            return reinterpret_cast<const PropertyInfo *>(&rendererToken);
        return original(name);
    }
    void read(const PropertyInfo *info, Callback callback, void *cookie, Read original) const {
        if (info == reinterpret_cast<const PropertyInfo *>(&rendererToken)) {
            callback(cookie, "debug.hwui.renderer", "skiagl", 0);
            return;
        }
        original(info, callback, cookie);
    }
private:
    // This token is consumed only by the paired libbase read interception, never by Bionic.
    char rendererToken = 0;
};
}
