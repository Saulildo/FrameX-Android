package com.framex.app.hud

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import com.framex.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Floating Performance HUD — a 1:1 replica of Apple's Metal Performance HUD rendered as a
 * transparent, draggable system overlay.
 *
 * Responsibilities:
 *  - Hosts a transparent [WebView] (loading `assets/hud.html`) in a `TYPE_APPLICATION_OVERLAY`
 *    window so it floats above games/apps.
 *  - Runs as a foreground service (CPU/process kept alive while the HUD is shown).
 *  - Spins up a persistent root shell ([RootShell]) and a [HudTelemetryCollector], then pushes
 *    fresh telemetry into the WebView by calling the page's `updateHudData(...)` every 500ms.
 *
 * Start it with [start] (it checks the overlay permission) and stop with [stop].
 */
class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private var webView: WebView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var telemetryJob: Job? = null

    private val rootShell = RootShell()
    private val collector = HudTelemetryCollector(rootShell)

    /** Set true once hud.html finishes loading; gates the first JS push. */
    @Volatile private var pageReady = false

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        createNotificationChannel()
        startAsForeground()
        acquireWakeLock()

        addOverlay()
        startTelemetryLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Re-create itself if the OS / OEM game-boost kills the process.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        _isRunning.value = false
        telemetryJob?.cancel()
        serviceScope.cancel()
        rootShell.close()
        removeOverlay()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // --- overlay window -----------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun addOverlay() {
        if (webView != null) return

        val wv = WebView(this).apply {
            // Transparent so the rounded-rect CSS background composites over whatever is behind.
            setBackgroundColor(Color.TRANSPARENT)
            settings.apply {
                javaScriptEnabled = true       // required to call updateHudData(...)
                allowFileAccess = true         // load file:///android_asset/hud.html
                domStorageEnabled = false
                // No network is used; everything is local. Keep cache off.
                cacheMode = WebView.LOAD_NO_CACHE
            }
            // The HUD is display-only: don't let the page try to take focus or show scrollbars.
            isFocusable = false
            isFocusableInTouchMode = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    pageReady = true
                }
            }
            loadUrl("file:///android_asset/hud.html")
        }
        webView = wv

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            // Explicit 8-bit alpha — more reliable than TRANSLUCENT on MediaTek/vivo ROMs.
            PixelFormat.RGBA_8888,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val prefs = getSharedPreferences("framex_settings", Context.MODE_PRIVATE)
            x = prefs.getInt(KEY_HUD_X, 40)
            y = prefs.getInt(KEY_HUD_Y, 120)
        }

        attachDragHandler(wv)

        try {
            windowManager.addView(wv, layoutParams)
            Log.d(TAG, "HUD overlay added to WindowManager.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add HUD overlay: ${e.message}", e)
        }
    }

    /**
     * Makes the whole HUD draggable. The HUD is non-interactive, so we consume every touch to
     * move the window. A small movement threshold distinguishes a drag from an incidental tap.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragHandler(view: View) {
        var initialX = 0
        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var dragging = false

        view.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchStartX)
                    val dy = (event.rawY - touchStartY)
                    if (!dragging && (abs(dx) > TOUCH_SLOP || abs(dy) > TOUCH_SLOP)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        webView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) persistPosition(params.x, params.y)
                    true
                }
                else -> false
            }
        }
    }

    private fun persistPosition(x: Int, y: Int) {
        getSharedPreferences("framex_settings", Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_HUD_X, x)
            .putInt(KEY_HUD_Y, y)
            .apply()
    }

    private fun removeOverlay() {
        webView?.let { wv ->
            runCatching { windowManager.removeView(wv) }
            wv.destroy()
        }
        webView = null
        layoutParams = null
        pageReady = false
    }

    // --- telemetry loop + JS bridge ----------------------------------------

    /**
     * The bridge. Opens the root shell once, then samples telemetry and pushes it into the
     * WebView every [POLL_INTERVAL_MS]. Sampling runs on IO; the `evaluateJavascript` call is
     * marshalled back onto the WebView's (main) thread via [WebView.post].
     */
    private fun startTelemetryLoop() {
        telemetryJob = serviceScope.launch(Dispatchers.IO) {
            val rooted = rootShell.init()
            if (!rooted) {
                Log.w(TAG, "Root not available — HUD will keep showing placeholder values.")
            }
            while (isActive) {
                val telemetry = runCatching { collector.sample() }.getOrNull()
                if (telemetry != null && pageReady) {
                    pushToWebView(telemetry)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun pushToWebView(t: HudTelemetry) {
        // Build the exact JS call the page exposes:
        //   updateHudData(device, res, hz, fps, pre, gpu, mem, maxMem)
        val device = jsString(t.device)
        val res = jsString(t.res)
        val js = "updateHudData($device, $res, ${t.hz}, " +
            "${fmt(t.fps)}, ${fmt(t.preMs)}, ${fmt(t.gpuMs)}, " +
            "${fmt(t.memUsedGb)}, ${fmt(t.memMaxMb)});"

        webView?.post {
            runCatching { webView?.evaluateJavascript(js, null) }
        }
    }

    /** Safely render a Double for embedding in JS (no locale commas, finite only). */
    private fun fmt(v: Double): String =
        if (v.isFinite()) String.format(java.util.Locale.US, "%.2f", v) else "0"

    /** Quote + escape a string for a JS argument. */
    private fun jsString(s: String): String {
        val escaped = s
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", " ")
            .replace("\r", " ")
        return "'$escaped'"
    }

    // --- foreground service plumbing ---------------------------------------

    private fun startAsForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FrameX::HudWakeLock")
            .also { runCatching { it.acquire() } }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FrameX Performance HUD",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Performance HUD Active")
            .setContentText("Streaming real-time GPU/CPU telemetry")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()

    companion object {
        private const val TAG = "FrameX_HUD"

        const val CHANNEL_ID = "framex_hud_channel"
        const val NOTIFICATION_ID = 7 // overlay=1, gaming=2 are taken
        const val ACTION_STOP = "com.framex.app.ACTION_STOP_HUD"

        private const val POLL_INTERVAL_MS = 500L
        private const val TOUCH_SLOP = 8f

        private const val KEY_HUD_X = "hud_x"
        private const val KEY_HUD_Y = "hud_y"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        /** True if the user has granted the "Display over other apps" permission. */
        fun canDrawOverlay(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

        /**
         * Starts the HUD if the overlay permission is granted.
         * @return false if the overlay permission is missing (caller should route the user to
         *         [Settings.ACTION_MANAGE_OVERLAY_PERMISSION]).
         */
        fun start(context: Context): Boolean {
            if (!canDrawOverlay(context)) return false
            val intent = Intent(context, FloatingWindowService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            return true
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, FloatingWindowService::class.java).apply { action = ACTION_STOP },
            )
        }
    }
}
