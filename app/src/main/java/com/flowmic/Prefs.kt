package com.flowmic

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "flowmic")

object PrefsKeys {
    val OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")
    val MODE = stringPreferencesKey("mode") // google | whisper_api
    val LANGUAGE = stringPreferencesKey("language") // fr | en | auto
    val API_KEY = stringPreferencesKey("api_key")
    val MIC_SIZE = intPreferencesKey("mic_size") // dp
    val MIC_X = intPreferencesKey("mic_x")
    val MIC_Y = intPreferencesKey("mic_y")
    val AUTO_SPACE = booleanPreferencesKey("auto_space")
    val HISTORY = stringSetPreferencesKey("history")
}

data class AppPrefs(
    val overlayEnabled: Boolean = false,
    val mode: String = "google",
    val language: String = "fr",
    val apiKey: String = "",
    val micSize: Int = 64,
    val micX: Int = -1,
    val micY: Int = -1,
    val autoSpace: Boolean = true,
    val history: Set<String> = emptySet(),
)

fun Context.prefsFlow() = dataStore.data.map { p ->
    AppPrefs(
        overlayEnabled = p[PrefsKeys.OVERLAY_ENABLED] ?: false,
        mode = p[PrefsKeys.MODE] ?: "google",
        language = p[PrefsKeys.LANGUAGE] ?: "fr",
        apiKey = p[PrefsKeys.API_KEY] ?: "",
        micSize = p[PrefsKeys.MIC_SIZE] ?: 64,
        micX = p[PrefsKeys.MIC_X] ?: -1,
        micY = p[PrefsKeys.MIC_Y] ?: -1,
        autoSpace = p[PrefsKeys.AUTO_SPACE] ?: true,
        history = p[PrefsKeys.HISTORY] ?: emptySet(),
    )
}

suspend fun Context.pushHistory(text: String) {
    dataStore.edit { prefs ->
        val updated = (listOf(text) + (prefs[PrefsKeys.HISTORY] ?: emptySet()).toList())
            .distinct().take(20).toSet()
        prefs[PrefsKeys.HISTORY] = updated
    }
}
