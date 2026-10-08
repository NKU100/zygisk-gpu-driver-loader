#include "../path_component.h"

#include <cassert>

int main() {
    assert(gpu::safeFileName("vendor.qti.hardware.display.mapper@2.0.so"));
    assert(!gpu::safePathComponent("vendor.qti.hardware.display.mapper@2.0.so"));
    assert(gpu::safeFileName("vulkan.adreno.so"));
    assert(!gpu::safeFileName("../escape.so"));
    assert(!gpu::safeFileName("folder/escape.so"));
    assert(!gpu::safeFileName(""));
    assert(!gpu::safeFileName(".."));
}
