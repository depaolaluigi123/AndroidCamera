package com.androidcamera.webcam

import android.app.Application
import android.content.Context
import com.androidcamera.webcam.data.PreferencesRepository
import com.androidcamera.webcam.data.WebcamSettingsStore
import com.androidcamera.webcam.locale.LocaleManager
import com.androidcamera.webcam.theme.ThemeManager

class AndroidCameraApp : Application() {

    lateinit var preferences: PreferencesRepository
        private set
    lateinit var themeManager: ThemeManager
        private set
    lateinit var localeManager: LocaleManager
        private set
    /** Single source of truth for the live webcam settings shared by the
     *  MainActivity and the fullscreen preview overlay. Both UIs read
     *  from / write to this store instead of duplicating state on the
     *  Activity. The initial snapshot comes from [PreferencesRepository]
     *  so the values the user persisted across the previous run are
     *  still visible after a cold start. */
    lateinit var webcamSettings: WebcamSettingsStore
        private set

    override fun attachBaseContext(base: Context) {
        val prefs = PreferencesRepository(base)
        val locale = LocaleManager(prefs)
        super.attachBaseContext(locale.wrapWithSavedLocale(base))
    }

    override fun onCreate() {
        super.onCreate()
        preferences = PreferencesRepository(this)
        localeManager = LocaleManager(preferences)
        themeManager = ThemeManager(this, preferences)
        themeManager.applySavedTheme()
        // Seed the in-memory store with the persisted values so any UI
        // surface that reads it before the Activity has had a chance to
        // call ``update`` already sees the user's saved settings.
        webcamSettings = WebcamSettingsStore(preferences.streamConfig())
    }
}
