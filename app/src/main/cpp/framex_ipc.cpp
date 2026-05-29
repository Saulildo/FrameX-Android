#include "framex_ipc.h"

#include <android/log.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <cstddef>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <mutex>

#define FX_TAG "FrameXLayerIPC"
#define FX_LOGW(...) __android_log_print(ANDROID_LOG_WARN, FX_TAG, __VA_ARGS__)

namespace {

// Must match the LocalServerSocket name on the Kotlin side (HudIpcServer).
constexpr char kSocketName[] = "framex_hud";

std::mutex g_mutex;
int g_fd = -1;
uint64_t g_lastConnectAttemptNs = 0;

// Connect to the HUD's abstract UNIX domain socket. Returns true if a usable fd is held.
bool connect_locked() {
    if (g_fd >= 0) return true;

    // Throttle reconnect attempts to ~once per second so a missing server doesn't burn CPU
    // on every frame (the HUD may not be listening yet, or may have stopped).
    uint64_t now = framex_now_ns();
    if (g_lastConnectAttemptNs != 0 && (now - g_lastConnectAttemptNs) < 1000000000ULL) {
        return false;
    }
    g_lastConnectAttemptNs = now;

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return false;

    sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    // Abstract namespace: a leading NUL byte, then the name (not NUL-terminated in the path).
    addr.sun_path[0] = '\0';
    const size_t nameLen = sizeof(kSocketName) - 1;
    memcpy(addr.sun_path + 1, kSocketName, nameLen);
    const socklen_t len =
        static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + nameLen);

    if (connect(fd, reinterpret_cast<sockaddr*>(&addr), len) < 0) {
        close(fd);
        return false;
    }
    g_fd = fd;
    return true;
}

}  // namespace

uint64_t framex_now_ns() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000000000ULL + static_cast<uint64_t>(ts.tv_nsec);
}

void framex_ipc_send(const FrameXSample& s) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (!connect_locked()) return;

    char buf[512];
    const int n = snprintf(
        buf, sizeof(buf), "%s,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f\n",
        s.api, s.gpuMs, s.presentDelayMs, s.frameIntervalMs,
        s.cmdBufferCpuMs, s.renderEncoderCpuMs, s.computeEncoderCpuMs, s.blitEncoderMs);
    if (n <= 0) return;

    // MSG_NOSIGNAL: never raise SIGPIPE inside the game's process if the HUD goes away.
    const ssize_t written = send(g_fd, buf, static_cast<size_t>(n), MSG_NOSIGNAL);
    if (written < 0) {
        close(g_fd);
        g_fd = -1;  // Force a reconnect next frame.
    }
}
