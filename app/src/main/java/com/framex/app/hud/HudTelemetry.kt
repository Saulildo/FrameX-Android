package com.framex.app.hud

import com.framex.app.core.root.RootManager

/**
 * The full expanded snapshot pushed to the HUD each cycle. Combines:
 *  - device/display/memory/thermal facts gathered over root (`dumpsys`, `/proc/meminfo`), and
 *  - per-frame GPU/CPU timings streamed from the injected Vulkan/GLES layer ([GpuFrameStats]).
 *
 * Times are in milliseconds. A value < 0 means "not applicable" (e.g. GLES has no encoder split)
 * and the UI renders it as "—".
 */
data class HudTelemetry(
    val device: String,
    val resolution: String,
    val scale: String,
    val hz: Int,
    val thermal: String,            // "Nominal" / "Fair" / "Severe"
    val api: String,                // "Vulkan" / "OpenGL" / "—" (no layer attached)
    val fps: Double,
    val gpuMs: Double,
    val presentDelayMs: Double,
    val frameIntervalMs: Double,
    val cmdBufferCpuMs: Double,
    val renderEncoderCpuMs: Double,
    val computeEncoderCpuMs: Double,
    val blitEncoderMs: Double,
    val memUsedGb: Double,
    val memTotalMb: Double,
    val memAvailMb: Double,
)

/**
 * Builds [HudTelemetry] by merging root-gathered device facts with the injected layer's per-frame
 * timings.
 *
 * - **GPU ms / present delay / frame interval / encoder CPU times** come from the layer
 *   ([GpuFrameStats]) — there is no other way to see inside Vulkan/GLES.
 * - **FPS** is derived from the layer's frame interval (1000 / interval) when a layer is attached;
 *   otherwise it falls back to SurfaceFlinger present timestamps so the HUD still shows a real
 *   frame rate for un-instrumented apps.
 * - **Resolution / refresh rate / device / memory / thermal** come from root and are cached
 *   (refreshed every few seconds) since they change slowly.
 *
 * Fast values pass through a light EMA so the readout is smooth but low-latency.
 */
class HudTelemetryCollector(private val root: RootManager) {

    // Cached "slow" fields.
    private var cachedDevice = "Android"
    private var cachedRes = "0x0"
    private var cachedHz = 60
    private var cachedThermal = "Nominal"
    private var cachedMemTotalMb = 0.0

    // Smoothed / last-known fast fields.
    private var smoothFps = 0.0
    private var smoothGpu = 0.0
    private var smoothPresent = 0.0
    private var smoothInterval = 0.0
    private var smoothCmd = 0.0
    private var smoothRender = 0.0
    private var smoothCompute = 0.0
    private var smoothBlit = 0.0
    private var lastMemUsedGb = 0.0
    private var lastMemAvailMb = 0.0

    private var tick = 0L

    /**
     * Runs one sample. [gpu] is the most recent frame from the injected layer, or null if no layer
     * is attached / its data is stale (in which case FPS falls back to SurfaceFlinger).
     */
    suspend fun sample(gpu: GpuFrameStats?): HudTelemetry? {
        val haveLayer = gpu != null
        val includeSlow = (tick % SLOW_EVERY == 0L)
        tick++

        val raw = root.executeCommand(buildScript(includeSlow, includeFpsFallback = !haveLayer))
        if (raw.isBlank() && !root.isAlive) return null

        val sections = splitSections(raw)

        if (includeSlow) {
            parseDevice(sections[SEC_MODEL])?.let { cachedDevice = it }
            parseResolution(sections[SEC_SIZE])?.let { cachedRes = it }
            parseHz(sections[SEC_DISPLAY])?.let { cachedHz = it }
            parseThermal(sections[SEC_THERMAL])?.let { cachedThermal = it }
        }

        parseMeminfo(sections[SEC_MEM])?.let { (usedGb, totalMb, availMb) ->
            lastMemUsedGb = usedGb
            cachedMemTotalMb = totalMb
            lastMemAvailMb = availMb
        }

        val api: String
        if (haveLayer) {
            api = gpu!!.api
            val layerFps = if (gpu.frameIntervalMs > 0.0) 1000.0 / gpu.frameIntervalMs else 0.0
            if (layerFps > 0.0) smoothFps = ema(smoothFps, layerFps, FPS_ALPHA)
            smoothGpu = emaSigned(smoothGpu, gpu.gpuMs)
            smoothPresent = emaSigned(smoothPresent, gpu.presentDelayMs)
            smoothInterval = emaSigned(smoothInterval, gpu.frameIntervalMs)
            smoothCmd = emaSigned(smoothCmd, gpu.cmdBufferCpuMs)
            smoothRender = emaSigned(smoothRender, gpu.renderEncoderCpuMs)
            smoothCompute = emaSigned(smoothCompute, gpu.computeEncoderCpuMs)
            smoothBlit = emaSigned(smoothBlit, gpu.blitEncoderMs)
        } else {
            api = "—"
            parseLatencyFps(sections[SEC_LATENCY])?.let { fps ->
                if (fps > 0.0) smoothFps = ema(smoothFps, fps, FPS_ALPHA)
            }
            // No layer => no GPU/encoder visibility.
            smoothGpu = -1.0
            smoothPresent = -1.0
            smoothCmd = -1.0
            smoothRender = -1.0
            smoothCompute = -1.0
            smoothBlit = -1.0
            smoothInterval = if (smoothFps > 0) 1000.0 / smoothFps else 0.0
        }

        return HudTelemetry(
            device = cachedDevice,
            resolution = cachedRes,
            scale = "1.0x Direct",
            hz = cachedHz,
            thermal = cachedThermal,
            api = api,
            fps = smoothFps,
            gpuMs = smoothGpu,
            presentDelayMs = smoothPresent,
            frameIntervalMs = smoothInterval,
            cmdBufferCpuMs = smoothCmd,
            renderEncoderCpuMs = smoothRender,
            computeEncoderCpuMs = smoothCompute,
            blitEncoderMs = smoothBlit,
            memUsedGb = lastMemUsedGb,
            memTotalMb = cachedMemTotalMb,
            memAvailMb = lastMemAvailMb,
        )
    }

    // --- batched script -----------------------------------------------------

    private fun buildScript(includeSlow: Boolean, includeFpsFallback: Boolean): String {
        val lines = ArrayList<String>()
        if (includeSlow) {
            lines += "echo $MARKER_PREFIX$SEC_MODEL"
            lines += """
                SOC_MODEL=${'$'}(getprop ro.soc.model)
                SOC_MAKER=${'$'}(getprop ro.soc.manufacturer)
                CHIP=${'$'}(getprop ro.vendor.mediatek.platform)
                [ -z "${'$'}CHIP" ] && CHIP=${'$'}(getprop ro.board.platform)
                CPUINFO=${'$'}(grep -m1 -E 'Hardware|Processor|model name' /proc/cpuinfo 2>/dev/null | cut -d: -f2- | xargs)
                if [ -n "${'$'}SOC_MODEL" ]; then
                  if [ -n "${'$'}SOC_MAKER" ]; then echo "${'$'}SOC_MAKER ${'$'}SOC_MODEL"; else echo "${'$'}SOC_MODEL"; fi
                elif [ -n "${'$'}CHIP" ]; then
                  echo "${'$'}CHIP"
                elif [ -n "${'$'}CPUINFO" ]; then
                  echo "${'$'}CPUINFO"
                else
                  getprop ro.product.model
                fi
            """.trimIndent()
            lines += "echo $MARKER_PREFIX$SEC_SIZE"
            lines += "wm size"
            lines += "echo $MARKER_PREFIX$SEC_DISPLAY"
            lines += "dumpsys display | grep -iE 'fps=|refreshrate' | head -n 8"
            lines += "echo $MARKER_PREFIX$SEC_THERMAL"
            lines += "dumpsys thermalservice | grep -iE 'Thermal Status' | head -n 1"
        }
        lines += "echo $MARKER_PREFIX$SEC_MEM"
        lines += "grep -E 'MemTotal|MemAvailable' /proc/meminfo"

        // Only pay for SurfaceFlinger present timestamps when no GPU layer is feeding us FPS.
        if (includeFpsFallback) {
            lines += "FX_PKG=\$(dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' " +
                "| grep -oE '[a-zA-Z0-9._]+/[a-zA-Z0-9._]+' | head -n1 | cut -d/ -f1)"
            lines += "echo $MARKER_PREFIX$SEC_LATENCY"
            lines += "if [ -n \"\$FX_PKG\" ]; then " +
                "LIST=\$(dumpsys SurfaceFlinger --list 2>/dev/null); " +
                "LAYER=\$(echo \"\$LIST\" | grep -F \"\$FX_PKG\" | grep -iF SurfaceView | tail -n1); " +
                "[ -z \"\$LAYER\" ] && LAYER=\$(echo \"\$LIST\" | grep -F \"\$FX_PKG\" | tail -n1); " +
                "dumpsys SurfaceFlinger --latency \"\$LAYER\" 2>/dev/null; fi"
        }
        return lines.joinToString("\n")
    }

    // --- section splitting --------------------------------------------------

    private fun splitSections(raw: String): Map<String, String> {
        val result = HashMap<String, String>()
        var current: String? = null
        val sb = StringBuilder()
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith(MARKER_PREFIX)) {
                current?.let { result[it] = sb.toString() }
                sb.setLength(0)
                current = trimmed.removePrefix(MARKER_PREFIX)
            } else {
                sb.append(line).append('\n')
            }
        }
        current?.let { result[it] = sb.toString() }
        return result
    }

    // --- per-section parsers ------------------------------------------------

    private fun parseDevice(block: String?): String? =
        block?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }

    private fun parseResolution(block: String?): String? {
        block ?: return null
        val rx = Regex("(\\d+)x(\\d+)")
        var physical: String? = null
        var override: String? = null
        for (line in block.lineSequence()) {
            val m = rx.find(line) ?: continue
            val value = "${m.groupValues[1]}x${m.groupValues[2]}"
            when {
                line.contains("Override", ignoreCase = true) -> override = value
                line.contains("Physical", ignoreCase = true) -> physical = value
                physical == null -> physical = value
            }
        }
        return override ?: physical
    }

    private fun parseHz(block: String?): Int? {
        block ?: return null
        val rx = Regex("(?:fps|refreshRate|RefreshRate)\\s*[=: ]\\s*([0-9]+(?:\\.[0-9]+)?)")
        var best = 0.0
        for (m in rx.findAll(block)) {
            val v = m.groupValues[1].toDoubleOrNull() ?: continue
            if (v in 20.0..600.0 && v > best) best = v
        }
        return if (best > 0) Math.round(best).toInt() else null
    }

    /** Map the numeric PowerManager thermal status (0..6) to the three Metal-HUD-style labels. */
    private fun parseThermal(block: String?): String? {
        block ?: return null
        val m = Regex("Thermal Status\\s*[:=]?\\s*(\\d+)").find(block) ?: return null
        return when (m.groupValues[1].toIntOrNull() ?: 0) {
            0 -> "Nominal"
            1, 2 -> "Fair"
            else -> "Severe"
        }
    }

    /** @return Triple(usedGb, totalMb, availMb) from /proc/meminfo (kB values). */
    private fun parseMeminfo(block: String?): Triple<Double, Double, Double>? {
        block ?: return null
        var totalKb = 0L
        var availKb = 0L
        val rx = Regex("(MemTotal|MemAvailable):\\s+(\\d+)")
        for (m in rx.findAll(block)) {
            val kb = m.groupValues[2].toLongOrNull() ?: continue
            if (m.groupValues[1] == "MemTotal") totalKb = kb else availKb = kb
        }
        if (totalKb <= 0L) return null
        val usedKb = (totalKb - availKb).coerceAtLeast(0L)
        return Triple(usedKb / 1024.0 / 1024.0, totalKb / 1024.0, availKb / 1024.0)
    }

    /**
     * Fallback FPS from `dumpsys SurfaceFlinger --latency <layer>` (present timestamps, middle
     * column) over the most recent ~1s window. Used only when no GPU layer is attached.
     */
    private fun parseLatencyFps(block: String?): Double? {
        block ?: return null
        val present = ArrayList<Long>()
        for (line in block.lineSequence()) {
            val toks = line.trim().split(Regex("\\s+"))
            if (toks.size < 3) continue
            if (toks[0].toLongOrNull() == null || toks[2].toLongOrNull() == null) continue
            val b = toks[1].toLongOrNull() ?: continue
            if (b <= 0L || b == PENDING) continue
            present.add(b)
        }
        if (present.size < 2) return null
        present.sort()
        val newest = present.last()
        val cutoff = newest - FPS_WINDOW_NS
        val recent = present.filter { it >= cutoff }
        val use = if (recent.size >= 2) recent else present
        val span = use.last() - use.first()
        if (span <= 0L) return null
        return ((use.size - 1) / (span / 1_000_000_000.0)).coerceIn(0.0, 1000.0)
    }

    // --- helpers ------------------------------------------------------------

    private fun ema(prev: Double, next: Double, alpha: Double): Double =
        if (prev <= 0.0) next else alpha * next + (1.0 - alpha) * prev

    /** EMA that preserves the "n/a" (-1) sentinel instead of smoothing toward it. */
    private fun emaSigned(prev: Double, next: Double): Double {
        if (next < 0.0) return -1.0
        return if (prev <= 0.0) next else TIMING_ALPHA * next + (1.0 - TIMING_ALPHA) * prev
    }

    private companion object {
        const val MARKER_PREFIX = "@@FX@@"

        const val SEC_MODEL = "MODEL"
        const val SEC_SIZE = "SIZE"
        const val SEC_DISPLAY = "DISPLAY"
        const val SEC_THERMAL = "THERMAL"
        const val SEC_MEM = "MEM"
        const val SEC_LATENCY = "LATENCY"

        const val PENDING = Long.MAX_VALUE
        const val FPS_WINDOW_NS = 1_000_000_000L
        const val SLOW_EVERY = 16L
        const val FPS_ALPHA = 0.5
        const val TIMING_ALPHA = 0.4
    }
}
