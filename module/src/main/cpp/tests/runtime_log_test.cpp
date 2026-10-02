#include "../runtime_log.h"
#include <cassert>
#include <condition_variable>
#include <fcntl.h>
#include <sys/wait.h>

static int anonymousQueue() {
    char path[] = "/tmp/gpu-runtime-queue.XXXXXX";
    int fd = mkstemp(path);
    assert(fd >= 0);
    assert(unlink(path) == 0);
    assert(ftruncate(fd, sizeof(gpu::RuntimeLogQueue)) == 0);
    return fd;
}

int main() {
    std::mutex mutex;
    std::condition_variable changed;
    std::vector<std::string> lines;
    gpu::RuntimeLogHub hub([&](const std::string &line) {
        std::lock_guard lock(mutex);
        lines.push_back(line);
        changed.notify_all();
    });
    int fd = anonymousQueue();
    gpu::RuntimeLogSender sender;
    assert(sender.initialize(fd));
    assert(hub.add(fd, getpid()));
    close(fd);
    assert(sender.send("HookInstalled\n"));
    assert(sender.send("Loaded\n"));
    {
        std::unique_lock lock(mutex);
        assert(changed.wait_for(lock, std::chrono::seconds(2), [&] { return lines.size() == 2; }));
        assert(lines[0] == "HookInstalled\n");
        assert(lines[1] == "Loaded\n");
    }
    assert(!sender.send(std::string(5000, 'x')));
    pid_t child = fork();
    assert(child >= 0);
    if (child == 0) {
        const bool rejected = !sender.send("child\n");
        sender.~RuntimeLogSender();
        _exit(rejected ? 0 : 1);
    }
    int status = 0;
    assert(waitpid(child, &status, 0) == child);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    assert(sender.send("SystemFallback\n"));
    {
        std::unique_lock lock(mutex);
        assert(changed.wait_for(lock, std::chrono::seconds(2), [&] { return lines.size() == 3; }));
        assert(lines[2] == "SystemFallback\n");
    }
    int fullFd = anonymousQueue();
    gpu::RuntimeLogSender full;
    assert(full.initialize(fullFd));
    close(fullFd);
    for (int i = 0; i < 8; ++i) assert(full.send("record\n"));
    const auto start = std::chrono::steady_clock::now();
    assert(!full.send("overflow\n"));
    assert(std::chrono::steady_clock::now() - start < std::chrono::seconds(1));
    gpu::RuntimeLogSender absent;
    assert(!absent.send("fallback\n"));
    assert(!hub.add(STDIN_FILENO, getpid()));
}
