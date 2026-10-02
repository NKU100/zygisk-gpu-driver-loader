#include <string>
#include <mutex>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <cerrno>
#include <cassert>
#include <iterator>
#include <fstream>

#ifndef TEST_TEMP_DIR
#define TEST_TEMP_DIR "/tmp"
#endif

static std::string logPath;
#define LOG_PATH logPath.c_str()
#include "example_log_fixture.h"

static std::string contents() {
    std::ifstream input(logPath);
    return {std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
}

int main() {
    char path[] = TEST_TEMP_DIR "/gpu-persistent-log.XXXXXX";
    int fd = mkstemp(path);
    assert(fd >= 0);
    close(fd);
    logPath = path;
    companion_appendLog("first\n");
    companion_appendLog("second\n");
    assert(contents() == "first\nsecond\n");
    const std::string tail = std::string(LOG_TRIM_BYTES - 7, 't') + "latest\n";
    {
        std::ofstream output(logPath, std::ios::trunc);
        output << std::string(LOG_MAX_BYTES, 'x') << tail;
    }
    companion_appendLog("new\n");
    assert(contents() == tail + "new\n");
    assert(unlink(path) == 0);
}
