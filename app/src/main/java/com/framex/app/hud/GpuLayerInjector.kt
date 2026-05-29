package com.framex.app.hud

import android.util.Log
import com.framex.app.core.root.RootManager

/**
 * Enables/disables injection of the FrameX Vulkan & GLES instrumentation layers into a target
 * app, using Android's built-in GPU debug layer mechanism (the same one Android GPU Inspector
 * uses). Requires root to write the `global` settings reliably for an arbitrary package.
 *
 * How it works:
 *  - `gpu_debug_layer_app` points the loader at *our* APK, whose nativeLibraryDir contains
 *    `libVkLayer_framex_hud.so` and `libGLES_framex_hud.so`.
 *  - `gpu_debug_app` names the target whose process should load the layers.
 *  - `gpu_debug_layers` (Vulkan, by layer name) and `gpu_debug_layers_gles` (GLES, by .so name)
 *    select which layers to inject.
 *
 * Important: layers are loaded only when the target's render API initialises, i.e. at process
 * start. A game already running must be relaunched (optionally via [enable]'s forceRestart) for
 * the layer to attach.
 */
class GpuLayerInjector(private val root: RootManager) {

    @Volatile
    private var injectedPackage: String? = null

    /** The package currently targeted for injection, if any. */
    val currentTarget: String? get() = injectedPackage

    /**
     * Turns on layer injection for [targetPackage].
     * @param forceRestart if true, force-stops the target so it relaunches with the layer attached.
     */
    suspend fun enable(targetPackage: String, forceRestart: Boolean = false): Boolean {
        if (!root.isRootAvailable.value) return false
        val script = buildString {
            appendLine("settings put global enable_gpu_debug_layers 1")
            appendLine("settings put global gpu_debug_app $targetPackage")
            appendLine("settings put global gpu_debug_layer_app $OUR_PACKAGE")
            appendLine("settings put global gpu_debug_layers $VK_LAYER_NAME")
            appendLine("settings put global gpu_debug_layers_gles $GLES_LAYER_SO")
            if (forceRestart) appendLine("am force-stop $targetPackage")
        }
        root.executeCommand(script)
        injectedPackage = targetPackage
        Log.i(TAG, "GPU layer injection enabled for $targetPackage (restart=$forceRestart)")
        return true
    }

    /** Clears all GPU debug layer settings so no further apps are instrumented. */
    suspend fun disable() {
        if (!root.isAlive) {
            injectedPackage = null
            return
        }
        root.executeCommand(
            buildString {
                appendLine("settings delete global gpu_debug_layers")
                appendLine("settings delete global gpu_debug_layers_gles")
                appendLine("settings delete global gpu_debug_app")
                appendLine("settings delete global gpu_debug_layer_app")
                appendLine("settings put global enable_gpu_debug_layers 0")
            },
        )
        injectedPackage = null
        Log.i(TAG, "GPU layer injection disabled")
    }

    /**
     * Some devices block the cross-process abstract socket (untrusted_app -> our app) under
     * enforcing SELinux. This is an explicit, opt-in escape hatch — it lowers device security for
     * the session, so it is off by default and the UI should warn before using it.
     */
    suspend fun setSelinuxPermissive(permissive: Boolean) {
        if (!root.isAlive) return
        root.executeCommand(if (permissive) "setenforce 0" else "setenforce 1")
        Log.w(TAG, "SELinux set to ${if (permissive) "permissive" else "enforcing"}")
    }

    private companion object {
        const val TAG = "FrameX_GpuInjector"
        const val OUR_PACKAGE = "com.framex.app"
        const val VK_LAYER_NAME = "VK_LAYER_framex_hud"
        const val GLES_LAYER_SO = "libGLES_framex_hud.so"
    }
}
