package com.framex.app.core.root

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * A persistent root (`su`) shell — the single privileged-command primitive for the whole app.
 *
 * Why persistent: spawning a fresh `su -c "<cmd>"` process for every call is expensive (process
 * fork + SELinux transition + Magisk/SuperSU audit logging each time). For the HUD's sub-second
 * telemetry cadence that means visible jank and log spam. Instead we open ONE long-lived `su`
 * process, keep its stdin/stdout pipes open, and stream commands into it. Each command is
 * terminated by an `echo <unique-marker>` so we know exactly where its output ends.
 *
 * Thread-safety: a [Mutex] serializes commands so two coroutines never interleave their writes
 * into the single shell. All blocking I/O runs on [Dispatchers.IO].
 *
 * Prefer using the injected [RootManager] singleton rather than constructing this directly, so the
 * whole app shares one `su` session.
 */
class RootShell {

    private val mutex = Mutex()

    @Volatile private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    /** Cheap, non-throwing check used by callers to short-circuit when root isn't ready. */
    val isAlive: Boolean
        get() = process?.isAlive == true

    /**
     * Opens the `su` shell (if not already open) and verifies we actually got uid 0.
     * On a Magisk/SuperSU device this is where the grant dialog appears on first launch;
     * the call blocks on [Dispatchers.IO] until the user responds.
     *
     * @return true if a root shell is alive and confirmed (uid 0).
     */
    suspend fun init(): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) { ensureShellLocked() && verifyRootLocked() }
    }

    /**
     * Runs [command] in the persistent shell and returns its combined stdout/stderr.
     * Returns an empty string if root is unavailable or the shell died mid-command
     * (the next call will transparently attempt to respawn it).
     */
    suspend fun exec(command: String): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!ensureShellLocked()) return@withContext ""
            execLocked(command)
        }
    }

    /** Closes the shell and releases the pipes. Safe to call multiple times. */
    fun close() {
        try {
            writer?.apply {
                // Politely ask the shell to exit; ignore failures, we kill it anyway.
                runCatching {
                    write("exit\n")
                    flush()
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { writer?.close() }
            runCatching { reader?.close() }
            runCatching { process?.destroy() }
            writer = null
            reader = null
            process = null
        }
    }

    // --- internal (must be called while holding the mutex) ------------------

    private fun ensureShellLocked(): Boolean {
        if (process?.isAlive == true && writer != null && reader != null) return true
        // Stale/dead shell: clean up before respawning.
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        runCatching { process?.destroy() }
        writer = null
        reader = null
        process = null

        return try {
            // redirectErrorStream so stderr (e.g. "permission denied") arrives inline and never
            // blocks on a separate pipe we aren't draining.
            val p = ProcessBuilder("su")
                .redirectErrorStream(true)
                .start()
            process = p
            writer = BufferedWriter(OutputStreamWriter(p.outputStream))
            reader = BufferedReader(InputStreamReader(p.inputStream))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start su shell: ${e.message}")
            false
        }
    }

    private fun verifyRootLocked(): Boolean {
        val out = execLocked("id -u")
        val uid = out.trim().lines().firstOrNull()?.trim()?.toIntOrNull()
        val rooted = uid == 0
        if (!rooted) Log.w(TAG, "su shell did not return uid 0 (got '$out')")
        return rooted
    }

    private fun execLocked(command: String): String {
        val w = writer ?: return ""
        val r = reader ?: return ""
        val marker = "__FRAMEX_EOF_${System.nanoTime()}__"
        return try {
            w.write(command)
            w.write("\n")
            // Print the marker on its own line regardless of whether the command ended in a newline.
            w.write("echo $marker\n")
            w.flush()

            val sb = StringBuilder()
            while (true) {
                val line = r.readLine()
                if (line == null) {
                    // EOF => the shell died. Mark it dead so the next call respawns.
                    Log.w(TAG, "su shell closed unexpectedly")
                    process = null
                    break
                }
                if (line.trim() == marker) break
                sb.append(line).append('\n')
            }
            sb.toString()
        } catch (e: Exception) {
            Log.e(TAG, "exec failed: ${e.message}")
            process = null
            ""
        }
    }

    private companion object {
        const val TAG = "FrameX_RootShell"
    }
}
