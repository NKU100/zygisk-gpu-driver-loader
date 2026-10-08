#include "../driver_stage_failure.h"
#include <cassert>
#include <cerrno>

int main() {
    gpu::DriverStageFailure failure;

    assert(!failure.reject("existing_cache.content", 0));
    assert(failure.message() == "stage=existing_cache.content errno=0");

    assert(!failure.reject("app_data.open", EACCES));
    assert(failure.message() == "stage=app_data.open errno=13");
}
