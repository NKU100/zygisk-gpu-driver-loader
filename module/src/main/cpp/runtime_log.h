#pragma once

#include <atomic>
#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <functional>
#include <mutex>
#include <new>
#include <signal.h>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <thread>
#include <unistd.h>
#include <vector>

namespace gpu {
inline constexpr uint8_t RuntimeLogOpcode = 4;
inline constexpr size_t MaxRuntimeLogBytes = 4096;
inline constexpr uint32_t RuntimeLogSlots = 8;

struct RuntimeLogQueue {
    std::atomic<uint32_t> written{0};
    std::atomic<uint32_t> read{0};
    std::atomic<uint32_t> closed{0};
    struct Record { uint32_t size; char bytes[MaxRuntimeLogBytes]; } records[RuntimeLogSlots];
};
static_assert(std::atomic<uint32_t>::is_always_lock_free);

inline RuntimeLogQueue *mapRuntimeLog(int fd) {
    struct stat info{};
    if (fstat(fd, &info) || !S_ISREG(info.st_mode) || info.st_nlink != 0 ||
        info.st_size != sizeof(RuntimeLogQueue)) return nullptr;
    void *memory = mmap(nullptr, sizeof(RuntimeLogQueue), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    return memory == MAP_FAILED ? nullptr : static_cast<RuntimeLogQueue *>(memory);
}

class RuntimeLogSender {
public:
    ~RuntimeLogSender() {
        if (queue) {
            if (owner == getpid()) queue->closed.store(1, std::memory_order_release);
            munmap(queue, sizeof(RuntimeLogQueue));
        }
    }
    bool initialize(int fd) {
        if (queue) return false;
        queue = mapRuntimeLog(fd);
        if (queue) {
            new (queue) RuntimeLogQueue{};
            owner = getpid();
        }
        return queue != nullptr;
    }
    bool send(const std::string &line) {
        if (owner != getpid() || line.empty() || line.size() > MaxRuntimeLogBytes) return false;
        std::unique_lock lock(mutex, std::try_to_lock);
        if (!lock.owns_lock() || !queue) return false;
        const uint32_t written = queue->written.load(std::memory_order_relaxed);
        const uint32_t read = queue->read.load(std::memory_order_acquire);
        if (written - read >= RuntimeLogSlots) return false;
        auto &record = queue->records[written % RuntimeLogSlots];
        record.size = static_cast<uint32_t>(line.size());
        memcpy(record.bytes, line.data(), line.size());
        queue->written.store(written + 1, std::memory_order_release);
        return true;
    }
private:
    RuntimeLogQueue *queue = nullptr;
    pid_t owner = 0;
    std::mutex mutex;
};

class RuntimeLogHub {
public:
    explicit RuntimeLogHub(std::function<void(const std::string &)> sink)
        : sink(std::move(sink)), worker([this] { run(); }) {}
    ~RuntimeLogHub() {
        {
            std::lock_guard lock(mutex);
            stopping = true;
        }
        changed.notify_all();
        worker.join();
        for (auto &client : clients) munmap(client.queue, sizeof(RuntimeLogQueue));
    }
    bool add(int fd, pid_t pid) {
        std::lock_guard lock(mutex);
        if (clients.size() >= 128 || pid <= 0) return false;
        auto *queue = mapRuntimeLog(fd);
        if (!queue) return false;
        clients.push_back({queue, pid});
        changed.notify_all();
        return true;
    }
private:
    struct Client { RuntimeLogQueue *queue; pid_t pid; };
    std::function<void(const std::string &)> sink;
    std::mutex mutex;
    std::condition_variable changed;
    std::vector<Client> clients;
    std::atomic<bool> stopping{false};
    std::thread worker;

    void run() {
        while (!stopping) {
            std::vector<std::string> ready;
            {
                std::unique_lock lock(mutex);
                changed.wait(lock, [this] { return stopping || !clients.empty(); });
                if (stopping) break;
                for (size_t i = clients.size(); i > 0; --i) {
                    auto &client = clients[i - 1];
                    auto *queue = client.queue;
                    uint32_t read = queue->read.load(std::memory_order_relaxed);
                    const uint32_t written = queue->written.load(std::memory_order_acquire);
                    bool remove = written - read > RuntimeLogSlots;
                    while (!remove && read != written) {
                        const auto &record = queue->records[read % RuntimeLogSlots];
                        const uint32_t size = record.size;
                        if (!size || size > MaxRuntimeLogBytes) { remove = true; break; }
                        ready.emplace_back(record.bytes, size);
                        ++read;
                    }
                    queue->read.store(read, std::memory_order_release);
                    remove = remove || queue->closed.load(std::memory_order_acquire) ||
                        (kill(client.pid, 0) != 0 && errno == ESRCH);
                    if (remove) {
                        munmap(queue, sizeof(RuntimeLogQueue));
                        clients.erase(clients.begin() + i - 1);
                    }
                }
            }
            for (const auto &line : ready) sink(line);
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
        }
    }
};
}
