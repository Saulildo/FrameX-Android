// VK_LAYER_framex_hud — a Vulkan instrumentation layer for the FrameX Performance HUD.
//
// It is injected into a target game's process (via Android's GPU debug layer mechanism, enabled
// by GpuLayerInjector with root) and intercepts the Vulkan calls needed to reproduce Apple's
// Metal HUD metrics:
//
//   - Frame interval / FPS   -> CPU delta between vkQueuePresentKHR calls.
//   - Present delay          -> CPU time from the frame's last vkQueueSubmit to vkQueuePresentKHR.
//   - GPU time (ms)          -> vkCmdWriteTimestamp pair per primary command buffer, read back a
//                               couple of frames later via vkGetQueryPoolResults.
//   - Command Buffer CPU     -> CPU time vkBeginCommandBuffer..vkEndCommandBuffer (recording cost).
//   - Render Encoder CPU     -> CPU time spent inside render passes (Begin..EndRenderPass).
//   - Compute Encoder CPU    -> CPU time spent recording vkCmdDispatch* calls.
//   - Blit Encoder           -> CPU time spent recording transfer/blit commands.
//
// Vulkan has no "encoders" the way Metal does, so the encoder numbers are the closest faithful
// mapping (render pass == render encoder; dispatch recording == compute encoder; transfer
// recording == blit encoder). Per-frame results are streamed to the HUD via framex_ipc_send().
//
// The layer follows the Khronos loader<->layer interface v2 (vkNegotiateLoaderLayerInterfaceVersion).

#include <vulkan/vulkan.h>

#include <android/log.h>

#include <cstring>
#include <deque>
#include <mutex>
#include <unordered_map>
#include <vector>

#include "framex_ipc.h"

#define FX_TAG "FrameXVkLayer"
#define FX_LOGI(...) __android_log_print(ANDROID_LOG_INFO, FX_TAG, __VA_ARGS__)
#define FX_LOGW(...) __android_log_print(ANDROID_LOG_WARN, FX_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Minimal subset of the loader<->layer interface (normally from vk_layer.h, which the NDK does
// not ship). These structures are part of the stable Vulkan loader ABI.
// ---------------------------------------------------------------------------
#ifndef VK_LAYER_EXPORT
#define VK_LAYER_EXPORT __attribute__((visibility("default")))
#endif

typedef enum VkLayerFunction_ {
    VK_LAYER_LINK_INFO = 0,
    VK_LOADER_DATA_CALLBACK = 1,
    VK_LOADER_LAYER_CREATE_DEVICE_CALLBACK = 2,
    VK_LOADER_FEATURES = 3,
} VkLayerFunction;

typedef PFN_vkVoidFunction(VKAPI_PTR* PFN_GetPhysicalDeviceProcAddr)(VkInstance instance,
                                                                     const char* pName);
typedef VkResult(VKAPI_PTR* PFN_vkSetInstanceLoaderData)(VkInstance instance, void* object);
typedef VkResult(VKAPI_PTR* PFN_vkSetDeviceLoaderData)(VkDevice device, void* object);

typedef struct VkLayerInstanceLink_ {
    struct VkLayerInstanceLink_* pNext;
    PFN_vkGetInstanceProcAddr pfnNextGetInstanceProcAddr;
    PFN_GetPhysicalDeviceProcAddr pfnNextGetPhysicalDeviceProcAddr;
} VkLayerInstanceLink;

typedef struct {
    VkStructureType sType;  // VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO
    const void* pNext;
    VkLayerFunction function;
    union {
        VkLayerInstanceLink* pLayerInfo;
        PFN_vkSetInstanceLoaderData pfnSetInstanceLoaderData;
    } u;
} VkLayerInstanceCreateInfo;

typedef struct VkLayerDeviceLink_ {
    struct VkLayerDeviceLink_* pNext;
    PFN_vkGetInstanceProcAddr pfnNextGetInstanceProcAddr;
    PFN_vkGetDeviceProcAddr pfnNextGetDeviceProcAddr;
} VkLayerDeviceLink;

typedef struct {
    VkStructureType sType;  // VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO
    const void* pNext;
    VkLayerFunction function;
    union {
        VkLayerDeviceLink* pLayerInfo;
        PFN_vkSetDeviceLoaderData pfnSetDeviceLoaderData;
    } u;
} VkLayerDeviceCreateInfo;

typedef struct VkNegotiateLayerInterface_ {
    VkStructureType sType;  // VK_STRUCTURE_TYPE_LOADER_LAYER_NEGOTIATION (0x10000003... numeric)
    void* pNext;
    uint32_t loaderLayerInterfaceVersion;
    PFN_vkGetInstanceProcAddr pfnGetInstanceProcAddr;
    PFN_vkGetDeviceProcAddr pfnGetDeviceProcAddr;
    PFN_GetPhysicalDeviceProcAddr pfnGetPhysicalDeviceProcAddr;
} VkNegotiateLayerInterface;

static constexpr char kLayerName[] = "VK_LAYER_framex_hud";

// Number of timestamp slots in the query pool ring. Each frame's primary command buffer consumes
// a contiguous pair (begin,end). Sized large so reused slots are always many frames in the past.
static constexpr uint32_t kQueryPoolSize = 1024;  // must be even
// How many frames to defer GPU timestamp read-back so the results are ready without stalling.
static constexpr size_t kGpuReadbackDelay = 2;

// ---------------------------------------------------------------------------
// Dispatch tables (only the entry points we actually use).
// ---------------------------------------------------------------------------
struct InstanceDispatch {
    PFN_vkGetInstanceProcAddr GetInstanceProcAddr = nullptr;
    PFN_vkDestroyInstance DestroyInstance = nullptr;
    PFN_vkGetPhysicalDeviceProperties GetPhysicalDeviceProperties = nullptr;
};

struct DeviceDispatch {
    PFN_vkGetDeviceProcAddr GetDeviceProcAddr = nullptr;
    PFN_vkDestroyDevice DestroyDevice = nullptr;
    PFN_vkCreateQueryPool CreateQueryPool = nullptr;
    PFN_vkDestroyQueryPool DestroyQueryPool = nullptr;
    PFN_vkGetQueryPoolResults GetQueryPoolResults = nullptr;
    PFN_vkCmdResetQueryPool CmdResetQueryPool = nullptr;
    PFN_vkCmdWriteTimestamp CmdWriteTimestamp = nullptr;
    PFN_vkBeginCommandBuffer BeginCommandBuffer = nullptr;
    PFN_vkEndCommandBuffer EndCommandBuffer = nullptr;
    PFN_vkCmdBeginRenderPass CmdBeginRenderPass = nullptr;
    PFN_vkCmdEndRenderPass CmdEndRenderPass = nullptr;
    PFN_vkCmdDispatch CmdDispatch = nullptr;
    PFN_vkCmdDispatchIndirect CmdDispatchIndirect = nullptr;
    PFN_vkCmdCopyBuffer CmdCopyBuffer = nullptr;
    PFN_vkCmdCopyImage CmdCopyImage = nullptr;
    PFN_vkCmdBlitImage CmdBlitImage = nullptr;
    PFN_vkCmdCopyBufferToImage CmdCopyBufferToImage = nullptr;
    PFN_vkCmdCopyImageToBuffer CmdCopyImageToBuffer = nullptr;
    PFN_vkQueueSubmit QueueSubmit = nullptr;
    PFN_vkQueuePresentKHR QueuePresentKHR = nullptr;
};

struct CmdBufState {
    uint32_t slotBegin = UINT32_MAX;  // query pool slot for the BOTTOM/ TOP timestamp pair
    uint32_t slotEnd = UINT32_MAX;
    uint64_t beginNs = 0;             // CPU clock at vkBeginCommandBuffer
    uint64_t rpStartNs = 0;           // CPU clock at last vkCmdBeginRenderPass
    bool inRenderPass = false;
    double cmdCpuMs = 0;
    double renderCpuMs = 0;
    double computeCpuMs = 0;
    double blitCpuMs = 0;
};

struct DeviceData {
    VkDevice device = VK_NULL_HANDLE;
    DeviceDispatch disp;
    VkQueryPool queryPool = VK_NULL_HANDLE;
    bool timestampsSupported = false;
    float timestampPeriodNs = 1.0f;  // ns per timestamp tick (from VkPhysicalDeviceLimits)
    uint32_t nextSlot = 0;           // ring cursor (steps by 2)

    std::mutex lock;
    std::unordered_map<VkCommandBuffer, CmdBufState> cmdBufs;

    // Per-frame CPU accumulators (summed across all command buffers submitted between presents).
    double frameCmdCpuMs = 0;
    double frameRenderCpuMs = 0;
    double frameComputeCpuMs = 0;
    double frameBlitCpuMs = 0;

    uint64_t lastSubmitNs = 0;
    uint64_t lastPresentNs = 0;

    // GPU timestamp pairs recorded for the in-flight frame, plus a small queue of past frames
    // awaiting read-back.
    std::vector<std::pair<uint32_t, uint32_t>> currentFramePairs;
    std::deque<std::vector<std::pair<uint32_t, uint32_t>>> pendingFrames;
    double lastGpuMs = -1.0;  // held value if a read-back isn't ready yet
};

namespace {
std::mutex g_globalLock;
std::unordered_map<void*, InstanceDispatch> g_instanceDispatch;
std::unordered_map<void*, VkInstance> g_deviceInstance;  // device key -> owning instance handle
std::unordered_map<void*, DeviceData*> g_deviceData;

// The first word of any dispatchable handle is its dispatch key.
inline void* DispatchKey(void* handle) { return *reinterpret_cast<void**>(handle); }

InstanceDispatch* GetInstanceDispatch(void* handle) {
    std::lock_guard<std::mutex> g(g_globalLock);
    auto it = g_instanceDispatch.find(DispatchKey(handle));
    return it == g_instanceDispatch.end() ? nullptr : &it->second;
}

DeviceData* GetDeviceData(void* handle) {
    std::lock_guard<std::mutex> g(g_globalLock);
    auto it = g_deviceData.find(DispatchKey(handle));
    return it == g_deviceData.end() ? nullptr : it->second;
}

inline double NsToMs(uint64_t ns) { return static_cast<double>(ns) / 1000000.0; }
}  // namespace

// ---------------------------------------------------------------------------
// Instance create / destroy
// ---------------------------------------------------------------------------
VKAPI_ATTR VkResult VKAPI_CALL FX_CreateInstance(const VkInstanceCreateInfo* pCreateInfo,
                                                 const VkAllocationCallbacks* pAllocator,
                                                 VkInstance* pInstance) {
    auto* ci = reinterpret_cast<VkLayerInstanceCreateInfo*>(const_cast<void*>(pCreateInfo->pNext));
    while (ci && !(ci->sType == VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO &&
                   ci->function == VK_LAYER_LINK_INFO)) {
        ci = reinterpret_cast<VkLayerInstanceCreateInfo*>(const_cast<void*>(ci->pNext));
    }
    if (!ci) return VK_ERROR_INITIALIZATION_FAILED;

    PFN_vkGetInstanceProcAddr nextGIPA = ci->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    // Advance the link so the next layer/loader consumes its own info.
    ci->u.pLayerInfo = ci->u.pLayerInfo->pNext;

    auto createInstance =
        reinterpret_cast<PFN_vkCreateInstance>(nextGIPA(VK_NULL_HANDLE, "vkCreateInstance"));
    if (!createInstance) return VK_ERROR_INITIALIZATION_FAILED;

    VkResult res = createInstance(pCreateInfo, pAllocator, pInstance);
    if (res != VK_SUCCESS) return res;

    InstanceDispatch disp;
    disp.GetInstanceProcAddr = nextGIPA;
    disp.DestroyInstance =
        reinterpret_cast<PFN_vkDestroyInstance>(nextGIPA(*pInstance, "vkDestroyInstance"));
    disp.GetPhysicalDeviceProperties = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(
        nextGIPA(*pInstance, "vkGetPhysicalDeviceProperties"));

    std::lock_guard<std::mutex> g(g_globalLock);
    g_instanceDispatch[DispatchKey(*pInstance)] = disp;
    FX_LOGI("FrameX Vulkan layer attached to instance");
    return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL FX_DestroyInstance(VkInstance instance,
                                              const VkAllocationCallbacks* pAllocator) {
    InstanceDispatch* disp = GetInstanceDispatch(instance);
    if (disp && disp->DestroyInstance) disp->DestroyInstance(instance, pAllocator);
    std::lock_guard<std::mutex> g(g_globalLock);
    g_instanceDispatch.erase(DispatchKey(instance));
}

// ---------------------------------------------------------------------------
// Device create / destroy
// ---------------------------------------------------------------------------
template <typename T>
static T LoadDev(PFN_vkGetDeviceProcAddr gdpa, VkDevice dev, const char* name) {
    return reinterpret_cast<T>(gdpa(dev, name));
}

VKAPI_ATTR VkResult VKAPI_CALL FX_CreateDevice(VkPhysicalDevice physicalDevice,
                                               const VkDeviceCreateInfo* pCreateInfo,
                                               const VkAllocationCallbacks* pAllocator,
                                               VkDevice* pDevice) {
    auto* ci = reinterpret_cast<VkLayerDeviceCreateInfo*>(const_cast<void*>(pCreateInfo->pNext));
    while (ci && !(ci->sType == VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO &&
                   ci->function == VK_LAYER_LINK_INFO)) {
        ci = reinterpret_cast<VkLayerDeviceCreateInfo*>(const_cast<void*>(ci->pNext));
    }
    if (!ci) return VK_ERROR_INITIALIZATION_FAILED;

    PFN_vkGetInstanceProcAddr nextGIPA = ci->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    PFN_vkGetDeviceProcAddr nextGDPA = ci->u.pLayerInfo->pfnNextGetDeviceProcAddr;
    ci->u.pLayerInfo = ci->u.pLayerInfo->pNext;

    auto createDevice =
        reinterpret_cast<PFN_vkCreateDevice>(nextGIPA(VK_NULL_HANDLE, "vkCreateDevice"));
    if (!createDevice) return VK_ERROR_INITIALIZATION_FAILED;

    VkResult res = createDevice(physicalDevice, pCreateInfo, pAllocator, pDevice);
    if (res != VK_SUCCESS) return res;

    auto* dd = new DeviceData();
    dd->device = *pDevice;
    DeviceDispatch& d = dd->disp;
    d.GetDeviceProcAddr = nextGDPA;
    d.DestroyDevice = LoadDev<PFN_vkDestroyDevice>(nextGDPA, *pDevice, "vkDestroyDevice");
    d.CreateQueryPool = LoadDev<PFN_vkCreateQueryPool>(nextGDPA, *pDevice, "vkCreateQueryPool");
    d.DestroyQueryPool = LoadDev<PFN_vkDestroyQueryPool>(nextGDPA, *pDevice, "vkDestroyQueryPool");
    d.GetQueryPoolResults =
        LoadDev<PFN_vkGetQueryPoolResults>(nextGDPA, *pDevice, "vkGetQueryPoolResults");
    d.CmdResetQueryPool =
        LoadDev<PFN_vkCmdResetQueryPool>(nextGDPA, *pDevice, "vkCmdResetQueryPool");
    d.CmdWriteTimestamp =
        LoadDev<PFN_vkCmdWriteTimestamp>(nextGDPA, *pDevice, "vkCmdWriteTimestamp");
    d.BeginCommandBuffer =
        LoadDev<PFN_vkBeginCommandBuffer>(nextGDPA, *pDevice, "vkBeginCommandBuffer");
    d.EndCommandBuffer = LoadDev<PFN_vkEndCommandBuffer>(nextGDPA, *pDevice, "vkEndCommandBuffer");
    d.CmdBeginRenderPass =
        LoadDev<PFN_vkCmdBeginRenderPass>(nextGDPA, *pDevice, "vkCmdBeginRenderPass");
    d.CmdEndRenderPass =
        LoadDev<PFN_vkCmdEndRenderPass>(nextGDPA, *pDevice, "vkCmdEndRenderPass");
    d.CmdDispatch = LoadDev<PFN_vkCmdDispatch>(nextGDPA, *pDevice, "vkCmdDispatch");
    d.CmdDispatchIndirect =
        LoadDev<PFN_vkCmdDispatchIndirect>(nextGDPA, *pDevice, "vkCmdDispatchIndirect");
    d.CmdCopyBuffer = LoadDev<PFN_vkCmdCopyBuffer>(nextGDPA, *pDevice, "vkCmdCopyBuffer");
    d.CmdCopyImage = LoadDev<PFN_vkCmdCopyImage>(nextGDPA, *pDevice, "vkCmdCopyImage");
    d.CmdBlitImage = LoadDev<PFN_vkCmdBlitImage>(nextGDPA, *pDevice, "vkCmdBlitImage");
    d.CmdCopyBufferToImage =
        LoadDev<PFN_vkCmdCopyBufferToImage>(nextGDPA, *pDevice, "vkCmdCopyBufferToImage");
    d.CmdCopyImageToBuffer =
        LoadDev<PFN_vkCmdCopyImageToBuffer>(nextGDPA, *pDevice, "vkCmdCopyImageToBuffer");
    d.QueueSubmit = LoadDev<PFN_vkQueueSubmit>(nextGDPA, *pDevice, "vkQueueSubmit");
    d.QueuePresentKHR = LoadDev<PFN_vkQueuePresentKHR>(nextGDPA, *pDevice, "vkQueuePresentKHR");

    // Timestamp support + period come from the physical device limits (instance-level call).
    InstanceDispatch* idisp = GetInstanceDispatch(physicalDevice);
    if (idisp && idisp->GetPhysicalDeviceProperties) {
        VkPhysicalDeviceProperties props{};
        idisp->GetPhysicalDeviceProperties(physicalDevice, &props);
        dd->timestampPeriodNs = props.limits.timestampPeriod;
        dd->timestampsSupported = props.limits.timestampComputeAndGraphics == VK_TRUE;
    }

    if (dd->timestampsSupported && d.CreateQueryPool) {
        VkQueryPoolCreateInfo qpci{};
        qpci.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
        qpci.queryType = VK_QUERY_TYPE_TIMESTAMP;
        qpci.queryCount = kQueryPoolSize;
        if (d.CreateQueryPool(*pDevice, &qpci, nullptr, &dd->queryPool) != VK_SUCCESS) {
            dd->queryPool = VK_NULL_HANDLE;
            dd->timestampsSupported = false;
        }
    }

    std::lock_guard<std::mutex> g(g_globalLock);
    g_deviceData[DispatchKey(*pDevice)] = dd;
    FX_LOGI("FrameX Vulkan layer attached to device (timestamps=%d, period=%.2fns)",
            dd->timestampsSupported ? 1 : 0, dd->timestampPeriodNs);
    return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL FX_DestroyDevice(VkDevice device,
                                            const VkAllocationCallbacks* pAllocator) {
    DeviceData* dd = GetDeviceData(device);
    if (!dd) return;
    if (dd->queryPool != VK_NULL_HANDLE && dd->disp.DestroyQueryPool) {
        dd->disp.DestroyQueryPool(device, dd->queryPool, nullptr);
    }
    PFN_vkDestroyDevice destroy = dd->disp.DestroyDevice;
    {
        std::lock_guard<std::mutex> g(g_globalLock);
        g_deviceData.erase(DispatchKey(device));
    }
    if (destroy) destroy(device, pAllocator);
    delete dd;
}

// ---------------------------------------------------------------------------
// Command buffer recording — CPU encoder timing + GPU timestamps
// ---------------------------------------------------------------------------
VKAPI_ATTR VkResult VKAPI_CALL FX_BeginCommandBuffer(VkCommandBuffer cb,
                                                     const VkCommandBufferBeginInfo* pBeginInfo) {
    DeviceData* dd = GetDeviceData(cb);
    if (!dd) return VK_ERROR_INITIALIZATION_FAILED;
    VkResult res = dd->disp.BeginCommandBuffer(cb, pBeginInfo);
    if (res != VK_SUCCESS) return res;

    std::lock_guard<std::mutex> g(dd->lock);
    CmdBufState st;
    st.beginNs = framex_now_ns();
    if (dd->timestampsSupported && dd->queryPool != VK_NULL_HANDLE) {
        const uint32_t base = dd->nextSlot;
        dd->nextSlot = (dd->nextSlot + 2) % kQueryPoolSize;
        st.slotBegin = base;
        st.slotEnd = base + 1;
        // Reset our two slots (legal here: we are outside a render pass at begin) then mark the
        // top-of-pipe time. The bottom-of-pipe time is written at EndCommandBuffer.
        dd->disp.CmdResetQueryPool(cb, dd->queryPool, base, 2);
        dd->disp.CmdWriteTimestamp(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, dd->queryPool,
                                   st.slotBegin);
    }
    dd->cmdBufs[cb] = st;
    return res;
}

VKAPI_ATTR VkResult VKAPI_CALL FX_EndCommandBuffer(VkCommandBuffer cb) {
    DeviceData* dd = GetDeviceData(cb);
    if (!dd) return VK_ERROR_INITIALIZATION_FAILED;
    {
        std::lock_guard<std::mutex> g(dd->lock);
        auto it = dd->cmdBufs.find(cb);
        if (it != dd->cmdBufs.end() && it->second.slotEnd != UINT32_MAX) {
            dd->disp.CmdWriteTimestamp(cb, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, dd->queryPool,
                                       it->second.slotEnd);
        }
        if (it != dd->cmdBufs.end()) {
            it->second.cmdCpuMs += NsToMs(framex_now_ns() - it->second.beginNs);
        }
    }
    return dd->disp.EndCommandBuffer(cb);
}

VKAPI_ATTR void VKAPI_CALL FX_CmdBeginRenderPass(VkCommandBuffer cb,
                                                 const VkRenderPassBeginInfo* pInfo,
                                                 VkSubpassContents contents) {
    DeviceData* dd = GetDeviceData(cb);
    if (dd) {
        std::lock_guard<std::mutex> g(dd->lock);
        auto it = dd->cmdBufs.find(cb);
        if (it != dd->cmdBufs.end()) {
            it->second.inRenderPass = true;
            it->second.rpStartNs = framex_now_ns();
        }
    }
    dd->disp.CmdBeginRenderPass(cb, pInfo, contents);
}

VKAPI_ATTR void VKAPI_CALL FX_CmdEndRenderPass(VkCommandBuffer cb) {
    DeviceData* dd = GetDeviceData(cb);
    dd->disp.CmdEndRenderPass(cb);
    if (dd) {
        std::lock_guard<std::mutex> g(dd->lock);
        auto it = dd->cmdBufs.find(cb);
        if (it != dd->cmdBufs.end() && it->second.inRenderPass) {
            it->second.renderCpuMs += NsToMs(framex_now_ns() - it->second.rpStartNs);
            it->second.inRenderPass = false;
        }
    }
}

// Helper: accumulate the CPU cost of recording a single command into one of the encoder buckets.
template <typename Fn>
static void TimeRecord(DeviceData* dd, VkCommandBuffer cb, double CmdBufState::*bucket, Fn&& call) {
    const uint64_t t0 = framex_now_ns();
    call();
    const double ms = NsToMs(framex_now_ns() - t0);
    std::lock_guard<std::mutex> g(dd->lock);
    auto it = dd->cmdBufs.find(cb);
    if (it != dd->cmdBufs.end()) it->second.*bucket += ms;
}

VKAPI_ATTR void VKAPI_CALL FX_CmdDispatch(VkCommandBuffer cb, uint32_t x, uint32_t y, uint32_t z) {
    DeviceData* dd = GetDeviceData(cb);
    TimeRecord(dd, cb, &CmdBufState::computeCpuMs, [&] { dd->disp.CmdDispatch(cb, x, y, z); });
}

VKAPI_ATTR void VKAPI_CALL FX_CmdDispatchIndirect(VkCommandBuffer cb, VkBuffer buf,
                                                  VkDeviceSize off) {
    DeviceData* dd = GetDeviceData(cb);
    TimeRecord(dd, cb, &CmdBufState::computeCpuMs,
               [&] { dd->disp.CmdDispatchIndirect(cb, buf, off); });
}

VKAPI_ATTR void VKAPI_CALL FX_CmdCopyBuffer(VkCommandBuffer cb, VkBuffer s, VkBuffer d,
                                            uint32_t n, const VkBufferCopy* r) {
    DeviceData* dd = GetDeviceData(cb);
    TimeRecord(dd, cb, &CmdBufState::blitCpuMs, [&] { dd->disp.CmdCopyBuffer(cb, s, d, n, r); });
}

VKAPI_ATTR void VKAPI_CALL FX_CmdCopyImage(VkCommandBuffer cb, VkImage s, VkImageLayout sl,
                                           VkImage d, VkImageLayout dl, uint32_t n,
                                           const VkImageCopy* r) {
    DeviceData* dd = GetDeviceData(cb);
    TimeRecord(dd, cb, &CmdBufState::blitCpuMs,
               [&] { dd->disp.CmdCopyImage(cb, s, sl, d, dl, n, r); });
}

VKAPI_ATTR void VKAPI_CALL FX_CmdBlitImage(VkCommandBuffer cb, VkImage s, VkImageLayout sl,
                                           VkImage d, VkImageLayout dl, uint32_t n,
                                           const VkImageBlit* r, VkFilter f) {
    DeviceData* dd = GetDeviceData(cb);
    TimeRecord(dd, cb, &CmdBufState::blitCpuMs,
               [&] { dd->disp.CmdBlitImage(cb, s, sl, d, dl, n, r, f); });
}

VKAPI_ATTR void VKAPI_CALL FX_CmdCopyBufferToImage(VkCommandBuffer cb, VkBuffer s, VkImage d,
                                                   VkImageLayout dl, uint32_t n,
                                                   const VkBufferImageCopy* r) {
    DeviceData* dd = GetDeviceData(cb);
    TimeRecord(dd, cb, &CmdBufState::blitCpuMs,
               [&] { dd->disp.CmdCopyBufferToImage(cb, s, d, dl, n, r); });
}

VKAPI_ATTR void VKAPI_CALL FX_CmdCopyImageToBuffer(VkCommandBuffer cb, VkImage s, VkImageLayout sl,
                                                   VkBuffer d, uint32_t n,
                                                   const VkBufferImageCopy* r) {
    DeviceData* dd = GetDeviceData(cb);
    TimeRecord(dd, cb, &CmdBufState::blitCpuMs,
               [&] { dd->disp.CmdCopyImageToBuffer(cb, s, sl, d, n, r); });
}

// ---------------------------------------------------------------------------
// Submit / Present — frame aggregation + GPU read-back + IPC emit
// ---------------------------------------------------------------------------
VKAPI_ATTR VkResult VKAPI_CALL FX_QueueSubmit(VkQueue queue, uint32_t submitCount,
                                              const VkSubmitInfo* pSubmits, VkFence fence) {
    DeviceData* dd = GetDeviceData(queue);
    if (dd) {
        std::lock_guard<std::mutex> g(dd->lock);
        dd->lastSubmitNs = framex_now_ns();
        for (uint32_t i = 0; i < submitCount; ++i) {
            for (uint32_t j = 0; j < pSubmits[i].commandBufferCount; ++j) {
                VkCommandBuffer cb = pSubmits[i].pCommandBuffers[j];
                auto it = dd->cmdBufs.find(cb);
                if (it == dd->cmdBufs.end()) continue;
                CmdBufState& st = it->second;
                dd->frameCmdCpuMs += st.cmdCpuMs;
                dd->frameRenderCpuMs += st.renderCpuMs;
                dd->frameComputeCpuMs += st.computeCpuMs;
                dd->frameBlitCpuMs += st.blitCpuMs;
                if (st.slotBegin != UINT32_MAX) {
                    dd->currentFramePairs.emplace_back(st.slotBegin, st.slotEnd);
                }
            }
        }
    }
    return dd ? dd->disp.QueueSubmit(queue, submitCount, pSubmits, fence)
              : VK_ERROR_INITIALIZATION_FAILED;
}

// Read back the GPU timestamp pairs for one (older) frame. Returns -1 if not ready yet.
static double ReadFrameGpuMs(DeviceData* dd,
                             const std::vector<std::pair<uint32_t, uint32_t>>& pairs) {
    if (pairs.empty() || !dd->disp.GetQueryPoolResults) return -1.0;
    uint64_t minBegin = UINT64_MAX;
    uint64_t maxEnd = 0;
    for (const auto& p : pairs) {
        uint64_t ts[2] = {0, 0};
        VkResult r = dd->disp.GetQueryPoolResults(dd->device, dd->queryPool, p.first, 2, sizeof(ts),
                                                  ts, sizeof(uint64_t), VK_QUERY_RESULT_64_BIT);
        if (r != VK_SUCCESS) return -1.0;  // VK_NOT_READY -> try again next frame
        if (ts[0] < minBegin) minBegin = ts[0];
        if (ts[1] > maxEnd) maxEnd = ts[1];
    }
    if (maxEnd <= minBegin) return 0.0;
    return NsToMs(static_cast<uint64_t>((maxEnd - minBegin) * dd->timestampPeriodNs));
}

VKAPI_ATTR VkResult VKAPI_CALL FX_QueuePresentKHR(VkQueue queue, const VkPresentInfoKHR* pInfo) {
    DeviceData* dd = GetDeviceData(queue);
    if (!dd) return VK_ERROR_INITIALIZATION_FAILED;

    const uint64_t presentNs = framex_now_ns();

    FrameXSample sample{};
    sample.api = "Vulkan";
    {
        std::lock_guard<std::mutex> g(dd->lock);

        sample.frameIntervalMs = dd->lastPresentNs ? NsToMs(presentNs - dd->lastPresentNs) : 0.0;
        sample.presentDelayMs = dd->lastSubmitNs ? NsToMs(presentNs - dd->lastSubmitNs) : 0.0;
        sample.cmdBufferCpuMs = dd->frameCmdCpuMs;
        sample.renderEncoderCpuMs = dd->frameRenderCpuMs;
        sample.computeEncoderCpuMs = dd->frameComputeCpuMs;
        sample.blitEncoderMs = dd->frameBlitCpuMs;

        // Defer GPU read-back so timestamps have completed; report the oldest ready frame.
        dd->pendingFrames.push_back(std::move(dd->currentFramePairs));
        dd->currentFramePairs.clear();
        if (dd->pendingFrames.size() > kGpuReadbackDelay) {
            double gpu = ReadFrameGpuMs(dd, dd->pendingFrames.front());
            dd->pendingFrames.pop_front();
            if (gpu >= 0.0) dd->lastGpuMs = gpu;
        }
        sample.gpuMs = dd->lastGpuMs >= 0.0 ? dd->lastGpuMs : 0.0;

        // Reset per-frame CPU accumulators for the next frame.
        dd->frameCmdCpuMs = dd->frameRenderCpuMs = dd->frameComputeCpuMs = dd->frameBlitCpuMs = 0;
        dd->lastPresentNs = presentNs;
    }

    framex_ipc_send(sample);
    return dd->disp.QueuePresentKHR(queue, pInfo);
}

// ---------------------------------------------------------------------------
// GetProcAddr dispatch
// ---------------------------------------------------------------------------
#define FX_INTERCEPT(name)                       \
    if (strcmp(pName, "vk" #name) == 0)          \
    return reinterpret_cast<PFN_vkVoidFunction>(FX_##name)

extern "C" {

// Forward declarations (definitions appear below; referenced from the GetProcAddr dispatch).
VK_LAYER_EXPORT VKAPI_ATTR VkResult VKAPI_CALL
FrameX_EnumerateInstanceLayerProperties(uint32_t* pCount, VkLayerProperties* pProps);
VK_LAYER_EXPORT VKAPI_ATTR VkResult VKAPI_CALL
FrameX_EnumerateDeviceLayerProperties(VkPhysicalDevice, uint32_t* pCount, VkLayerProperties* pProps);
VK_LAYER_EXPORT VKAPI_ATTR VkResult VKAPI_CALL
FrameX_EnumerateInstanceExtensionProperties(const char* pLayerName, uint32_t* pCount,
                                            VkExtensionProperties* pProps);
VK_LAYER_EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
FrameX_GetDeviceProcAddr(VkDevice device, const char* pName);
VK_LAYER_EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
FrameX_GetInstanceProcAddr(VkInstance instance, const char* pName);

VK_LAYER_EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
FrameX_GetDeviceProcAddr(VkDevice device, const char* pName) {
    FX_INTERCEPT(BeginCommandBuffer);
    FX_INTERCEPT(EndCommandBuffer);
    FX_INTERCEPT(CmdBeginRenderPass);
    FX_INTERCEPT(CmdEndRenderPass);
    FX_INTERCEPT(CmdDispatch);
    FX_INTERCEPT(CmdDispatchIndirect);
    FX_INTERCEPT(CmdCopyBuffer);
    FX_INTERCEPT(CmdCopyImage);
    FX_INTERCEPT(CmdBlitImage);
    FX_INTERCEPT(CmdCopyBufferToImage);
    FX_INTERCEPT(CmdCopyImageToBuffer);
    FX_INTERCEPT(QueueSubmit);
    FX_INTERCEPT(QueuePresentKHR);
    FX_INTERCEPT(DestroyDevice);

    DeviceData* dd = GetDeviceData(device);
    if (!dd || !dd->disp.GetDeviceProcAddr) return nullptr;
    return dd->disp.GetDeviceProcAddr(device, pName);
}

VK_LAYER_EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
FrameX_GetInstanceProcAddr(VkInstance instance, const char* pName) {
    if (strcmp(pName, "vkGetInstanceProcAddr") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(FrameX_GetInstanceProcAddr);
    if (strcmp(pName, "vkGetDeviceProcAddr") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(FrameX_GetDeviceProcAddr);
    if (strcmp(pName, "vkEnumerateInstanceLayerProperties") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(FrameX_EnumerateInstanceLayerProperties);
    if (strcmp(pName, "vkEnumerateDeviceLayerProperties") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(FrameX_EnumerateDeviceLayerProperties);
    if (strcmp(pName, "vkEnumerateInstanceExtensionProperties") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(FrameX_EnumerateInstanceExtensionProperties);
    FX_INTERCEPT(CreateInstance);
    FX_INTERCEPT(DestroyInstance);
    FX_INTERCEPT(CreateDevice);

    // Device-level functions can also be queried through the instance GIPA.
    FX_INTERCEPT(BeginCommandBuffer);
    FX_INTERCEPT(EndCommandBuffer);
    FX_INTERCEPT(CmdBeginRenderPass);
    FX_INTERCEPT(CmdEndRenderPass);
    FX_INTERCEPT(CmdDispatch);
    FX_INTERCEPT(QueueSubmit);
    FX_INTERCEPT(QueuePresentKHR);
    FX_INTERCEPT(DestroyDevice);

    InstanceDispatch* disp = GetInstanceDispatch(instance);
    if (!disp || !disp->GetInstanceProcAddr) return nullptr;
    return disp->GetInstanceProcAddr(instance, pName);
}

// --- Layer enumeration (how Android discovers and names this layer) --------
VK_LAYER_EXPORT VKAPI_ATTR VkResult VKAPI_CALL FrameX_EnumerateInstanceLayerProperties(
    uint32_t* pCount, VkLayerProperties* pProps) {
    if (pProps == nullptr) {
        *pCount = 1;
        return VK_SUCCESS;
    }
    if (*pCount < 1) {
        *pCount = 0;
        return VK_INCOMPLETE;
    }
    *pCount = 1;
    memset(pProps, 0, sizeof(VkLayerProperties));
    strncpy(pProps->layerName, kLayerName, VK_MAX_EXTENSION_NAME_SIZE - 1);
    strncpy(pProps->description, "FrameX Performance HUD instrumentation",
            VK_MAX_DESCRIPTION_SIZE - 1);
    pProps->specVersion = VK_API_VERSION_1_1;
    pProps->implementationVersion = 1;
    return VK_SUCCESS;
}

VK_LAYER_EXPORT VKAPI_ATTR VkResult VKAPI_CALL FrameX_EnumerateDeviceLayerProperties(
    VkPhysicalDevice, uint32_t* pCount, VkLayerProperties* pProps) {
    return FrameX_EnumerateInstanceLayerProperties(pCount, pProps);
}

VK_LAYER_EXPORT VKAPI_ATTR VkResult VKAPI_CALL FrameX_EnumerateInstanceExtensionProperties(
    const char* pLayerName, uint32_t* pCount, VkExtensionProperties*) {
    if (pLayerName && strcmp(pLayerName, kLayerName) == 0) {
        *pCount = 0;  // We expose no instance extensions.
        return VK_SUCCESS;
    }
    return VK_ERROR_LAYER_NOT_PRESENT;
}

// --- Loader negotiation (interface v2) -------------------------------------
VK_LAYER_EXPORT VKAPI_ATTR VkResult VKAPI_CALL
vkNegotiateLoaderLayerInterfaceVersion(VkNegotiateLayerInterface* pVersionStruct) {
    if (pVersionStruct == nullptr) return VK_ERROR_INITIALIZATION_FAILED;
    if (pVersionStruct->loaderLayerInterfaceVersion > 2) {
        pVersionStruct->loaderLayerInterfaceVersion = 2;
    }
    pVersionStruct->pfnGetInstanceProcAddr = FrameX_GetInstanceProcAddr;
    pVersionStruct->pfnGetDeviceProcAddr = FrameX_GetDeviceProcAddr;
    pVersionStruct->pfnGetPhysicalDeviceProcAddr = nullptr;
    return VK_SUCCESS;
}

// Some loaders (older Android) look up these fixed symbol names directly.
VK_LAYER_EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
vkGetInstanceProcAddr(VkInstance instance, const char* pName) {
    return FrameX_GetInstanceProcAddr(instance, pName);
}

VK_LAYER_EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
vkGetDeviceProcAddr(VkDevice device, const char* pName) {
    return FrameX_GetDeviceProcAddr(device, pName);
}

}  // extern "C"
