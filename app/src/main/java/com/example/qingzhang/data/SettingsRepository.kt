package com.example.qingzhang.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val dailyLimitCents: Long = 0,
    val dailyLimitEnabled: Boolean = false,
    val updateManifestUrl: String = "",
    val autoCheckUpdates: Boolean = true,
    val darkTheme: Boolean? = null,
)

class SettingsRepository(
    private val context: Context,
    private val defaultUpdateManifestUrl: String = "",
) {
    private val limitKey = longPreferencesKey("daily_limit_cents")
    private val enabledKey = booleanPreferencesKey("daily_limit_enabled")
    private val updateUrlKey = stringPreferencesKey("update_manifest_url")
    private val autoCheckKey = booleanPreferencesKey("auto_check_updates")
    private val darkThemeKey = booleanPreferencesKey("dark_theme")

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { preferences ->
        AppSettings(
            dailyLimitCents = preferences[limitKey] ?: 0,
            dailyLimitEnabled = preferences[enabledKey] ?: false,
            updateManifestUrl = preferences[updateUrlKey] ?: defaultUpdateManifestUrl,
            autoCheckUpdates = preferences[autoCheckKey] ?: true,
            darkTheme = preferences[darkThemeKey],
        )
    }

    suspend fun update(limitCents: Long, enabled: Boolean) {
        require(limitCents >= 0) { "每日限额不能为负数" }
        require(!enabled || limitCents > 0) { "启用限额时金额必须大于 0" }
        context.settingsDataStore.edit { preferences ->
            preferences[limitKey] = limitCents
            preferences[enabledKey] = enabled
        }
    }

    suspend fun updateUpdateSettings(manifestUrl: String, autoCheck: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[updateUrlKey] = manifestUrl.trim()
            preferences[autoCheckKey] = autoCheck
        }
    }

    suspend fun updateTheme(darkTheme: Boolean) {
        context.settingsDataStore.edit { preferences -> preferences[darkThemeKey] = darkTheme }
    }
}
