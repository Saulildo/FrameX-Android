cmake_minimum_required(VERSION 3.18.1)
project(framex_layers LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# Vulkan instrumentation layer  ->  libVkLayer_framex_hud.so
#
# Discovered by the Android Vulkan loader via the layer NAME "VK_LAYER_framex_hud"
# (see GpuLayerInjector). We do NOT link libvulkan: a layer only uses function
# pointers handed to it through the loader<->layer call chain.
# ---------------------------------------------------------------------------
add_library(VkLayer_framex_hud SHARED
        framex_vk_layer.cpp
        framex_ipc.cpp)
target_compile_options(VkLayer_framex_hud PRIVATE -fvisibility=hidden -Wall -Wextra)
target_link_libraries(VkLayer_framex_hud log)

# ---------------------------------------------------------------------------
# GLES instrumentation layer  ->  libGLES_framex_hud.so
#
# Discovered by name (the .so filename) via the "gpu_debug_layers_gles" setting.
# ---------------------------------------------------------------------------
add_library(GLES_framex_hud SHARED
        framex_gles_layer.cpp
        framex_ipc.cpp)
target_compile_options(GLES_framex_hud PRIVATE -fvisibility=hidden -Wall -Wextra)
target_link_libraries(GLES_framex_hud log EGL GLESv2)

# ---------------------------------------------------------------------------
# Zygisk module -> packaged as zygisk/<abi>.so in the Magisk module zip.
#
# This does not rely on Android's GPU-debug-layer loader. It runs inside the target app process
# through Zygisk, patches graphics present/swap imports, and streams samples to the HUD IPC socket.
# ---------------------------------------------------------------------------
add_library(framex_zygisk SHARED
        framex_zygisk.cpp
        framex_ipc.cpp)
target_compile_options(framex_zygisk PRIVATE -fvisibility=hidden -Wall -Wextra)
target_link_libraries(framex_zygisk log EGL GLESv2 dl)
