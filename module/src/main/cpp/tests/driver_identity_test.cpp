#include "../driver_identity.h"
#include <cassert>

int main(int argc, char **argv) {
    assert(argc == 3);
    struct stat selected{};
    assert(stat(argv[1], &selected) == 0);
    void *custom = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    void *stock = dlopen(argv[2], RTLD_NOW | RTLD_LOCAL);
    assert(custom && stock);
    assert(gpu::identifyLoadedDriver(custom, selected.st_dev, selected.st_ino) == gpu::DriverIdentity::Selected);
    assert(gpu::identifyLoadedDriver(stock, selected.st_dev, selected.st_ino) == gpu::DriverIdentity::Other);
    dlclose(custom);
    dlclose(stock);
}
