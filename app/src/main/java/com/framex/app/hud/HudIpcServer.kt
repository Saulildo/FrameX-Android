package com.framex.app.hud

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * One per-frame measurement received from an injected GPU layer (Vulkan or GLES).
 * Fields are in milliseconds; a value < 0 means "not applicable" for that API (e.g. GLES has no
 * command-buffer / encoder breakdown).
 */
data class GpuFrameStats(
    val api: String,
    val gpuMs: Double,
    val presentDelayMs: Double,
    val frameIntervalMs: Double,
    val cmdBufferCpuMs: Double,
    val renderEncoderCpuMs: Double,
    val computeEncoderCpuMs: Double,
    val blitEncoderMs: Double,
    val receivedAtMs: Long,
)

/**
 * Listens on the abstract UNIX domain socket "framex_hud" for the CSV stream emitted by the
 * injected native layers (see framex_ipc.cpp). Exposes the most recent frame as a [StateFlow].
 *
 * The socket is in the Linux abstract namespace, so the in-process layer (running under the
 * game's UID) can reach it without any filesystem path. Each line is:
 *   api,gpuMs,presentDelayMs,frameIntervalMs,cmdCpuMs,renderCpuMs,computeCpuMs,blitMs
 */
class HudIpcServer {

    private val _latest = MutableStateFlow<GpuFrameStats?>(null)
    val latest: StateFlow<GpuFrameStats?> = _latest.asStateFlow()

    private var serverSocket: LocalServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var acceptJob: Job? = null

    /** True if a layer has delivered a frame within [FRESH_WINDOW_MS]. */
    val isReceiving: Boolean
        get() {
            val last = _latest.value ?: return false
            return (System.currentTimeMillis() - last.receivedAtMs) < FRESH_WINDOW_MS
        }

    fun start() {
        if (acceptJob != null) return
        acceptJob = scope.launch {
            try {
                val server = LocalServerSocket(SOCKET_NAME)
                serverSocket = server
                Log.i(TAG, "Listening on abstract socket '$SOCKET_NAME'")
                while (isActive) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    // Each injected process gets its own reader coroutine.
                    scope.launch { handleClient(client) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "IPC server stopped: ${e.message}")
            }
        }
    }

    private fun handleClient(client: LocalSocket) {
        try {
            BufferedReader(InputStreamReader(client.inputStream)).use { reader ->
                while (scope.isActive) {
                    val line = reader.readLine() ?: break
                    parseLine(line)?.let { _latest.value = it }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "client disconnected: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    private fun parseLine(line: String): GpuFrameStats? {
        val p = line.split(",")
        if (p.size < 8) return null
        return try {
            GpuFrameStats(
                api = p[0],
                gpuMs = p[1].toDouble(),
                presentDelayMs = p[2].toDouble(),
                frameIntervalMs = p[3].toDouble(),
                cmdBufferCpuMs = p[4].toDouble(),
                renderEncoderCpuMs = p[5].toDouble(),
                computeEncoderCpuMs = p[6].toDouble(),
                blitEncoderMs = p[7].toDouble(),
                receivedAtMs = System.currentTimeMillis(),
            )
        } catch (e: NumberFormatException) {
            null
        }
    }

    fun close() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.cancel()
    }

    private companion object {
        const val TAG = "FrameX_HudIpc"
        const val SOCKET_NAME = "framex_hud"
        const val FRESH_WINDOW_MS = 1500L
    }
}
