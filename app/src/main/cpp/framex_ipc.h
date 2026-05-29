// FrameX GPU instrumentation — IPC client shared by the Vulkan and GLES layers.
//
// The layers run *inside the target app's process* (injected via Android's GPU debug layer
// mechanism). They stream one compact CSV line per presented frame to the FrameX HUD service
// over an abstract-namespace UNIX domain socket named "framex_hud" (created on the Kotlin side
// by a LocalServerSocket). Abstract sockets need no filesystem path and survive across UIDs.
#ifndef FRAMEX_IPC_H
#define FRAMEX_IPC_H

#include <cstdint>

// One per-frame measurement. All times are in milliseconds; -1 means "not applicable / unknown".
struct FrameXSample {
    const char* api;              // "Vulkan" or "OpenGL"
    double gpuMs;                 // GPU execution time for the frame
    double presentDelayMs;        // submit -> present (CPU-side approximation)
    double frameIntervalMs;       // time between consecutive presents (== 1000/FPS)
    double cmdBufferCpuMs;        // CPU time recording command buffers this frame
    double renderEncoderCpuMs;    // CPU time inside render passes (render "encoder")
    double computeEncoderCpuMs;   // CPU time recording compute dispatches
    double blitEncoderMs;         // CPU time recording transfer/blit commands
};

// Sends one sample. Lazily (re)connects; never blocks the render thread for long. Thread-safe.
void framex_ipc_send(const FrameXSample& sample);

// Monotonic clock in nanoseconds (CLOCK_MONOTONIC) — shared timing helper.
uint64_t framex_now_ns();

#endif  // FRAMEX_IPC_H
