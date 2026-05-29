# FrameX

> Real-time performance overlay for Android, including a 1:1 replica of Apple's **Metal Performance HUD**. Powered by **root (su)** with real Vulkan/OpenGL GPU instrumentation.

> [!IMPORTANT]
> The **Gaming Performance Mode** engine is a specialized implementation specifically hardened and optimized for **Android 16** and **Vivo OriginOS/FuntouchOS** environments. Its package suspension and restricted-state flows are custom-tailored for peak efficiency on these devices and may not reflect universal behavior on all Android skins.

[![API](https://img.shields.io/badge/API-26%2B-brightgreen.svg?style=flat)](https://developer.android.com/about/versions/oreo)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

---

## What it does

FrameX shows a draggable, fully customisable overlay with live system stats on top of any app or game — including full-screen titles.

It ships **two** overlays:

1. **Performance HUD** — an expanded replica of Apple's Metal Performance HUD rendered in a transparent WebView. Shows device, resolution, refresh rate, thermal state, FPS, GPU time, present delay, frame interval, the CPU **Command Buffer / Render / Compute / Blit** encoder breakdown, memory, and a live frame graph.
2. **Compact Compose overlay** — the original lightweight FPS/CPU/RAM/temp/net/ping overlay.

**Performance Mode:** Optimize your device for gaming by suspending bloatware, restricting background apps, and enabling advanced Do Not Disturb.

---

## How the GPU metrics work

Per-encoder GPU/CPU timings cannot be read from `dumpsys` — they only exist inside the graphics driver. FrameX therefore injects a native **instrumentation layer** into the target game using Android's built-in GPU debug layer mechanism (the same one Android GPU Inspector uses), enabled at runtime via root:

- **`VK_LAYER_framex_hud`** (Vulkan) wraps command-buffer recording, render passes, dispatches, transfer/blit commands, `vkQueueSubmit` and `vkQueuePresentKHR`, plus GPU timestamp queries.
- **`libGLES_framex_hud.so`** (OpenGL ES) measures frame interval at `eglSwapBuffers` and GPU time via `GL_EXT_disjoint_timer_query`.

The layer streams one packet per frame over an abstract `LocalSocket` to the HUD service. FPS for un-instrumented apps falls back to **SurfaceFlinger** present timestamps.

> Vulkan/GLES have no "encoders" like Metal; the encoder figures are the closest faithful mapping (render pass → render encoder, dispatch recording → compute encoder, transfer/blit recording → blit encoder). GLES reports `—` for the encoder split.

---

## Requirements

- Android 8.0 (API 26) or higher
- A **rooted device** (Magisk / KernelSU) — FrameX runs a persistent `su` shell
- Per-frame GPU/encoder data additionally requires the device's graphics stack to honor the GPU debug layer settings (most modern Android 10+ ROMs do)

---

## How to use

1. Open FrameX and complete onboarding — it requests overlay access and root
2. Grant the **superuser** prompt when it appears (remembered afterwards on Magisk)
3. From the dashboard tap **Start Overlay** (compact) or start the **Performance HUD**
4. **Launch / relaunch your game** — the GPU layer attaches when the game's graphics API initialises
5. **Drag** the HUD anywhere on screen
6. **Performance** — Activate Gaming Mode to suspend OEM bloatware and restrict background noise

---

## Project structure

```
app/src/main/
├── cpp/                          # Native GPU instrumentation (NDK + CMake)
│   ├── framex_vk_layer.cpp       # VK_LAYER_framex_hud — Vulkan hook
│   ├── framex_gles_layer.cpp     # GLES hook (timer queries)
│   ├── framex_ipc.{h,cpp}        # Per-frame abstract-socket client → HUD
│   └── CMakeLists.txt            # builds libVkLayer_framex_hud.so + libGLES_framex_hud.so
├── assets/hud.html               # Metal HUD WebView UI
└── java/com/framex/app/
    ├── core/root/                # RootShell + RootManager — the shared su pipeline
    ├── hud/                      # FloatingWindowService, HudIpcServer, GpuLayerInjector, HudTelemetry
    ├── gaming/                   # Gaming Mode engine + services
    ├── metrics/                  # Monitors + MetricsEngine (compact overlay)
    ├── overlay/                  # Compose overlay service + window manager
    ├── repository/               # SettingsRepository (SharedPreferences)
    └── ui/                       # Compose screens, navigation, theme
```

All privileged work goes through a single `RootManager` (`core/root`) — one `su` session shared by the HUD, the monitors, and Gaming Mode.

---

## Appearance options

- **Background** — Black / Navy / Charcoal / Transparent
- **Border** — Accent color / None / Subtle / Ghost
- **Text color** — White / Accent / Silver / Auto (FPS-adaptive green → amber → red)
- **Accent color** — 6 presets
- **Text size** — Small / Medium / Large
- **Font** — Default or JetBrains Mono

All settings and the last overlay position persist across restarts.

---

## Build

Standard Android Gradle project with a native (NDK/CMake) component for the GPU layers. Requires **JDK 17**, **Android SDK 34**, and the **Android NDK**.

```bash
git clone https://github.com/MaheshSharan/FrameX-Android.git
cd FrameX-Android
./gradlew assembleDebug
```

The Vulkan/GLES layers are built for `arm64-v8a` and `armeabi-v7a` and packaged uncompressed so the target app's loader can `dlopen` them.

---

## Permissions

| Permission | Why |
|---|---|
| Draw over other apps | Display the overlay/HUD on top of games |
| Foreground service | Keep the overlay alive while the screen is on |
| Wake lock | Prevent CPU sleep during an active session |
| PACKAGE_USAGE_STATS | Identify which game is in the foreground |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | Survive aggressive OEM background-kill policies |
| Receive boot completed | Auto-restart overlay after reboot if it was active |
| Internet | Ping measurement to google.com only |
| Kill background processes | Purge cached background apps during Gaming Mode |
| Access notification policy | Toggle Do Not Disturb automatically |
| Foreground service (Special Use) | Keep Gaming Mode active on Android 14+ |

Root (`su`) is requested at runtime — there is no manifest permission for it.

---

## Security & Privacy

- **No data collection** — nothing is sent anywhere
- **No analytics** — no Firebase, no Crashlytics, no tracking SDKs
- **No accounts** — FrameX has no sign-in or user identity
- **No ads** — ever
- All data stays on-device

[Full Privacy Policy](https://maheshsharan.github.io/FrameX-Android/privacy-policy)

---

## License

```
MIT License — see LICENSE for details.
```
