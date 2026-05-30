#include "zygisk.hpp"
#include "framex_ipc.h"

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <link.h>
#include <pthread.h>
#include <sys/mman.h>
#include <unistd.h>

#include <cstdint>
#include <cstdio>
#include <cstring>

#define FX_TAG "FrameXZygisk"
#define FX_LOGI(...) __android_log_print(ANDROID_LOG_INFO, FX_TAG, __VA_ARGS__)
#define FX_LOGW(...) __android_log_print(ANDROID_LOG_WARN, FX_TAG, __VA_ARGS__)

#ifndef GL_TIME_ELAPSED_EXT
#define GL_TIME_ELAPSED_EXT 0x88BF
#endif
#ifndef GL_QUERY_RESULT_EXT
#define GL_QUERY_RESULT_EXT 0x8866
#endif
#ifndef GL_QUERY_RESULT_AVAILABLE_EXT
#define GL_QUERY_RESULT_AVAILABLE_EXT 0x8867
#endif

#if defined(__LP64__)
#define FRAMEX_R_SYM ELF64_R_SYM
#else
#define FRAMEX_R_SYM ELF32_R_SYM
#endif

namespace {

using PFN_eglSwapBuffers = EGLBoolean (*)(EGLDisplay, EGLSurface);
using PFN_eglSwapBuffersWithDamage = EGLBoolean (*)(EGLDisplay, EGLSurface, EGLint*, EGLint);

using VkQueue = void*;
using VkResult = int32_t;
struct VkPresentInfoKHR;
using PFN_vkQueuePresentKHR = VkResult (*)(VkQueue, const VkPresentInfoKHR*);

PFN_eglSwapBuffers g_nextSwapBuffers = nullptr;
PFN_eglSwapBuffersWithDamage g_nextSwapBuffersWithDamageKHR = nullptr;
PFN_eglSwapBuffersWithDamage g_nextSwapBuffersWithDamageEXT = nullptr;
PFN_vkQueuePresentKHR g_nextQueuePresent = nullptr;

PFNGLGENQUERIESEXTPROC g_glGenQueriesEXT = nullptr;
PFNGLBEGINQUERYEXTPROC g_glBeginQueryEXT = nullptr;
PFNGLENDQUERYEXTPROC g_glEndQueryEXT = nullptr;
PFNGLGETQUERYOBJECTUI64VEXTPROC g_glGetQueryObjectui64vEXT = nullptr;
PFNGLGETQUERYOBJECTIVEXTPROC g_glGetQueryObjectivEXT = nullptr;

constexpr int kQueryRing = 4;
GLuint g_queries[kQueryRing] = {0, 0, 0, 0};
bool g_queriesReady = false;
bool g_timerSupported = true;
bool g_queryActive = false;
int g_writeIdx = 0;
double g_lastGpuMs = -1.0;
uint64_t g_lastOpenGlSwapNs = 0;
uint64_t g_lastVulkanPresentNs = 0;
pthread_mutex_t g_frameMutex = PTHREAD_MUTEX_INITIALIZER;

struct HookedObject {
    ElfW(Addr) base;
    const char *name;
};

HookedObject g_hookedObjects[512] = {};
int g_hookedObjectCount = 0;
pthread_mutex_t g_patchMutex = PTHREAD_MUTEX_INITIALIZER;

bool g_targetProcess = false;
char g_processName[128] = {};

bool readTarget(char *out, size_t outSize) {
    FILE *file = fopen("/data/adb/modules/framex_zygisk/target.txt", "r");
    if (!file) {
        snprintf(out, outSize, "com.roblox.client");
        return true;
    }
    if (!fgets(out, static_cast<int>(outSize), file)) {
        fclose(file);
        out[0] = '\0';
        return false;
    }
    fclose(file);
    out[strcspn(out, "\r\n\t ")] = '\0';
    return out[0] != '\0';
}

bool processMatchesTarget(const char *processName) {
    if (!processName || !processName[0]) return false;
    char target[128] = {};
    if (!readTarget(target, sizeof(target))) return false;
    const size_t targetLen = strlen(target);
    return strcmp(processName, target) == 0 ||
        (strncmp(processName, target, targetLen) == 0 && processName[targetLen] == ':');
}

void ensureTimerFns() {
    if (g_queriesReady || !g_timerSupported) return;
    g_glGenQueriesEXT = reinterpret_cast<PFNGLGENQUERIESEXTPROC>(eglGetProcAddress("glGenQueriesEXT"));
    g_glBeginQueryEXT = reinterpret_cast<PFNGLBEGINQUERYEXTPROC>(eglGetProcAddress("glBeginQueryEXT"));
    g_glEndQueryEXT = reinterpret_cast<PFNGLENDQUERYEXTPROC>(eglGetProcAddress("glEndQueryEXT"));
    g_glGetQueryObjectui64vEXT =
        reinterpret_cast<PFNGLGETQUERYOBJECTUI64VEXTPROC>(eglGetProcAddress("glGetQueryObjectui64vEXT"));
    g_glGetQueryObjectivEXT =
        reinterpret_cast<PFNGLGETQUERYOBJECTIVEXTPROC>(eglGetProcAddress("glGetQueryObjectivEXT"));

    if (!g_glGenQueriesEXT || !g_glBeginQueryEXT || !g_glEndQueryEXT ||
        !g_glGetQueryObjectui64vEXT || !g_glGetQueryObjectivEXT) {
        g_timerSupported = false;
        FX_LOGI("GL_EXT_disjoint_timer_query unavailable; GPU time disabled");
        return;
    }
    g_glGenQueriesEXT(kQueryRing, g_queries);
    g_queriesReady = true;
}

void readDeferredGpu(int idx) {
    if (!g_timerSupported || !g_queriesReady || g_queries[idx] == 0) return;
    GLint available = 0;
    g_glGetQueryObjectivEXT(g_queries[idx], GL_QUERY_RESULT_AVAILABLE_EXT, &available);
    if (!available) return;
    GLuint64 ns = 0;
    g_glGetQueryObjectui64vEXT(g_queries[idx], GL_QUERY_RESULT_EXT, &ns);
    g_lastGpuMs = static_cast<double>(ns) / 1000000.0;
}

void beforeOpenGlSwap() {
    pthread_mutex_lock(&g_frameMutex);
    ensureTimerFns();
    const uint64_t now = framex_now_ns();

    if (g_timerSupported && g_queriesReady && g_queryActive) {
        g_glEndQueryEXT(GL_TIME_ELAPSED_EXT);
        g_queryActive = false;
    }

    if (g_timerSupported && g_queriesReady) {
        const int readIdx = (g_writeIdx + 1) % kQueryRing;
        readDeferredGpu(readIdx);
    }

    FrameXSample sample{};
    sample.api = "OpenGL";
    sample.gpuMs = g_lastGpuMs;
    sample.presentDelayMs = -1.0;
    sample.frameIntervalMs =
        g_lastOpenGlSwapNs ? static_cast<double>(now - g_lastOpenGlSwapNs) / 1000000.0 : 0.0;
    sample.cmdBufferCpuMs = -1.0;
    sample.renderEncoderCpuMs = -1.0;
    sample.computeEncoderCpuMs = -1.0;
    sample.blitEncoderMs = -1.0;
    framex_ipc_send(sample);
    g_lastOpenGlSwapNs = now;
    pthread_mutex_unlock(&g_frameMutex);
}

void afterOpenGlSwap() {
    pthread_mutex_lock(&g_frameMutex);
    if (g_timerSupported && g_queriesReady) {
        g_writeIdx = (g_writeIdx + 1) % kQueryRing;
        g_glBeginQueryEXT(GL_TIME_ELAPSED_EXT, g_queries[g_writeIdx]);
        g_queryActive = true;
    }
    pthread_mutex_unlock(&g_frameMutex);
}

void sendVulkanPresent() {
    const uint64_t now = framex_now_ns();
    FrameXSample sample{};
    sample.api = "Vulkan";
    sample.gpuMs = -1.0;
    sample.presentDelayMs = -1.0;
    sample.frameIntervalMs =
        g_lastVulkanPresentNs ? static_cast<double>(now - g_lastVulkanPresentNs) / 1000000.0 : 0.0;
    sample.cmdBufferCpuMs = -1.0;
    sample.renderEncoderCpuMs = -1.0;
    sample.computeEncoderCpuMs = -1.0;
    sample.blitEncoderMs = -1.0;
    framex_ipc_send(sample);
    g_lastVulkanPresentNs = now;
}

EGLBoolean FX_eglSwapBuffers(EGLDisplay display, EGLSurface surface) {
    beforeOpenGlSwap();
    const EGLBoolean result = g_nextSwapBuffers ? g_nextSwapBuffers(display, surface) : EGL_FALSE;
    afterOpenGlSwap();
    return result;
}

EGLBoolean FX_eglSwapBuffersWithDamageKHR(EGLDisplay display, EGLSurface surface, EGLint *rects, EGLint nRects) {
    beforeOpenGlSwap();
    const EGLBoolean result = g_nextSwapBuffersWithDamageKHR ?
        g_nextSwapBuffersWithDamageKHR(display, surface, rects, nRects) : EGL_FALSE;
    afterOpenGlSwap();
    return result;
}

EGLBoolean FX_eglSwapBuffersWithDamageEXT(EGLDisplay display, EGLSurface surface, EGLint *rects, EGLint nRects) {
    beforeOpenGlSwap();
    const EGLBoolean result = g_nextSwapBuffersWithDamageEXT ?
        g_nextSwapBuffersWithDamageEXT(display, surface, rects, nRects) : EGL_FALSE;
    afterOpenGlSwap();
    return result;
}

VkResult FX_vkQueuePresentKHR(VkQueue queue, const VkPresentInfoKHR *presentInfo) {
    sendVulkanPresent();
    return g_nextQueuePresent ? g_nextQueuePresent(queue, presentInfo) : 0;
}

bool isCandidateObject(const char *name) {
    if (!name || !name[0]) return false;
    if (strstr(name, "/system/") || strstr(name, "/apex/") || strstr(name, "/vendor/")) return false;
    if (strstr(name, "/data/adb/modules/framex_zygisk/")) return false;
    return strstr(name, "/data/app/") || strstr(name, "/data/user/") || strstr(name, "/data/data/");
}

bool alreadyHooked(ElfW(Addr) base) {
    for (int i = 0; i < g_hookedObjectCount; ++i) {
        if (g_hookedObjects[i].base == base) return true;
    }
    return false;
}

void rememberHooked(ElfW(Addr) base, const char *name) {
    if (g_hookedObjectCount >= static_cast<int>(sizeof(g_hookedObjects) / sizeof(g_hookedObjects[0]))) return;
    g_hookedObjects[g_hookedObjectCount++] = HookedObject{base, name};
}

void *dynamicPtr(ElfW(Addr) base, ElfW(Addr) value) {
    if (value == 0) return nullptr;
    if (value < base) return reinterpret_cast<void*>(base + value);
    return reinterpret_cast<void*>(value);
}

bool patchSlot(void **slot, void *replacement, void **original) {
    if (!slot || *slot == replacement) return false;
    if (original && *original == nullptr) *original = *slot;

    const long pageSize = sysconf(_SC_PAGESIZE);
    const uintptr_t page = reinterpret_cast<uintptr_t>(slot) & ~(static_cast<uintptr_t>(pageSize) - 1);
    if (mprotect(reinterpret_cast<void*>(page), static_cast<size_t>(pageSize), PROT_READ | PROT_WRITE) != 0) {
        return false;
    }
    *slot = replacement;
    __builtin___clear_cache(reinterpret_cast<char*>(slot), reinterpret_cast<char*>(slot + 1));
    mprotect(reinterpret_cast<void*>(page), static_cast<size_t>(pageSize), PROT_READ | PROT_WRITE);
    return true;
}

bool patchSymbol(const char *name, void **slot) {
    if (strcmp(name, "eglSwapBuffers") == 0) {
        return patchSlot(slot, reinterpret_cast<void*>(FX_eglSwapBuffers),
            reinterpret_cast<void**>(&g_nextSwapBuffers));
    }
    if (strcmp(name, "eglSwapBuffersWithDamageKHR") == 0) {
        return patchSlot(slot, reinterpret_cast<void*>(FX_eglSwapBuffersWithDamageKHR),
            reinterpret_cast<void**>(&g_nextSwapBuffersWithDamageKHR));
    }
    if (strcmp(name, "eglSwapBuffersWithDamageEXT") == 0) {
        return patchSlot(slot, reinterpret_cast<void*>(FX_eglSwapBuffersWithDamageEXT),
            reinterpret_cast<void**>(&g_nextSwapBuffersWithDamageEXT));
    }
    if (strcmp(name, "vkQueuePresentKHR") == 0) {
        return patchSlot(slot, reinterpret_cast<void*>(FX_vkQueuePresentKHR),
            reinterpret_cast<void**>(&g_nextQueuePresent));
    }
    return false;
}

template <typename Rel>
int patchRelocations(ElfW(Addr) base, Rel *rels, size_t count, ElfW(Sym) *symtab, const char *strtab) {
    if (!rels || !symtab || !strtab) return 0;
    int patched = 0;
    for (size_t i = 0; i < count; ++i) {
        const size_t symIndex = FRAMEX_R_SYM(rels[i].r_info);
        const char *name = strtab + symtab[symIndex].st_name;
        if (!name || !name[0]) continue;
        void **slot = reinterpret_cast<void**>(base + rels[i].r_offset);
        if (patchSymbol(name, slot)) ++patched;
    }
    return patched;
}

int patchObject(const dl_phdr_info *info) {
    if (!info || !isCandidateObject(info->dlpi_name) || alreadyHooked(info->dlpi_addr)) return 0;

    ElfW(Dyn) *dynamic = nullptr;
    for (int i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr) &phdr = info->dlpi_phdr[i];
        if (phdr.p_type == PT_DYNAMIC) {
            dynamic = reinterpret_cast<ElfW(Dyn)*>(info->dlpi_addr + phdr.p_vaddr);
            break;
        }
    }
    if (!dynamic) return 0;

    ElfW(Sym) *symtab = nullptr;
    const char *strtab = nullptr;
    void *jmprel = nullptr;
    size_t pltrelSize = 0;
    int pltrelType = DT_RELA;
    ElfW(Rela) *rela = nullptr;
    size_t relaSize = 0;
    ElfW(Rel) *rel = nullptr;
    size_t relSize = 0;

    for (ElfW(Dyn) *dyn = dynamic; dyn->d_tag != DT_NULL; ++dyn) {
        switch (dyn->d_tag) {
            case DT_SYMTAB:
                symtab = reinterpret_cast<ElfW(Sym)*>(dynamicPtr(info->dlpi_addr, dyn->d_un.d_ptr));
                break;
            case DT_STRTAB:
                strtab = reinterpret_cast<const char*>(dynamicPtr(info->dlpi_addr, dyn->d_un.d_ptr));
                break;
            case DT_JMPREL:
                jmprel = dynamicPtr(info->dlpi_addr, dyn->d_un.d_ptr);
                break;
            case DT_PLTRELSZ:
                pltrelSize = dyn->d_un.d_val;
                break;
            case DT_PLTREL:
                pltrelType = static_cast<int>(dyn->d_un.d_val);
                break;
            case DT_RELA:
                rela = reinterpret_cast<ElfW(Rela)*>(dynamicPtr(info->dlpi_addr, dyn->d_un.d_ptr));
                break;
            case DT_RELASZ:
                relaSize = dyn->d_un.d_val;
                break;
            case DT_REL:
                rel = reinterpret_cast<ElfW(Rel)*>(dynamicPtr(info->dlpi_addr, dyn->d_un.d_ptr));
                break;
            case DT_RELSZ:
                relSize = dyn->d_un.d_val;
                break;
            default:
                break;
        }
    }

    int patched = 0;
    if (jmprel && pltrelSize > 0) {
        if (pltrelType == DT_RELA) {
            patched += patchRelocations(info->dlpi_addr, reinterpret_cast<ElfW(Rela)*>(jmprel),
                pltrelSize / sizeof(ElfW(Rela)), symtab, strtab);
        } else {
            patched += patchRelocations(info->dlpi_addr, reinterpret_cast<ElfW(Rel)*>(jmprel),
                pltrelSize / sizeof(ElfW(Rel)), symtab, strtab);
        }
    }
    if (rela && relaSize > 0) {
        patched += patchRelocations(info->dlpi_addr, rela, relaSize / sizeof(ElfW(Rela)), symtab, strtab);
    }
    if (rel && relSize > 0) {
        patched += patchRelocations(info->dlpi_addr, rel, relSize / sizeof(ElfW(Rel)), symtab, strtab);
    }

    rememberHooked(info->dlpi_addr, info->dlpi_name);
    if (patched > 0) FX_LOGI("patched %d imports in %s", patched, info->dlpi_name);
    return patched;
}

int phdrCallback(dl_phdr_info *info, size_t, void *data) {
    int *patched = reinterpret_cast<int*>(data);
    *patched += patchObject(info);
    return 0;
}

int scanAndPatch() {
    pthread_mutex_lock(&g_patchMutex);
    int patched = 0;
    dl_iterate_phdr(phdrCallback, &patched);
    pthread_mutex_unlock(&g_patchMutex);
    return patched;
}

void *patchThread(void*) {
    FX_LOGI("FrameX Zygisk active in %s", g_processName);
    for (int pass = 0; ; ++pass) {
        scanAndPatch();
        usleep(pass < 120 ? 500000 : 2000000);
    }
    return nullptr;
}

class FrameXZygiskModule : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        this->api = api;
        this->env = env;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        const char *processName = env->GetStringUTFChars(args->nice_name, nullptr);
        if (processName) {
            snprintf(g_processName, sizeof(g_processName), "%s", processName);
            g_targetProcess = processMatchesTarget(processName);
            env->ReleaseStringUTFChars(args->nice_name, processName);
        }
        if (!g_targetProcess) {
            api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
        }
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs*) override {
        if (!g_targetProcess) return;
        pthread_t thread;
        if (pthread_create(&thread, nullptr, patchThread, nullptr) == 0) {
            pthread_detach(thread);
        } else {
            FX_LOGW("failed to start patch thread");
        }
    }

private:
    zygisk::Api *api = nullptr;
    JNIEnv *env = nullptr;
};

} // namespace

REGISTER_ZYGISK_MODULE(FrameXZygiskModule)
