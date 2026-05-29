package com.framex.app.hud

import android.util.Log

/**
 * One snapshot of everything the HUD needs. Maps 1:1 onto the JS contract:
 * `updateHudData(device, res, hz, fps, pre, gpu, mem, maxMem)`.
 *
 * @param device    product model, e.g. "Pixel 8 Pro"
 * @param res       active resolution, e.g. "1080x2400"
 * @param hz        active display refresh rate (Hz)
 * @param fps       presented frames per second (SurfaceFlinger present timestamps)
 * @param preMs     CPU render time per frame in ms ("Pre" = CPU work up to buffer swap)
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
 * Collects real device telemetry through a [RootShell], tuned for Metal-HUD-grade accuracy:
 * presentation-accurate FPS, a CPU/GPU per-frame split, low latency, and smooth output.
 *
 * ### Where each number comes from
 * - **FPS / frametime** → `dumpsys SurfaceFlinger --latency <layer>`. These are the timestamps
 *   of frames *actually presented to the display*, so the rate matches what the eye sees. Unlike
 *   per-app `gfxinfo`, this also works for games that render through SurfaceView / GL / Vulkan
 *   (which bypass HWUI entirely). FPS is computed over a sliding ~1s window of the most recent
 *   present timestamps, giving low delay without single-frame jitter.
 * - **Pre (CPU) / GPU** → `dumpsys gfxinfo <pkg> framestats`. HWUI reports per-frame nanosecond
 *   stamps for the CPU pipeline (DrawStart…SwapBuffers) and GPU completion. We take the *median*
 *   over the last interval (robust to one-off spikes) and reset the buffer each poll so the
 *   sample only reflects recent frames. (gfxinfo is empty for pure-GL/Vulkan games; in that case
 *   Pre/GPU hold their last value — Android exposes no per-app GPU timing for those without a
 *   dedicated profiler.)
 * - **Mem** → `/proc/meminfo` (MemTotal − MemAvailable). **Device / Res / Hz** → cached and
 *   refreshed only every few seconds since they change rarely.
 *
 * ### Smoothing
 * Fast values pass through a light EMA so the readout is steady but still tracks changes within
 * a frame or two. Slow/static fields are cached so a transient parse miss never blanks them.
 */
class HudTelemetryCollector(private val shell: RootShell) {

    // Cached "slow" fields - they rarely (or never) change during a session.
    private var cachedDevice = "Android"
    private var cachedRes = "0x0"
    private var cachedHz = 60
    private var cachedMemMaxMb = 0.0

    // Smoothed/last-known fast fields so a frame with no new data holds steady instead of flashing 0.
    private var smoothFps = 0.0
    private var smoothPre = 0.0
    private var smoothGpu = 0.0
    private var lastMemUsedGb = 0.0

    private var tick = 0L

    /**
     * Runs one batched sample. Returns null only if root is completely unavailable;
     * otherwise returns a fully-populated snapshot (using cached/last-known values for any
     * section that produced no fresh data this cycle).
     */
    suspend fun sample(): HudTelemetry? {
        val includeSlow = (tick % SLOW_EVERY == 0L)
        tick++

        val raw = shell.exec(buildScript(includeSlow))
        if (raw.isBlank() && !shell.isAlive) return null

        val sections = splitSections(raw)

        if (includeSlow) {
            parseDevice(sections[SEC_MODEL])?.let { cachedDevice = it }
            parseResolution(sections[SEC_SIZE])?.let { cachedRes = it }
            parseHz(sections[SEC_DISPLAY])?.let { cachedHz = it }
        }

        parseMeminfo(sections[SEC_MEM])?.let { (usedGb, totalMb) ->
            lastMemUsedGb = usedGb
            cachedMemMaxMb = totalMb
        }

        // FPS: prefer presentation timestamps; fall back to gfxinfo's own frame rate if the
        // SurfaceFlinger layer reported nothing (e.g. layer name mismatch on some ROMs).
        val frames = parseFrameStats(sections[SEC_GFX])
        val presentFps = parseLatencyFps(sections[SEC_LATENCY])
        val freshFps = presentFps ?: frames?.fpsFallback

        if (freshFps != null && freshFps > 0.0) {
            smoothFps = ema(smoothFps, freshFps, FPS_ALPHA)
        }
        if (frames != null) {
            smoothPre = ema(smoothPre, frames.preMs, TIMING_ALPHA)
            smoothGpu = ema(smoothGpu, frames.gpuMs, TIMING_ALPHA)
        }

        return HudTelemetry(
            device = cachedDevice,
            res = cachedRes,
            hz = cachedHz,
            fps = smoothFps,
            preMs = smoothPre,
            gpuMs = smoothGpu,
            memUsedGb = lastMemUsedGb,
            memMaxMb = cachedMemMaxMb,
        )
    }

    // --- batched script -----------------------------------------------------

    /**
     * Builds the per-poll script. Section markers (`@@FX@@<NAME>`) let us parse one blob in a
     * single pass / single round-trip. Slow fields are included only every [SLOW_EVERY] ticks to
     * keep each fast poll cheap (lower delay). The foreground package is resolved inline so the
     * whole sample stays a single shell invocation.
     */
    private fun buildScript(includeSlow: Boolean): String {
        val lines = ArrayList<String>()
        if (includeSlow) {
            lines += "echo $MARKER_PREFIX$SEC_MODEL"
            lines += "getprop ro.product.model"
            lines += "echo $MARKER_PREFIX$SEC_SIZE"
            lines += "wm size"
            lines += "echo $MARKER_PREFIX$SEC_DISPLAY"
            lines += "dumpsys display | grep -iE 'fps=|refreshrate' | head -n 8"
        }
        lines += "echo $MARKER_PREFIX$SEC_MEM"
        lines += "grep -E 'MemTotal|MemAvailable' /proc/meminfo"
        // Foreground package (used for both the SurfaceFlinger layer and gfxinfo).
        lines += "FX_PKG=\$(dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' " +
            "| grep -oE '[a-zA-Z0-9._]+/[a-zA-Z0-9._]+' | head -n1 | cut -d/ -f1)"
        // FPS via presentation timestamps. Pick the presenting layer for the foreground app,
        // preferring its SurfaceView (games) over the activity window.
        lines += "echo $MARKER_PREFIX$SEC_LATENCY"
        lines += "if [ -n \"\$FX_PKG\" ]; then " +
            "LIST=\$(dumpsys SurfaceFlinger --list 2>/dev/null); " +
            "LAYER=\$(echo \"\$LIST\" | grep -F \"\$FX_PKG\" | grep -iF SurfaceView | tail -n1); " +
            "[ -z \"\$LAYER\" ] && LAYER=\$(echo \"\$LIST\" | grep -F \"\$FX_PKG\" | tail -n1); " +
            "dumpsys SurfaceFlinger --latency \"\$LAYER\" 2>/dev/null; fi"
        // Pre (CPU) / GPU split via HWUI framestats; reset so the next poll is a fresh window.
        lines += "echo $MARKER_PREFIX$SEC_GFX"
        lines += "if [ -n \"\$FX_PKG\" ]; then " +
            "dumpsys gfxinfo \"\$FX_PKG\" framestats 2>/dev/null; " +
            "dumpsys gfxinfo \"\$FX_PKG\" reset >/dev/null 2>&1; fi"
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
     * float next to one of those tokens and take the max (the active high-refresh mode).
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
        return (usedKb / 1024.0 / 1024.0) to (totalKb / 1024.0)
    }

    /**
     * FPS from `dumpsys SurfaceFlinger --latency <layer>`.
     *
     * Output: line 1 is the refresh period (ns); each following line has three nanosecond
     * timestamps `desiredPresentTime  actualPresentTime  frameReadyTime`. We use the middle
     * column (actual present time = when the frame hit the display) — the standard, accurate
     * basis for on-screen FPS. Rows that are 0 or INT64_MAX (no data / pending) are dropped.
     *
     * To stay low-latency yet stable we measure over the most recent [FPS_WINDOW_NS] of present
     * times: `fps = (frames - 1) / span`.
     */
    private fun parseLatencyFps(block: String?): Double? {
        block ?: return null
        val present = ArrayList<Long>()
        for (line in block.lineSequence()) {
            val toks = line.trim().split(Regex("\\s+"))
            if (toks.size < 3) continue // skips the refresh-period header line (single token)
            // A valid frame row is three numeric columns; non-numeric/short lines are headers.
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
        val fps = (use.size - 1) / (span / 1_000_000_000.0)
        return fps.coerceIn(0.0, 1000.0)
    }

    private data class FrameAgg(val preMs: Double, val gpuMs: Double, val fpsFallback: Double)

    /**
     * Parse `gfxinfo <pkg> framestats`. Frame rows live between `---PROFILEDATA---` fences as
     * comma-separated nanosecond timestamps. Column layout (HWUI):
     *
     *   0 Flags  1 IntendedVsync  2 Vsync ... 8 DrawStart ... 11 IssueDrawCommandsStart
     *   12 SwapBuffers  13 FrameCompleted ... 16 GpuCompleted
     *
     *   Pre (CPU) = SwapBuffers   - DrawStart    (record + sync + issue: the CPU's frame work)
     *   GPU       = GpuCompleted  - SwapBuffers  (GPU execution; falls back to FrameCompleted)
     *
     * We use the **median** of each (robust to single-frame spikes) and derive an FPS fallback
     * from the IntendedVsync span. Rows with non-zero Flags (first-frame / janky) are skipped.
     */
    private fun parseFrameStats(block: String?): FrameAgg? {
        block ?: return null

        val preList = ArrayList<Double>()
        val gpuList = ArrayList<Double>()
        var firstVsync = Long.MAX_VALUE
        var lastVsync = Long.MIN_VALUE
        var count = 0

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
            if (flags != 0L) continue

            val intendedVsync = cols[1].trim().toLongOrNull() ?: continue
            val drawStart = cols[8].trim().toLongOrNull() ?: continue
            val swapBuffers = cols[12].trim().toLongOrNull() ?: continue
            val frameCompleted = cols[13].trim().toLongOrNull() ?: continue
            val gpuCompleted = cols.getOrNull(16)?.trim()?.toLongOrNull() ?: 0L

            if (swapBuffers <= drawStart || frameCompleted < swapBuffers) continue

            val preNs = swapBuffers - drawStart
            val gpuNs = if (gpuCompleted > swapBuffers) gpuCompleted - swapBuffers
            else frameCompleted - swapBuffers

            preList += preNs / 1_000_000.0
            gpuList += gpuNs / 1_000_000.0
            count++

            if (intendedVsync < firstVsync) firstVsync = intendedVsync
            if (intendedVsync > lastVsync) lastVsync = intendedVsync
        }

        if (count == 0) return null

        val spanSec = (lastVsync - firstVsync) / 1_000_000_000.0
        val fpsFallback = if (count >= 2 && spanSec > 0) (count - 1) / spanSec else 0.0

        return FrameAgg(
            preMs = median(preList),
            gpuMs = median(gpuList),
            fpsFallback = fpsFallback.coerceIn(0.0, 1000.0),
        ).also { Log.v(TAG, "gfx frames=$count pre=${it.preMs} gpu=${it.gpuMs}") }
    }

    // --- helpers ------------------------------------------------------------

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
    }

    /** Exponential moving average; seeds with the first real value to avoid a slow ramp from 0. */
    private fun ema(prev: Double, next: Double, alpha: Double): Double =
        if (prev <= 0.0) next else alpha * next + (1.0 - alpha) * prev

    private companion object {
        const val TAG = "FrameX_HudTelemetry"
        const val MARKER_PREFIX = "@@FX@@"

        const val SEC_MODEL = "MODEL"
        const val SEC_SIZE = "SIZE"
        const val SEC_DISPLAY = "DISPLAY"
        const val SEC_MEM = "MEM"
        const val SEC_LATENCY = "LATENCY"
        const val SEC_GFX = "GFX"

        // INT64_MAX sentinel SurfaceFlinger uses for pending/unknown present times.
        const val PENDING = Long.MAX_VALUE

        // FPS measured over the most recent 1s of present timestamps: stable but low-delay.
        const val FPS_WINDOW_NS = 1_000_000_000L

        // Refresh static-ish fields (model/res/hz) every N fast polls only.
        const val SLOW_EVERY = 16L

        // Smoothing: FPS tracks faster (less lag), per-frame timings a touch smoother.
        const val FPS_ALPHA = 0.5
        const val TIMING_ALPHA = 0.4
    }
}
