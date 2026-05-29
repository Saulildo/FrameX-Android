package com.framex.app.hud

import android.util.Log

/**
 * One snapshot of everything the HUD needs. Maps 1:1 onto the JS contract:
 * `updateHudData(device, res, hz, fps, pre, gpu, mem, maxMem)`.
 *
 * @param device    product model, e.g. "Pixel 8 Pro"
 * @param res       active resolution, e.g. "1080x2400"
 * @param hz        active display refresh rate (Hz)
 * @param fps       app frames per second (measured from gfxinfo frame timestamps)
 * @param preMs     CPU render time per frame in ms ("Pre" = CPU work before/around GPU submit)
 * @param gpuMs     GPU render time per frame in ms (GpuCompleted - SwapBuffers)
 * @param memUsedGb system RAM in use, in GB (MemTotal - MemAvailable)
 * @param memMaxMb  total system RAM, in MB (the ceiling shown in the green bracket)
 */
data class HudTelemetry(
    val device: String,
    val res: String,
    val hz: Int,
    val fps: Double,
    val preMs: Double,
    val gpuMs: Double,
    val memUsedGb: Double,
    val memMaxMb: Double,
)

/**
 * Collects real device telemetry through a [RootShell].
 *
 * Efficiency: every poll issues ONE batched shell script (a single round-trip through the
 * persistent su pipe) that gathers model, resolution, refresh rate, /proc/meminfo, the
 * foreground package, and that package's `gfxinfo framestats`. Output is delimited by
 * section markers so a single pass parses it all. After reading the frame stats we reset
 * gfxinfo so the next poll only contains frames from the last interval — that makes the
 * frame count a clean basis for FPS without unbounded accumulation.
 *
 * Static-ish values (model, resolution, total RAM, refresh rate) are cached after the first
 * successful read so transient parse misses never blank them out.
 */
class HudTelemetryCollector(private val shell: RootShell) {

    // Cached "slow" fields - they rarely (or never) change during a session.
    private var cachedDevice = "Android"
    private var cachedRes = "0x0"
    private var cachedHz = 60
    private var cachedMemMaxMb = 0.0

    // Last-known fast fields so a frame with no new data holds steady instead of flashing 0.
    private var lastFps = 0.0
    private var lastPre = 0.0
    private var lastGpu = 0.0
    private var lastMemUsedGb = 0.0

    /**
     * Runs one batched sample. Returns null only if root is completely unavailable;
     * otherwise returns a fully-populated snapshot (using cached/last-known values for any
     * section that produced no fresh data this cycle).
     */
    suspend fun sample(): HudTelemetry? {
        val raw = shell.exec(BATCH_SCRIPT)
        if (raw.isBlank() && !shell.isAlive) return null

        val sections = splitSections(raw)

        parseDevice(sections[SEC_MODEL])?.let { cachedDevice = it }
        parseResolution(sections[SEC_SIZE])?.let { cachedRes = it }
        parseHz(sections[SEC_DISPLAY])?.let { cachedHz = it }

        val mem = parseMeminfo(sections[SEC_MEM])
        if (mem != null) {
            lastMemUsedGb = mem.first
            cachedMemMaxMb = mem.second
        }

        val frames = parseFrameStats(sections[SEC_GFX])
        if (frames != null) {
            lastFps = frames.fps
            lastPre = frames.preMs
            lastGpu = frames.gpuMs
        }

        return HudTelemetry(
            device = cachedDevice,
            res = cachedRes,
            hz = cachedHz,
            fps = lastFps,
            preMs = lastPre,
            gpuMs = lastGpu,
            memUsedGb = lastMemUsedGb,
            memMaxMb = cachedMemMaxMb,
        )
    }

    // --- section splitting --------------------------------------------------

    private fun splitSections(raw: String): Map<String, String> {
        val result = HashMap<String, String>()
        var current: String? = null
        val sb = StringBuilder()
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith(MARKER_PREFIX)) {
                // Flush the previous section.
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

    /** Prefer an "Override size" (the size apps actually render at) over "Physical size". */
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

    /**
     * Pull the active refresh rate out of `dumpsys display`. Different ROMs phrase it as
     * `fps=120.0`, `refreshRate 120.0`, `mRefreshRate=120.0`, etc. We grab every candidate
     * float that sits next to one of those tokens and take the max (the active high-refresh mode).
     */
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

    /** @return Pair(usedGb, totalMb) parsed from /proc/meminfo (values are in kB). */
    private fun parseMeminfo(block: String?): Pair<Double, Double>? {
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
        val usedGb = usedKb / 1024.0 / 1024.0
        val totalMb = totalKb / 1024.0
        return usedGb to totalMb
    }

    private data class FrameAgg(val fps: Double, val preMs: Double, val gpuMs: Double)

    /**
     * Parse `gfxinfo <pkg> framestats`. Frame rows live between `---PROFILEDATA---` fences as
     * comma-separated nanosecond timestamps. Column layout (HWUI):
     *
     *   0 Flags  1 IntendedVsync  2 Vsync ... 8 DrawStart ... 11 IssueDrawCommandsStart
     *   12 SwapBuffers  13 FrameCompleted ... 16 GpuCompleted
     *
     * Definitions (matching Apple's HUD semantics as closely as Android exposes):
     *   Pre (CPU) = SwapBuffers - DrawStart   (record + sync + issue, the CPU's per-frame work)
     *   GPU       = GpuCompleted - SwapBuffers (GPU execution; falls back to FrameCompleted)
     *   FPS       = (n-1) / (lastIntendedVsync - firstIntendedVsync)
     *
     * Rows with non-zero Flags (first-frame / janky markers) are skipped to avoid skewing.
     */
    private fun parseFrameStats(block: String?): FrameAgg? {
        block ?: return null

        var sumPre = 0.0
        var sumGpu = 0.0
        var count = 0
        var firstVsync = Long.MAX_VALUE
        var lastVsync = Long.MIN_VALUE

        var inProfile = false
        for (rawLine in block.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith("---PROFILEDATA")) {
                inProfile = !inProfile
                continue
            }
            if (!inProfile || line.isEmpty()) continue
            if (line.startsWith("Flags", ignoreCase = true)) continue // header

            val cols = line.split(",")
            if (cols.size < 14) continue

            val flags = cols[0].trim().toLongOrNull() ?: continue
            if (flags != 0L) continue // skip first-draw / janky frames

            val intendedVsync = cols[1].trim().toLongOrNull() ?: continue
            val drawStart = cols[8].trim().toLongOrNull() ?: continue
            val swapBuffers = cols[12].trim().toLongOrNull() ?: continue
            val frameCompleted = cols[13].trim().toLongOrNull() ?: continue
            val gpuCompleted = cols.getOrNull(16)?.trim()?.toLongOrNull() ?: 0L

            // Sanity: timestamps must be monotonically ordered for this frame.
            if (swapBuffers <= drawStart || frameCompleted < swapBuffers) continue

            val preNs = swapBuffers - drawStart
            val gpuNs = when {
                gpuCompleted > swapBuffers -> gpuCompleted - swapBuffers
                else -> frameCompleted - swapBuffers // GPU timestamp unavailable on this ROM
            }

            sumPre += preNs / 1_000_000.0
            sumGpu += gpuNs / 1_000_000.0
            count++

            if (intendedVsync < firstVsync) firstVsync = intendedVsync
            if (intendedVsync > lastVsync) lastVsync = intendedVsync
        }

        if (count == 0) return null

        val spanSec = (lastVsync - firstVsync) / 1_000_000_000.0
        val fps = if (count >= 2 && spanSec > 0) (count - 1) / spanSec else 0.0

        return FrameAgg(
            fps = fps.coerceIn(0.0, 1000.0),
            preMs = sumPre / count,
            gpuMs = sumGpu / count,
        ).also {
            Log.v(TAG, "frames=$count fps=${it.fps} pre=${it.preMs} gpu=${it.gpuMs}")
        }
    }

    private companion object {
        const val TAG = "FrameX_HudTelemetry"
        const val MARKER_PREFIX = "@@FX@@"

        const val SEC_MODEL = "MODEL"
        const val SEC_SIZE = "SIZE"
        const val SEC_DISPLAY = "DISPLAY"
        const val SEC_MEM = "MEM"
        const val SEC_GFX = "GFX"

        /**
         * Single batched script. Section markers (`@@FX@@<NAME>`) let us parse one blob in a
         * single pass. The foreground package is resolved inline via command substitution so the
         * whole sample is still one round-trip. `gfxinfo ... reset` at the end clears the buffer
         * so the next poll's frame count corresponds to this interval only.
         */
        val BATCH_SCRIPT = """
            echo $MARKER_PREFIX$SEC_MODEL
            getprop ro.product.model
            echo $MARKER_PREFIX$SEC_SIZE
            wm size
            echo $MARKER_PREFIX$SEC_DISPLAY
            dumpsys display | grep -iE "fps=|refreshrate" | head -n 8
            echo $MARKER_PREFIX$SEC_MEM
            grep -E "MemTotal|MemAvailable" /proc/meminfo
            echo $MARKER_PREFIX$SEC_GFX
            FX_PKG=${'$'}(dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp" | grep -oE "[a-zA-Z0-9._]+/[a-zA-Z0-9._]+" | head -n 1 | cut -d/ -f1)
            dumpsys gfxinfo ${'$'}FX_PKG framestats 2>/dev/null
            dumpsys gfxinfo ${'$'}FX_PKG reset >/dev/null 2>&1
        """.trimIndent()
    }
}
