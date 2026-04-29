package com.framex.app.gaming

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.framex.app.repository.SettingsRepository
import com.framex.app.shizuku.ShizukuManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// ---------------------------------------------------------------------------
// State model
// ---------------------------------------------------------------------------

sealed class GamingModeState {
    object Idle : GamingModeState()
    data class Enabling(val progress: Float = 0f, val statusText: String = "Preparing…") : GamingModeState()
    object Active : GamingModeState()
    object Disabling : GamingModeState()
    data class Error(val message: String) : GamingModeState()
}

data class AppInfo(
    val packageName: String,
    val label: String
)

// ---------------------------------------------------------------------------
// Engine
// ---------------------------------------------------------------------------

@Singleton
class GamingModeEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizukuManager: ShizukuManager,
    private val settingsRepository: SettingsRepository
) {

    // ---- Public state -------------------------------------------------------

    private val _state = MutableStateFlow<GamingModeState>(GamingModeState.Idle)
    val state: StateFlow<GamingModeState> = _state.asStateFlow()

    // Companion-level flag so GamingNotificationListener can read it without DI.
    companion object {
        private val _isActive = MutableStateFlow(false)
        val isActive: StateFlow<Boolean> = _isActive.asStateFlow()
    }

    // ---- Package hit-lists (extracted from Vivo T3 Ultra dump) -----------

    val SAFE_TO_SUSPEND = listOf(
        // App Stores & Updaters
        "com.vivo.appstore",
        "com.bbk.updater",
        "com.vivo.website",
        "com.vivo.cardstore",

        // UI Bloat & Background Polling
        "com.vivo.assistant",
        "com.vivo.hiboard",            // Jovi / Minus-one screen
        "com.vivo.globalsearch",
        "com.vivo.magazine",           // Lockscreen magazine
        "com.bbk.theme",               // Theme store background sync
        "com.vivo.theme.effect",
        "com.vivo.video.floating",

        // Widgets & Syncers
        "com.vivo.weather",
        "com.vivo.weather.provider",
        "com.vivo.healthwidget",
        "com.vivo.stepcount",
        "com.vivo.exhealth",
        "com.bbk.cloud",               // Vivo Cloud sync

        // Secondary Vivo Services
        "com.vivo.imanager",           // Vivo cleaner (FrameX replaces it)
        "com.vivo.safecenter",         // Vivo security
        "com.vivo.xspace",
        "com.vivo.doubleinstance",     // App clone daemon
        "com.vivo.musicwidgetmix",
        "com.vivo.smartshot",
        "com.vivo.nps"                 // Net Promoter Score / Analytics
    )

    val SYSTEM_CRITICAL = listOf(
        // Core Daemons — suspending these causes soft-reboot on OriginOS
        "com.vivo.pem",                // Power Event Manager — restarts force-stopped apps
        "com.vivo.abe",                // App Behavior Engine
        "com.vivo.daemonService",      // Hardware daemon
        "com.vivo.sps",                // System Power Service
        "com.vivo.pie",                // Framework extension

        // Hardware & UI Modules
        "com.vivo.fingerprintui",
        "com.vivo.fingerprint",
        "com.vivo.fingerprintvit",
        "com.vivo.faceui",
        "com.vivo.faceunlock",
        "com.vivo.systemuiplugin",
        "com.vivo.networkstate",
        "com.vivo.connbase",
        "com.android.systemui",
        "com.android.phone",
        "com.mediatek.ims"             // VoLTE — kills calls if suspended
    )

    val GAMING_DAEMONS = listOf(
        "com.vivo.gamecube",
        "com.vivo.gamewatch",
        "com.vivo.game",
        "com.iqoo.powersaving",        // Prevents thermal throttling
        "com.microsoft.deviceintegrationservice"  // ThermalInfoService bridge
    )

    // Always protected — losing Shizuku = losing the ADB bridge.
    private val HARD_WHITELIST = setOf(
        "moe.shizuku.privileged.api",  // Shizuku itself
        context.packageName,           // FrameX itself
        "com.adguard.android",
        "com.adguard.vpn"
    )

    // ---- Public API ---------------------------------------------------------

    /**
     * Enumerate all installed non-system user apps that are candidates for
     * the AppOps / force-stop treatment.  Returns them sorted by label.
     */
    fun getInstalledUserApps(): List<AppInfo> {
        val pm = context.packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { ai ->
                // Keep only user-installed apps (no FLAG_SYSTEM)
                (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                    ai.packageName !in SYSTEM_CRITICAL &&
                    ai.packageName !in GAMING_DAEMONS &&
                    ai.packageName !in HARD_WHITELIST
            }
            .map { ai ->
                AppInfo(
                    packageName = ai.packageName,
                    label = pm.getApplicationLabel(ai).toString()
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Full Gaming Mode activation sequence.
     *
     * 1. pm suspend --user 0 on SAFE_TO_SUSPEND
     * 2. AppOps ignore + am force-stop on non-whitelisted user apps
     * 3. am kill-all
     * 4. Enable DND (if policy access is granted)
     */
    suspend fun enableGamingMode(userWhitelist: Set<String>) {
        if (!shizukuManager.isShizukuAvailable.value || !shizukuManager.hasPermission.value) {
            _state.value = GamingModeState.Error("Shizuku not available or permission not granted")
            return
        }

        _state.value = GamingModeState.Enabling(0f, "Initializing…")

        try {
            // ----------------------------------------------------------------
            // Phase 1 — Suspend OEM bloatware (pm suspend is immune to PEM)
            // ----------------------------------------------------------------
            val totalPhases = SAFE_TO_SUSPEND.size + 10  // rough estimate for progress
            SAFE_TO_SUSPEND.forEachIndexed { idx, pkg ->
                _state.value = GamingModeState.Enabling(
                    progress = idx.toFloat() / totalPhases,
                    statusText = "Suspending ${pkg.substringAfterLast('.')}"
                )
                shizukuManager.executeCommand("pm suspend --user 0 $pkg")
            }

            // ----------------------------------------------------------------
            // Phase 2 — User app AppOps + force-stop
            // ----------------------------------------------------------------
            val affectedPkgs = mutableSetOf<String>()
            val userApps = withContext(Dispatchers.IO) { getInstalledUserApps() }
                .filter { it.packageName !in userWhitelist }

            userApps.forEachIndexed { idx, app ->
                _state.value = GamingModeState.Enabling(
                    progress = (SAFE_TO_SUSPEND.size + idx).toFloat() / (SAFE_TO_SUSPEND.size + userApps.size + 2),
                    statusText = "Restricting ${app.label}"
                )
                shizukuManager.executeCommand("cmd appops set ${app.packageName} RUN_IN_BACKGROUND ignore")
                shizukuManager.executeCommand("cmd appops set ${app.packageName} RUN_ANY_IN_BACKGROUND ignore")
                shizukuManager.executeCommand("am force-stop ${app.packageName}")
                affectedPkgs.add(app.packageName)
            }

            // Persist the affected list so disableGamingMode restores only what we changed.
            settingsRepository.setGamingAffectedPackages(affectedPkgs)

            // ----------------------------------------------------------------
            // Phase 3 — Kill cached background processes
            // ----------------------------------------------------------------
            _state.value = GamingModeState.Enabling(0.96f, "Purging background cache…")
            shizukuManager.executeCommand("am kill-all")

            // ----------------------------------------------------------------
            // Phase 4 — Enable DND via NotificationManager policy
            // ----------------------------------------------------------------
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            }

            // ----------------------------------------------------------------
            // Done
            // ----------------------------------------------------------------
            settingsRepository.setGamingModeActive(true)
            _isActive.value = true
            _state.value = GamingModeState.Active

        } catch (e: Exception) {
            _state.value = GamingModeState.Error(e.message ?: "Unexpected error during activation")
            settingsRepository.setGamingModeActive(false)
            _isActive.value = false
        }
    }

    /**
     * Full Gaming Mode deactivation sequence.
     *
     * 1. pm unsuspend on SAFE_TO_SUSPEND
     * 2. Restore AppOps allow on previously-affected packages
     * 3. Disable DND
     */
    suspend fun disableGamingMode() {
        _state.value = GamingModeState.Disabling

        try {
            // Unsuspend all OEM packages
            SAFE_TO_SUSPEND.forEach { pkg ->
                shizukuManager.executeCommand("pm unsuspend --user 0 $pkg")
            }

            // Restore AppOps only for packages we actually changed
            val affected = settingsRepository.getGamingAffectedPackages()
            affected.forEach { pkg ->
                shizukuManager.executeCommand("cmd appops set $pkg RUN_IN_BACKGROUND allow")
                shizukuManager.executeCommand("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow")
            }
            settingsRepository.setGamingAffectedPackages(emptySet())

            // Restore DND
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }

        } catch (e: Exception) {
            // Always transition to Idle even on partial failure — leaving the mode in
            // a broken "Active" state is worse than a failed restore.
        }

        settingsRepository.setGamingModeActive(false)
        _isActive.value = false
        _state.value = GamingModeState.Idle
    }

    /** Called on app start-up to recover state that was active before a kill. */
    fun recoverPersistedState() {
        if (settingsRepository.isGamingModeActive()) {
            // The FGS may have been killed but the device is still in the modified state.
            // Mark as active so the UI reflects reality; the user can deactivate normally.
            _isActive.value = true
            _state.value = GamingModeState.Active
        }
    }
}
