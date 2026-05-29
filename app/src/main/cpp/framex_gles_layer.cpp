// FrameX GLES instrumentation layer (Android GLES layer interface).
//
// Injected into OpenGL ES games. Unlike Vulkan, GLES is an immediate-mode API with no command
// buffers or encoders, so the per-encoder CPU breakdown does not apply (those fields are reported
// as -1 = "n/a"). What we *can* measure accurately:
//
//   - Frame interval / FPS -> CPU delta between eglSwapBuffers calls.
//   - GPU time (ms)        -> a GL_TIME_ELAPSED_EXT timer query bracketing each frame's GL work,
//                             read back one frame later. Requires GL_EXT_disjoint_timer_query.
//
// The Android GLES layer entry points are AndroidGLESLayer_Initialize / _GetProcAddress.

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/log.h>

#include <cstring>

#include "framex_ipc.h"

#define FX_TAG "FrameXGlesLayer"
#define FX_LOGI(...) __android_log_print(ANDROID_LOG_INFO, FX_TAG, __VA_ARGS__)

// Fallbacks in case the EXT enums aren't visible in the toolchain headers.
#ifndef GL_TIME_ELAPSED_EXT
#define GL_TIME_ELAPSED_EXT 0x88BF
#endif
#ifndef GL_QUERY_RESULT_EXT
#define GL_QUERY_RESULT_EXT 0x8866
#endif
#ifndef GL_QUERY_RESULT_AVAILABLE_EXT
#define GL_QUERY_RESULT_AVAILABLE_EXT 0x8867
#endif

namespace {

using PFNEGLGETNEXTLAYERPROCADDRESSPROC = void* (*)(void*, const char*);

void* g_layerId = nullptr;
PFNEGLGETNEXTLAYERPROCADDRESSPROC g_getNextProc = nullptr;

// The real (next-layer) EGL entry points we wrap.
using PFN_eglSwapBuffers = EGLBoolean (*)(EGLDisplay, EGLSurface);
PFN_eglSwapBuffers g_nextSwapBuffers = nullptr;

// EXT_disjoint_timer_query function pointers (resolved lazily once a context is current).
PFNGLGENQUERIESEXTPROC g_glGenQueriesEXT = nullptr;
PFNGLBEGINQUERYEXTPROC g_glBeginQueryEXT = nullptr;
PFNGLENDQUERYEXTPROC g_glEndQueryEXT = nullptr;
PFNGLGETQUERYOBJECTUI64VEXTPROC g_glGetQueryObjectui64vEXT = nullptr;
PFNGLGETQUERYOBJECTIVEXTPROC g_glGetQueryObjectivEXT = nullptr;

constexpr int kRing = 4;
GLuint g_queries[kRing] = {0, 0, 0, 0};
bool g_queriesReady = false;
bool g_timerSupported = true;  // set false if resolution fails
int g_writeIdx = 0;            // ring slot for the query we begin this frame
bool g_queryActive = false;    // a query is currently open (begun last frame)

uint64_t g_lastSwapNs = 0;
double g_lastGpuMs = -1.0;

template <typename T>
T resolve(const char* name) {
    return g_getNextProc ? reinterpret_cast<T>(g_getNextProc(g_layerId, name)) : nullptr;
}

void ensureTimerFns() {
    if (g_queriesReady || !g_timerSupported) return;
    g_glGenQueriesEXT = resolve<PFNGLGENQUERIESEXTPROC>("glGenQueriesEXT");
    g_glBeginQueryEXT = resolve<PFNGLBEGINQUERYEXTPROC>("glBeginQueryEXT");
    g_glEndQueryEXT = resolve<PFNGLENDQUERYEXTPROC>("glEndQueryEXT");
    g_glGetQueryObjectui64vEXT = resolve<PFNGLGETQUERYOBJECTUI64VEXTPROC>("glGetQueryObjectui64vEXT");
    g_glGetQueryObjectivEXT = resolve<PFNGLGETQUERYOBJECTIVEXTPROC>("glGetQueryObjectivEXT");

    if (!g_glGenQueriesEXT || !g_glBeginQueryEXT || !g_glEndQueryEXT ||
        !g_glGetQueryObjectui64vEXT || !g_glGetQueryObjectivEXT) {
        g_timerSupported = false;  // extension unavailable; GPU time stays "n/a"
        FX_LOGI("GL_EXT_disjoint_timer_query unavailable; GPU time disabled");
        return;
    }
    g_glGenQueriesEXT(kRing, g_queries);
    g_queriesReady = true;
}

// Try to read the GPU time from a query that finished a couple of frames ago.
void readDeferredGpu(int idx) {
    if (!g_timerSupported || !g_queriesReady || g_queries[idx] == 0) return;
    GLint available = 0;
    g_glGetQueryObjectivEXT(g_queries[idx], GL_QUERY_RESULT_AVAILABLE_EXT, &available);
    if (!available) return;
    GLuint64 ns = 0;
    g_glGetQueryObjectui64vEXT(g_queries[idx], GL_QUERY_RESULT_EXT, &ns);
    g_lastGpuMs = static_cast<double>(ns) / 1000000.0;
}

EGLBoolean FX_eglSwapBuffers(EGLDisplay dpy, EGLSurface surface) {
    ensureTimerFns();
    const uint64_t now = framex_now_ns();

    // Close the query that has been bracketing this frame's GL commands.
    if (g_timerSupported && g_queriesReady && g_queryActive) {
        g_glEndQueryEXT(GL_TIME_ELAPSED_EXT);
        g_queryActive = false;
    }

    FrameXSample s{};
    s.api = "OpenGL";
    s.frameIntervalMs = g_lastSwapNs ? static_cast<double>(now - g_lastSwapNs) / 1000000.0 : 0.0;
    s.presentDelayMs = -1.0;       // n/a for GLES
    s.cmdBufferCpuMs = -1.0;       // n/a — no command buffers
    s.renderEncoderCpuMs = -1.0;   // n/a — no encoders
    s.computeEncoderCpuMs = -1.0;
    s.blitEncoderMs = -1.0;

    // Read the GPU result from the query two slots back (it has had time to resolve).
    if (g_timerSupported && g_queriesReady) {
        const int readIdx = (g_writeIdx + 1) % kRing;
        readDeferredGpu(readIdx);
    }
    s.gpuMs = g_lastGpuMs;  // -1 if unsupported/not ready yet

    framex_ipc_send(s);
    g_lastSwapNs = now;

    const EGLBoolean result = g_nextSwapBuffers ? g_nextSwapBuffers(dpy, surface) : EGL_FALSE;

    // Open a new timer query that will bracket the *next* frame's GL work.
    if (g_timerSupported && g_queriesReady) {
        g_writeIdx = (g_writeIdx + 1) % kRing;
        g_glBeginQueryEXT(GL_TIME_ELAPSED_EXT, g_queries[g_writeIdx]);
        g_queryActive = true;
    }
    return result;
}

}  // namespace

extern "C" {

__attribute__((visibility("default"))) void AndroidGLESLayer_Initialize(
    void* layer_id, PFNEGLGETNEXTLAYERPROCADDRESSPROC get_next_layer_proc_address) {
    g_layerId = layer_id;
    g_getNextProc = get_next_layer_proc_address;
    FX_LOGI("FrameX GLES layer initialized");
}

__attribute__((visibility("default"))) void* AndroidGLESLayer_GetProcAddress(const char* funcName,
                                                                             void* next) {
    if (strcmp(funcName, "eglSwapBuffers") == 0) {
        g_nextSwapBuffers = reinterpret_cast<PFN_eglSwapBuffers>(next);
        return reinterpret_cast<void*>(FX_eglSwapBuffers);
    }
    // Everything else passes straight through to the next layer / driver.
    return next;
}

}  // extern "C"
