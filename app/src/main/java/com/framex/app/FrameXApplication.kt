package com.framex.app

import android.app.Application
import com.framex.app.core.root.RootManager
import com.framex.app.gaming.GamingModeEngine
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class FrameXApplication : Application() {

    @Inject
    lateinit var rootManager: RootManager

    @Inject
    lateinit var gamingModeEngine: GamingModeEngine

    override fun onCreate() {
        super.onCreate()
        // Probe root once at startup. On Magisk the grant is remembered after the first prompt.
        rootManager.init()
        // Recover gaming mode state if the process was killed while mode was active.
        gamingModeEngine.recoverPersistedState()
    }
}
