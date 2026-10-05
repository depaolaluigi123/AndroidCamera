package com.androidcamera.webcam.locale

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import com.androidcamera.webcam.data.PreferencesRepository
import com.androidcamera.webcam.model.AppLanguage
import java.util.Locale

/**
 * Applies app language. Strings live in XML:
 * - res/values/strings.xml (English, default)
 * - res/values-it/strings.xml (Italian)
 * Changing language recreates the Activity so strings are reloaded from
 * the matching XML resource file.
 */
class LocaleManager(
    private val preferences: PreferencesRepository
) {

    fun currentLanguage(): AppLanguage = preferences.language

    fun setLanguage(context: Context, language: AppLanguage): Context {
        preferences.language = language
        return wrap(context, language)
    }

    fun wrapWithSavedLocale(context: Context): Context {
        return wrap(context, preferences.language)
    }

    private fun wrap(context: Context, language: AppLanguage): Context {
        val locale = Locale.forLanguageTag(language.code)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList(locale))
        return context.createConfigurationContext(config)
    }
}
