package com.framex.app.hud

import android.util.Log
import com.framex.app.core.root.RootManager

/**
 * Enables/disables FrameX graphics instrumentation for a target app.
 *
 * Preferred path:
 *  - If the FrameX Zygisk Magisk module is installed, write the selected package to the module's
 *    target file. The module then patches graphics present/swap imports inside that target app.
 *
 * Fallback path:
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
        if (!PACKAGE_RE.matches(targetPackage)) return false

        val script = buildString {
            appendLine("MOD=$ZYGISK_MODULE_DIR")
            appendLine("if [ -d \"${'$'}MOD\" ] && [ ! -f \"${'$'}MOD/disable\" ]; then")
            appendLine("  echo '$targetPackage' > \"${'$'}MOD/target.txt\"")
            appendLine("  chmod 0644 \"${'$'}MOD/target.txt\" 2>/dev/null")
            appendLine("  settings delete global gpu_debug_layers >/dev/null 2>&1")
            appendLine("  settings delete global gpu_debug_layers_gles >/dev/null 2>&1")
            appendLine("  settings delete global gpu_debug_app >/dev/null 2>&1")
            appendLine("  settings delete global gpu_debug_layer_app >/dev/null 2>&1")
            appendLine("  settings put global enable_gpu_debug_layers 0")
            if (forceRestart) appendLine("  am force-stop $targetPackage")
            appendLine("  echo FRAMEX_ZYGISK=1")
            appendLine("  echo FRAMEX_TARGET=${'$'}(cat \"${'$'}MOD/target.txt\" 2>/dev/null)")
            appendLine("else")
            appendLine("  settings put global enable_gpu_debug_layers 1")
            appendLine("  settings put global gpu_debug_app $targetPackage")
            appendLine("  settings put global gpu_debug_layer_app $OUR_PACKAGE")
            appendLine("  settings put global gpu_debug_layers $VK_LAYER_NAME")
            appendLine("  settings put global gpu_debug_layers_gles $GLES_LAYER_SO")
            if (forceRestart) appendLine("  am force-stop $targetPackage")
            appendLine("  echo FRAMEX_ZYGISK=0")
            appendLine("  echo FRAMEX_ENABLE=${'$'}(settings get global enable_gpu_debug_layers)")
            appendLine("  echo FRAMEX_APP=${'$'}(settings get global gpu_debug_app)")
            appendLine("  echo FRAMEX_LAYER_APP=${'$'}(settings get global gpu_debug_layer_app)")
            appendLine("  echo FRAMEX_VK=${'$'}(settings get global gpu_debug_layers)")
            appendLine("  echo FRAMEX_GLES=${'$'}(settings get global gpu_debug_layers_gles)")
            appendLine("fi")
        }
        val out = root.executeCommand(script)
        val applied = out.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx < 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            .toMap()

        val zygiskOk = applied["FRAMEX_ZYGISK"] == "1" &&
            applied["FRAMEX_TARGET"] == targetPackage
        val gpuDebugOk = applied["FRAMEX_ENABLE"] == "1" &&
            applied["FRAMEX_APP"] == targetPackage &&
            applied["FRAMEX_LAYER_APP"] == OUR_PACKAGE &&
            applied["FRAMEX_VK"] == VK_LAYER_NAME &&
            applied["FRAMEX_GLES"] == GLES_LAYER_SO
        val ok = zygiskOk || gpuDebugOk

        if (ok) {
            injectedPackage = targetPackage
            val mode = if (zygiskOk) "Zygisk" else "GPU debug layer"
            Log.i(TAG, "$mode injection enabled for $targetPackage (restart=$forceRestart)")
        } else {
            Log.w(TAG, "Injection settings did not apply for $targetPackage: $out")
        }
        return ok
    }

    /** Clears all GPU debug layer settings so no further apps are instrumented. */
    suspend fun disable() {
        if (!root.isAlive) {
            injectedPackage = null
            return
        }
        root.executeCommand(
            buildString {
                appendLine("MOD=$ZYGISK_MODULE_DIR")
                appendLine("[ -d \"${'$'}MOD\" ] && : > \"${'$'}MOD/target.txt\"")
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
        const val ZYGISK_MODULE_DIR = "/data/adb/modules/framex_zygisk"
        val PACKAGE_RE = Regex("[A-Za-z0-9_.]+")
    }
}
