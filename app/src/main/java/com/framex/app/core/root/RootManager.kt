package com.framex.app.core.root

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide entry point for privileged commands. Wraps a single persistent [RootShell] so the HUD,
 * the metric monitors, and Gaming Mode all share one `su` session (one grant, one process).
 *
 * This replaces the previous Shizuku bridge: FrameX is now a pure-root app, so there is no AIDL
 * user-service, no binder, and no third-party dependency — just `su`.
 *
 * Public surface mirrors what callers need:
 *  - [isRootAvailable] — observable state for the UI / monitors to gate on.
 *  - [executeCommand]  — run a shell command as uid 0 (lazily acquires root if needed).
 *  - [refresh]         — (re)probe root, e.g. from a "Grant" button; triggers the su dialog.
 */
@Singleton
class RootManager @Inject constructor() {

    private val shell = RootShell()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isRootAvailable = MutableStateFlow(false)
    val isRootAvailable: StateFlow<Boolean> = _isRootAvailable.asStateFlow()

    /** True while the underlying `su` process is alive. */
    val isAlive: Boolean get() = shell.isAlive

    /** Fire-and-forget probe at app start. On Magisk the grant is remembered after the first time. */
    fun init() {
        scope.launch { refresh() }
    }

    /** (Re)establish and verify the root shell. Safe to call from the UI to (re)trigger the prompt. */
    suspend fun refresh(): Boolean {
        val ok = shell.init()
        _isRootAvailable.value = ok
        return ok
    }

    /**
     * Runs [command] as root and returns its combined stdout/stderr ("" if root is unavailable).
     * Lazily acquires root on first use so callers don't have to sequence [refresh] themselves.
     */
    suspend fun executeCommand(command: String): String {
        if (!_isRootAvailable.value && !refresh()) return ""
        val out = shell.exec(command)
        // Keep the observable flag honest if the shell died underneath us.
        if (!shell.isAlive) _isRootAvailable.value = false
        return out
    }

    fun destroy() {
        shell.close()
        _isRootAvailable.value = false
    }
}
