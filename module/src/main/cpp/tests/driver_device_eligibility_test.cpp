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
    assert(gpu::appDataPathAllowed("/data/user/0/com.example", "com.example", 10001));
    assert(gpu::appDataPathAllowed("/data/user_de/10/com.example", "com.example", 1010001));
    assert(gpu::appDataPathAllowed("/data/data/com.example", "com.example", 10001));
    assert(!gpu::appDataPathAllowed("/data/user/0/com.other", "com.example", 10001));
    assert(!gpu::appDataPathAllowed("/data/user_de/0/com.example", "com.example", 1010001));
    assert(!gpu::appDataPathAllowed("/data/data/com.example", "com.example", 1010001));
    assert(!gpu::appDataPathAllowed("/data/user/0/com.example/../com.other", "com.example", 10001));
    assert(!gpu::appDataPathAllowed("/data/user/0/com.example", "com.example", 9999));
    assert(readsGpuModelThroughSysfsLink());

    assert(gpu::stagedFileModeAllowed(gpu::StagedFileKind::NativeLibrary, 0500));
    assert(gpu::stagedFileModeAllowed(gpu::StagedFileKind::Metadata, 0400));
    assert(!gpu::stagedFileModeAllowed(gpu::StagedFileKind::NativeLibrary, 0400));
    assert(!gpu::stagedFileModeAllowed(gpu::StagedFileKind::NativeLibrary, 0700));
    assert(!gpu::stagedFileModeAllowed(gpu::StagedFileKind::NativeLibrary, 0501));
    assert(!gpu::stagedFileModeAllowed(gpu::StagedFileKind::Metadata, 0500));
    assert(!gpu::stagedFileModeAllowed(gpu::StagedFileKind::Metadata, 0600));
    assert(!gpu::stagedFileModeAllowed(gpu::StagedFileKind::Metadata, 0444));
    assert(!gpu::stagedFileModeAllowed(gpu::StagedFileKind::Metadata, 0401));

    assert(gpu::privateDirectoryMetadataAllowed(10000, 10000, 0700, 10000, 10000));
    assert(!gpu::privateDirectoryMetadataAllowed(10000, 10000, 0755, 10000, 10000));
    assert(!gpu::privateDirectoryMetadataAllowed(10000, 10000, 0750, 10000, 10000));
    assert(!gpu::privateDirectoryMetadataAllowed(10000, 10000, 0710, 10000, 10000));
    assert(!gpu::privateDirectoryMetadataAllowed(10000, 10000, 0705, 10000, 10000));
    assert(!gpu::privateDirectoryMetadataAllowed(10000, 10000, 0701, 10000, 10000));
    assert(!gpu::privateDirectoryMetadataAllowed(10000, 10000, 0720, 10000, 10000));
    assert(!gpu::privateDirectoryMetadataAllowed(10000, 10000, 0702, 10000, 10000));
    assert(!gpu::privateDirectoryMetadataAllowed(10001, 10000, 0700, 10000, 10000));
    assert(!gpu::privateDirectoryMetadataAllowed(10000, 10001, 0700, 10000, 10000));
    assert(gpu::appFilesDirectoryMetadataAllowed(10000, 10000, 0771, 10000, 10000));
    assert(gpu::appFilesDirectoryMetadataAllowed(10000, 10000, 0700, 10000, 10000));
    assert(!gpu::appFilesDirectoryMetadataAllowed(10000, 10000, 0777, 10000, 10000));
    assert(!gpu::appFilesDirectoryMetadataAllowed(10001, 10000, 0771, 10000, 10000));
    assert(!gpu::appFilesDirectoryMetadataAllowed(10000, 10001, 0771, 10000, 10000));
    assert(!gpu::appFilesDirectoryMetadataAllowed(10000, 10000, 04771, 10000, 10000));

    assert(gpu::driverLifecycleAction(false, false, false) == gpu::DriverLifecycleAction::DropModuleLibrary);
    assert(gpu::driverLifecycleAction(false, true, true) == gpu::DriverLifecycleAction::DropModuleLibrary);
    assert(gpu::driverLifecycleAction(true, false, false) == gpu::DriverLifecycleAction::DropModuleLibrary);
    assert(gpu::driverLifecycleAction(true, true, true) == gpu::DriverLifecycleAction::KeepModuleLibrary);
    assert(gpu::driverLifecycleAction(true, true, false) == gpu::DriverLifecycleAction::UseSystemDriver);

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
