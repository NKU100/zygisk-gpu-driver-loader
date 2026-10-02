#include "../companion_fd.h"

#include <cassert>
#include <cstdlib>
#include <cstring>
#include <string>
#include <sys/socket.h>
#include <thread>
#include <unistd.h>

int main() {
    char path[] = "/tmp/zygisk-companion-fd-XXXXXX";
    int source = mkstemp(path);
    assert(source >= 0);
    constexpr char expected[] = "metadata transferred through SCM_RIGHTS";
    assert(write(source, expected, sizeof(expected) - 1) == sizeof(expected) - 1);
    assert(lseek(source, 0, SEEK_SET) == 0);

    int sockets[2];
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) == 0);
    std::thread companion([&] {
        uint8_t opcode = 0;
        assert(read(sockets[1], &opcode, sizeof(opcode)) == sizeof(opcode));
        gpu::companion_fd::FileRequest request;
        assert(gpu::companion_fd::receiveFileRequest(sockets[1], request, opcode));
        assert(request.opcode == 2);
        assert(request.kind == 1);
        assert(request.driverId == "fixture-id");
        assert(request.fileName == "meta.json");
        assert(gpu::companion_fd::sendFileReply(sockets[1], source));
        close(sockets[1]);
    });

    gpu::companion_fd::FileRequest request{2, 1, "fixture-id", "meta.json"};
    assert(gpu::companion_fd::sendFileRequest(sockets[0], request));
    int received = gpu::companion_fd::receiveFileReply(sockets[0]);
    assert(received >= 0);
    char contents[sizeof(expected)]{};
    assert(read(received, contents, sizeof(contents) - 1) == sizeof(expected) - 1);
    assert(std::string(contents) == expected);

    char snapshotPath[] = "/tmp/zygisk-snapshot-XXXXXX";
    int snapshot = mkstemp(snapshotPath);
    assert(snapshot >= 0);
    assert(!gpu::companion_fd::populateSnapshot(source, snapshot, 1024));
    assert(unlink(snapshotPath) == 0);
    assert(!gpu::companion_fd::populateSnapshot(source, snapshot, 1));
    assert(gpu::companion_fd::populateSnapshot(source, snapshot, 1024));
    char snapshotContents[sizeof(expected)]{};
    assert(pread(snapshot, snapshotContents, sizeof(expected) - 1, 0) == sizeof(expected) - 1);
    assert(std::string(snapshotContents) == expected);
    assert(!gpu::companion_fd::populateSnapshot(source, snapshot, 1024));
    close(snapshot);

    close(received);
    close(sockets[0]);
    close(source);
    unlink(path);
    companion.join();

    int textSockets[2];
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, textSockets) == 0);
    assert(gpu::companion_fd::sendText(textSockets[0], "prepared paths", 64));
    std::string message;
    assert(gpu::companion_fd::receiveText(textSockets[1], message, 64));
    assert(message == "prepared paths");
    assert(!gpu::companion_fd::sendText(textSockets[0], std::string(65, 'x'), 64));
    assert(gpu::companion_fd::sendText(textSockets[0], std::string(65, 'x'), 128));
    assert(!gpu::companion_fd::receiveText(textSockets[1], message, 64));
    close(textSockets[0]);
    close(textSockets[1]);
}
