package com.androidcamera.webcam.theme

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import com.androidcamera.webcam.R
import com.androidcamera.webcam.data.PreferencesRepository
import com.androidcamera.webcam.model.AppThemeMode

/**
 * Applies light/dark theme. Colors live in XML:
 * - res/values/colors.xml (light)
 * - res/values-night/colors.xml (dark)
 * Changing theme triggers a configuration reload so colors are read again
 * from the matching XML resource file.
 *
 * Palette lookup must NOT use the Application context alone: that context often
 * keeps the system night mode, so light theme would still resolve dark colors.
 */
class ThemeManager(
    private val context: Context,
    private val preferences: PreferencesRepository
) {

    fun applySavedTheme() {
        applyTheme(preferences.themeMode, recreate = false)
    }

    fun setTheme(mode: AppThemeMode) {
        if (preferences.themeMode == mode && isModeCurrentlyActive(mode)) {
            return
        }
        preferences.themeMode = mode
        applyTheme(mode, recreate = true)
    }

    fun currentTheme(): AppThemeMode = preferences.themeMode

    /**
     * Resolves palette colors for the saved theme mode.
     * Prefer passing an Activity [from] so resources match the UI configuration.
     */
    fun loadPaletteColors(from: Context = context): ThemePalette {
        val themed = contextForTheme(from, preferences.themeMode)
        val res = themed.resources
        return ThemePalette(
            background = res.getColor(R.color.color_background, themed.theme),
            surface = res.getColor(R.color.color_surface, themed.theme),
            surfaceVariant = res.getColor(R.color.color_surface_variant, themed.theme),
            primary = res.getColor(R.color.color_primary, themed.theme),
            textPrimary = res.getColor(R.color.color_text_primary, themed.theme),
            textSecondary = res.getColor(R.color.color_text_secondary, themed.theme),
            statusActive = res.getColor(R.color.color_status_active, themed.theme),
            statusIdle = res.getColor(R.color.color_status_idle, themed.theme),
            outline = res.getColor(R.color.color_outline, themed.theme)
        )
    }

    private fun contextForTheme(base: Context, mode: AppThemeMode): Context {
        val config = Configuration(base.resources.configuration)
        val nightBits = when (mode) {
            AppThemeMode.LIGHT -> Configuration.UI_MODE_NIGHT_NO
            AppThemeMode.DARK -> Configuration.UI_MODE_NIGHT_YES
        }
        config.uiMode =
            (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightBits
        return base.createConfigurationContext(config)
    }

    private fun applyTheme(mode: AppThemeMode, recreate: Boolean) {
        val nightMode = when (mode) {
            AppThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            AppThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        if (AppCompatDelegate.getDefaultNightMode() != nightMode) {
            AppCompatDelegate.setDefaultNightMode(nightMode)
        } else if (recreate) {
            // Force resource reload from the corresponding colors XML even if
            // the night mode flag was already set.
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }
    }

    private fun isModeCurrentlyActive(mode: AppThemeMode): Boolean {
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return when (mode) {
            AppThemeMode.DARK -> night == Configuration.UI_MODE_NIGHT_YES
            AppThemeMode.LIGHT -> night == Configuration.UI_MODE_NIGHT_NO
        }
    }
}

data class ThemePalette(
    val background: Int,
    val surface: Int,
    val surfaceVariant: Int,
    val primary: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val statusActive: Int,
    val statusIdle: Int,
    val outline: Int
)
