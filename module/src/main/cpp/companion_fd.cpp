#include "companion_fd.h"

#include <cerrno>
#include <algorithm>
#include <cstring>
#include <fcntl.h>
#include <limits.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>

namespace gpu::companion_fd {

bool populateSnapshot(int source, int destination, off_t limit) {
    struct stat input{}, output{};
    if (fstat(source, &input) || fstat(destination, &output) || !S_ISREG(input.st_mode) ||
        !S_ISREG(output.st_mode) || input.st_size <= 0 || input.st_size > limit ||
        output.st_size != 0 || output.st_nlink != 0) return false;
    char buffer[16384];
    for (off_t offset = 0; offset < input.st_size;) {
        ssize_t count = pread(source, buffer, std::min<off_t>(sizeof(buffer), input.st_size - offset), offset);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return false;
        for (ssize_t written = 0; written < count;) {
            ssize_t n = pwrite(destination, buffer + written, count - written, offset + written);
            if (n < 0 && errno == EINTR) continue;
            if (n <= 0) return false;
            written += n;
        }
        offset += count;
    }
    return fstat(destination, &output) == 0 && output.st_size == input.st_size;
}
namespace {

bool transferRead(int socket, void *buffer, size_t size) {
    auto *cursor = static_cast<char *>(buffer);
    while (size) {
        ssize_t count = read(socket, cursor, size);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return false;
        cursor += count;
        size -= static_cast<size_t>(count);
    }
    return true;
}

bool transferWrite(int socket, const void *buffer, size_t size) {
    auto *cursor = static_cast<const char *>(buffer);
    while (size) {
        ssize_t count = send(socket, cursor, size, MSG_NOSIGNAL);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return false;
        cursor += count;
        size -= static_cast<size_t>(count);
    }
    return true;
}

void closeReceivedDescriptors(msghdr &message) {
    for (cmsghdr *header = CMSG_FIRSTHDR(&message); header; header = CMSG_NXTHDR(&message, header)) {
        if (header->cmsg_level != SOL_SOCKET || header->cmsg_type != SCM_RIGHTS ||
            header->cmsg_len < CMSG_LEN(0)) continue;
        size_t bytes = header->cmsg_len - CMSG_LEN(0);
        auto *descriptors = reinterpret_cast<int *>(CMSG_DATA(header));
        for (size_t index = 0; index < bytes / sizeof(int); ++index) close(descriptors[index]);
    }
}

}

bool sendText(int socket, const std::string &text, size_t limit) {
    if (text.size() > limit || text.size() > UINT32_MAX) return false;
    uint32_t size = static_cast<uint32_t>(text.size());
    return transferWrite(socket, &size, sizeof(size)) && transferWrite(socket, text.data(), size);
}

bool receiveText(int socket, std::string &text, size_t limit) {
    uint32_t size = 0;
    if (!transferRead(socket, &size, sizeof(size)) || size > limit) return false;
    text.resize(size);
    return transferRead(socket, text.data(), size);
}

bool sendFileRequest(int socket, const FileRequest &request) {
    if (request.driverId.empty() || request.driverId.size() > NAME_MAX ||
        request.fileName.empty() || request.fileName.size() > NAME_MAX) return false;
    uint32_t idSize = static_cast<uint32_t>(request.driverId.size());
    uint32_t nameSize = static_cast<uint32_t>(request.fileName.size());
    uint8_t opcode = request.opcode;
    uint8_t kind = request.kind;
    return transferWrite(socket, &opcode, sizeof(opcode)) &&
        transferWrite(socket, &kind, sizeof(kind)) &&
        transferWrite(socket, &idSize, sizeof(idSize)) &&
        transferWrite(socket, &nameSize, sizeof(nameSize)) &&
        transferWrite(socket, request.driverId.data(), idSize) &&
        transferWrite(socket, request.fileName.data(), nameSize);
}

bool receiveFileRequest(int socket, FileRequest &request) {
    if (!transferRead(socket, &request.opcode, sizeof(request.opcode))) return false;
    return receiveFileRequest(socket, request, request.opcode);
}

bool receiveFileRequest(int socket, FileRequest &request, uint8_t opcode) {
    request.opcode = opcode;
    uint32_t idSize = 0;
    uint32_t nameSize = 0;
    if (!transferRead(socket, &request.kind, sizeof(request.kind)) ||
        !transferRead(socket, &idSize, sizeof(idSize)) ||
        !transferRead(socket, &nameSize, sizeof(nameSize)) ||
        idSize == 0 || idSize > NAME_MAX || nameSize == 0 || nameSize > NAME_MAX) return false;
    request.driverId.resize(idSize);
    request.fileName.resize(nameSize);
    return transferRead(socket, request.driverId.data(), idSize) &&
        transferRead(socket, request.fileName.data(), nameSize);
}

bool sendFileReply(int socket, int fileFd) {
    char status = fileFd >= 0 ? 1 : 0;
    iovec io{&status, sizeof(status)};
    alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int))]{};
    msghdr message{};
    message.msg_iov = &io;
    message.msg_iovlen = 1;
    if (fileFd >= 0) {
        message.msg_control = control;
        message.msg_controllen = sizeof(control);
        cmsghdr *header = CMSG_FIRSTHDR(&message);
        header->cmsg_level = SOL_SOCKET;
        header->cmsg_type = SCM_RIGHTS;
        header->cmsg_len = CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(header), &fileFd, sizeof(fileFd));
    }
    ssize_t sent;
    do {
        sent = sendmsg(socket, &message, MSG_NOSIGNAL);
    } while (sent < 0 && errno == EINTR);
    return sent == 1;
}

int receiveFileReply(int socket) {
    char status = 0;
    iovec io{&status, sizeof(status)};
    alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int))]{};
    msghdr message{};
    message.msg_iov = &io;
    message.msg_iovlen = 1;
    message.msg_control = control;
    message.msg_controllen = sizeof(control);
    ssize_t received;
#ifdef MSG_CMSG_CLOEXEC
    constexpr int receiveFlags = MSG_CMSG_CLOEXEC;
#else
    constexpr int receiveFlags = 0;
#endif
    do {
        received = recvmsg(socket, &message, receiveFlags);
    } while (received < 0 && errno == EINTR);
    if (received != 1) {
        closeReceivedDescriptors(message);
        return -1;
    }
    cmsghdr *header = CMSG_FIRSTHDR(&message);
    if (!header || header->cmsg_level != SOL_SOCKET || header->cmsg_type != SCM_RIGHTS ||
        header->cmsg_len != CMSG_LEN(sizeof(int)) || CMSG_NXTHDR(&message, header) ||
        (message.msg_flags & MSG_CTRUNC)) {
        closeReceivedDescriptors(message);
        return -1;
    }
    int fileFd = -1;
    memcpy(&fileFd, CMSG_DATA(header), sizeof(fileFd));
#ifndef MSG_CMSG_CLOEXEC
    if (fcntl(fileFd, F_SETFD, FD_CLOEXEC) < 0) {
        close(fileFd);
        return -1;
    }
#endif
    if (status != 1) {
        close(fileFd);
        return -1;
    }
    return fileFd;
}

}
