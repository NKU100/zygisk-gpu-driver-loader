/* Copyright 2022-2023 John "topjohnwu" Wu
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH
 * REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY
 * AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT,
 * INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM
 * LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR
 * OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR
 * PERFORMANCE OF THIS SOFTWARE.
 */

/* Zygisk Module WebUI Template — example implementation
 *
 * Reads config.json written by the WebUI and logs according to per-package settings.
 *
 * Architecture note:
 *   preAppSpecialize() retains zygote privileges, including SELinux restrictions.
 *   Log lines are sent to the companion process (root) via IPC,
 *   and the companion appends them to module.log.
 *
 * Companion protocol (uint8_t opcode first):
 *   OP_READ_CONFIG (0): companion replies with uint32_t len + config bytes.
 *   OP_WRITE_LOG   (1): module sends uint32_t len + log line; companion returns nothing.
 *   OpenDriverOpcode (2): bounded driver ID; companion replies with a registry directory FD.
 */

#include <unistd.h>
#include <fcntl.h>
#include <android/log.h>
#include <string>
#include <cstdio>
#include <ctime>
#include <sys/stat.h>
#include <sys/socket.h>
#include <cerrno>
#include <algorithm>

#include "zygisk.hpp"
#include "yyjson.h"
#include "driver_loader.h"

using zygisk::Api;
using zygisk::AppSpecializeArgs;
using zygisk::ServerSpecializeArgs;

// Write exactly n bytes, handling partial writes.
static ssize_t write_all(int fd, const void *buf, size_t n) {
    const char *p = static_cast<const char *>(buf);
    size_t left = n;
    while (left > 0) {
        ssize_t written = write(fd, p, left);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) return written;
        p += written;
        left -= (size_t)written;
    }
    return (ssize_t)n;
}

static bool socket_transfer(int fd, void *buffer, size_t size, bool writing) {
    auto *p = static_cast<char *>(buffer);
    while (size) {
        ssize_t n = writing ? send(fd, p, size, MSG_NOSIGNAL) : read(fd, p, size);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        p += n;
        size -= n;
    }
    return true;
}

#define LOG_TAG    "ZygiskWebUI"
#ifndef MODULE_ID
#define MODULE_ID  "zygisk_sample"
#endif
#define DATA_DIR    "/data/adb/" MODULE_ID
#define CONFIG_PATH DATA_DIR "/config.json"
#define LOG_PATH    DATA_DIR "/module.log"

// IPC opcodes
static constexpr uint8_t OP_READ_CONFIG = 0;
static constexpr uint8_t OP_WRITE_LOG   = 1;

// Maximum log file size: rotate when exceeded
static constexpr off_t LOG_MAX_BYTES  = 512 * 1024;
static constexpr off_t LOG_TRIM_BYTES = 256 * 1024;

static void companion_appendLog(const std::string &line) {
    int fd = open(LOG_PATH, O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd < 0) return;

    struct stat st{};
    if (fstat(fd, &st) == 0 && st.st_size > LOG_MAX_BYTES) {
        // Keep the newest LOG_TRIM_BYTES
        char *buf = new char[LOG_TRIM_BYTES];
        ssize_t n = pread(fd, buf, LOG_TRIM_BYTES, st.st_size - LOG_TRIM_BYTES);
        if (n > 0) {
            ftruncate(fd, 0);
            lseek(fd, 0, SEEK_SET);
            write_all(fd, buf, (size_t)n);
        }
        delete[] buf;
    }

    write_all(fd, line.data(), line.size());
    close(fd);
}

static void companion_handler(int sock) {
    uint8_t op = OP_READ_CONFIG;
    if (read(sock, &op, 1) != 1) return;

    if (op == OP_READ_CONFIG) {
        std::string config;
        int cfd = open(CONFIG_PATH, O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC);
        if (cfd >= 0) {
            struct stat st{};
            if (fstat(cfd, &st) || !S_ISREG(st.st_mode) || st.st_size > gpu::MaxConfigBytes) {
                close(cfd);
                cfd = -1;
            }
        }
        if (cfd >= 0) {
            char buf[4096];
            ssize_t n;
            while ((n = read(cfd, buf, sizeof(buf))) > 0) {
                config.append(buf, n);
                if (config.size() > gpu::MaxConfigBytes) { config.clear(); break; }
            }
            if (n < 0) config.clear();
            close(cfd);
        }
        uint32_t len = (uint32_t)config.size();
        socket_transfer(sock, &len, sizeof(len), true);
        if (len > 0) socket_transfer(sock, config.data(), len, true);

    } else if (op == gpu::OpenDriverOpcode) {
        gpu::serveDriverDirectory(sock);
    } else if (op == OP_WRITE_LOG) {
        uint32_t len = 0;
        if (!socket_transfer(sock, &len, sizeof(len), false) || len == 0 || len > 65536) return;
        std::string line(len, '\0');
        ssize_t nread = 0;
        while (nread < (ssize_t)len) {
            ssize_t r = read(sock, &line[(size_t)nread], (size_t)(len - (uint32_t)nread));
            if (r <= 0) return;
            nread += r;
        }
        companion_appendLog(line);
    }
}

static char levelChar(int prio) {
    switch (prio) {
        case ANDROID_LOG_VERBOSE: return 'V';
        case ANDROID_LOG_DEBUG:   return 'D';
        case ANDROID_LOG_WARN:    return 'W';
        case ANDROID_LOG_ERROR:   return 'E';
        default:                  return 'I';
    }
}

/**
 * Build a logcat-time-format line and send it to the companion for writing.
 * Also calls __android_log_print so logcat still works normally.
 */
static void remoteLog(Api *api, int prio, const char *tag, const char *msg) {
    // Always log to logcat (works fine from app context)
    __android_log_print(prio, tag, "%s", msg);

    // Build formatted line: "MM-DD HH:MM:SS.mmm  PID  TID  L TAG: msg\n"
    struct timespec ts{};
    clock_gettime(CLOCK_REALTIME, &ts);
    struct tm tm{};
    localtime_r(&ts.tv_sec, &tm);
    int ms = (int)(ts.tv_nsec / 1000000);

    char line[4096];
    int len = snprintf(line, sizeof(line),
        "%02d-%02d %02d:%02d:%02d.%03d %5d %5d %c %-16s: %s\n",
        tm.tm_mon + 1, tm.tm_mday,
        tm.tm_hour, tm.tm_min, tm.tm_sec, ms,
        (int)getpid(), (int)gettid(),
        levelChar(prio), tag, msg);
    if (len <= 0) return;
    len = std::min(len, static_cast<int>(sizeof(line) - 1));

    // Send to companion (root) via IPC
    int sock = api->connectCompanion();
    if (sock < 0) return;

    uint8_t op = OP_WRITE_LOG;
    uint32_t msgLen = (uint32_t)len;
    socket_transfer(sock, &op, 1, true);
    socket_transfer(sock, &msgLen, sizeof(msgLen), true);
    socket_transfer(sock, line, (size_t)len, true);
    close(sock);
}

class MyModule : public zygisk::ModuleBase {
public:
    void onLoad(Api *api, JNIEnv *env) override {
        this->api = api;
        this->env = env;
    }

    void preAppSpecialize(AppSpecializeArgs *args) override {
        if (handled) return;
        handled = true;
        if (!args->nice_name) {
            api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        const char *process = env->GetStringUTFChars(args->nice_name, nullptr);
        if (!process) {
            env->ExceptionClear();
            api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        std::string processName(process);
        env->ReleaseStringUTFChars(args->nice_name, process);
        std::string dataDir;
        if (args->app_data_dir) {
            const char *data = env->GetStringUTFChars(args->app_data_dir, nullptr);
            if (data) {
                dataDir = data;
                env->ReleaseStringUTFChars(args->app_data_dir, data);
            } else {
                env->ExceptionClear();
            }
        }
        preSpecialize(processName, dataDir, args->uid, args->gid);
    }

    void preServerSpecialize(ServerSpecializeArgs *args) override {
        api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
    }

private:
    Api *api = nullptr;
    JNIEnv *env = nullptr;
    bool handled = false;
    gpu::DriverLoader driverLoader;

    void preSpecialize(const std::string &processName, const std::string &dataDir, uid_t uid, gid_t gid) {
        // Read config from companion (OP_READ_CONFIG)
        std::string configJson;
        int sock = api->connectCompanion();
        if (sock >= 0) {
            timeval timeout{2, 0};
            setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
            setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
            uint8_t op = OP_READ_CONFIG;
            socket_transfer(sock, &op, 1, true);
            uint32_t len = 0;
            if (socket_transfer(sock, &len, sizeof(len), false) && len > 0 && len <= gpu::MaxConfigBytes) {
                configJson.resize(len);
                if (!socket_transfer(sock, configJson.data(), len, false)) configJson.clear();
            }
            close(sock);
        }

        const auto selection = gpu::selectDriver(configJson, processName);
        const auto &result = driverLoader.load(api, selection, dataDir, uid, gid);
        std::string diagnostic = "process=" + processName + " status=" + gpu::statusName(result.status) +
            " reason=" + result.reason + " driverPath=" + result.driverPath + " hookPath=" + result.hookPath;
        if (result.status != gpu::DriverLoadStatus::Loaded) {
            diagnostic += "; fallback to system driver";
            api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
        }
        if (result.status == gpu::DriverLoadStatus::NotTargeted) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "%s", diagnostic.c_str());
            return;
        }
        remoteLog(api, ANDROID_LOG_INFO, LOG_TAG, diagnostic.c_str());

        yyjson_doc *doc = yyjson_read(configJson.data(), configJson.size(), 0);
        if (!doc) {
            return;
        }
        yyjson_val *root = yyjson_doc_get_root(doc);
        const char *process = selection.packageName.c_str();

        // Per-package settings
        const char *logLevel = "INFO";
        const char *logTag   = process;
        bool dumpStack       = false;

        yyjson_val *pkgSettings = yyjson_obj_get(root, "packageSettings");
        if (yyjson_is_obj(pkgSettings)) {
            yyjson_val *thisPkg = yyjson_obj_get(pkgSettings, process);
            if (yyjson_is_obj(thisPkg)) {
                yyjson_val *v;
                v = yyjson_obj_get(thisPkg, "logLevel");
                if (yyjson_is_str(v)) logLevel = yyjson_get_str(v);
                v = yyjson_obj_get(thisPkg, "logTag");
                if (yyjson_is_str(v) && yyjson_get_len(v) > 0) logTag = yyjson_get_str(v);
                v = yyjson_obj_get(thisPkg, "dumpStackTrace");
                if (yyjson_is_bool(v)) dumpStack = yyjson_get_bool(v);
            }
        }

        int prio = (strcmp(logLevel, "DEBUG") == 0) ? ANDROID_LOG_DEBUG
                 : (strcmp(logLevel, "WARN")  == 0) ? ANDROID_LOG_WARN
                 :                                     ANDROID_LOG_INFO;

        char msgBuf[2048];
        snprintf(msgBuf, sizeof(msgBuf),
            "[ZygiskWebUI] process=%s logLevel=%s dumpStackTrace=%s",
            process, logLevel, dumpStack ? "true" : "false");
        remoteLog(api, prio, logTag, msgBuf);

        if (dumpStack) {
            snprintf(msgBuf, sizeof(msgBuf),
                "[ZygiskWebUI] stack trace requested for %s (implement hook here)", process);
            remoteLog(api, prio, logTag, msgBuf);
        }

        yyjson_doc_free(doc);
    }
};

REGISTER_ZYGISK_MODULE(MyModule)
REGISTER_ZYGISK_COMPANION(companion_handler)
