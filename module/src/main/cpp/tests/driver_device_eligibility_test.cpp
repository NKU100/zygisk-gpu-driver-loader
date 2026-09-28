#include "../driver_loader.h"
#include "../gpu_model_reader.h"

#include <cassert>
#include <cstdlib>
#include <string>
#include <unistd.h>

bool readsGpuModelThroughSysfsLink() {
    char targetPath[] = "/tmp/zygisk-gpu-model-XXXXXX";
    int targetFd = mkstemp(targetPath);
    if (targetFd < 0) return false;

    constexpr char modelContents[] = "Adreno750v2\n";
    bool wroteModel = write(targetFd, modelContents, sizeof(modelContents) - 1) ==
        static_cast<ssize_t>(sizeof(modelContents) - 1);
    close(targetFd);

    std::string linkPath = std::string(targetPath) + ".link";
    bool linked = symlink(targetPath, linkPath.c_str()) == 0;
    std::string model;
    gpu::GpuModelReadStatus status = linked
        ? gpu::readGpuModelFile(linkPath, model)
        : gpu::GpuModelReadStatus::Unavailable;

    unlink(linkPath.c_str());
    unlink(targetPath);
    return wroteModel && linked && status == gpu::GpuModelReadStatus::Readable &&
        model == "Adreno750v2\n";
}

int main() {
    assert(readsGpuModelThroughSysfsLink());

    int registryRequests = 0;
    auto requestRegistry = [&] { ++registryRequests; };

    const char *adrenoModels[] = {
        "Adreno750v2",
        "Adreno 750",
        "Adreno (TM) 750",
        "adreno750v2",
        "aDrEnO 750V2",
        "  Adreno 750\n"
    };
    for (const char *model : adrenoModels) {
        int requestsBeforeCheck = registryRequests;
        assert(gpu::isAdrenoGpuModel(model));
        assert(gpu::withAdrenoGpuModel(model, requestRegistry));
        assert(registryRequests == requestsBeforeCheck + 1);
    }

    const char *unsupportedModels[] = {
        "",
        "unknown",
        "Mali-G715",
        "NotAdreno750",
        "Adreno",
        "Adrenoless750",
        "Adreno unknown 750"
    };
    for (const char *model : unsupportedModels) {
        int requestsBeforeCheck = registryRequests;
        assert(!gpu::isAdrenoGpuModel(model));
        assert(!gpu::withAdrenoGpuModel(model, requestRegistry));
        assert(registryRequests == requestsBeforeCheck);
    }
}
