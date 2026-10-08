#include "../hwui_property_override.h"
#include <cassert>
#include <string>

struct Info { const char *name; const char *value; };
Info other{"ro.build.version.sdk", "36"};
const Info *findOriginal(const char *name) {
    return std::strcmp(name, other.name) == 0 ? &other : nullptr;
}
void readOriginal(const Info *info, gpu::HwuiPropertyOverride<Info>::Callback callback, void *cookie) {
    assert(info == &other);
    callback(cookie, info->name, info->value, 123);
}
void record(void *cookie, const char *, const char *value, unsigned) {
    *static_cast<std::string *>(cookie) = value;
}
int main() {
    gpu::HwuiPropertyOverride<Info> properties;
    auto renderer = properties.find("debug.hwui.renderer", findOriginal);
    assert(renderer != nullptr);
    std::string result;
    properties.read(renderer, record, &result, readOriginal);
    assert(result == "skiagl");
    auto sdk = properties.find("ro.build.version.sdk", findOriginal);
    properties.read(sdk, record, &result, readOriginal);
    assert(result == "36");
    assert(properties.find("missing.property", findOriginal) == nullptr);
    assert(properties.find("debug.hwui.renderer.extra", findOriginal) == nullptr);
}
