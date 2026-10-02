#pragma once

#include <cstdint>
#include <string>
#include <sys/types.h>

namespace gpu::companion_fd {

inline constexpr uint8_t MetadataFile = 1;
inline constexpr uint8_t LibraryFile = 2;

struct FileRequest {
    uint8_t opcode = 0;
    uint8_t kind = 0;
    std::string driverId;
    std::string fileName;
};

bool sendFileRequest(int socket, const FileRequest &request);
bool receiveFileRequest(int socket, FileRequest &request);
bool receiveFileRequest(int socket, FileRequest &request, uint8_t opcode);
bool sendFileReply(int socket, int fileFd);
int receiveFileReply(int socket);
bool populateSnapshot(int source, int destination, off_t limit);
bool sendText(int socket, const std::string &text, size_t limit);
bool receiveText(int socket, std::string &text, size_t limit);

}
