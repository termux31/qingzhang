package com.example.qingzhang.notifications

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.reminderStore by preferencesDataStore(name = "reminders")

internal class ReminderPreferences(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.reminderStore)

    private val minuteKey = intPreferencesKey("daily_minute")
    private val historyKey = stringSetPreferencesKey("history")
    private fun enabledAtKey(kind: NoticeKind) = longPreferencesKey("enabled_${kind.name}")

    val settings = store.data.map { preferences ->
        ReminderSettings(
            enabledSince = NoticeKind.entries.mapNotNull { kind ->
                preferences[enabledAtKey(kind)]?.let { kind to it }
            }.toMap(),
            dailyMinute = preferences[minuteKey] ?: 21 * 60,
        )
    }.distinctUntilChanged()

    suspend fun setEnabled(kind: NoticeKind, enabled: Boolean) {
        store.edit { preferences ->
            val key = enabledAtKey(kind)
            if (!enabled) preferences.remove(key)
            else if (preferences[key] == null) preferences[key] = System.currentTimeMillis()
        }
    }

    suspend fun setDailyMinute(minute: Int) {
        require(minute in 0 until 24 * 60)
        store.edit { preferences ->
            if ((preferences[minuteKey] ?: 21 * 60) == minute) return@edit
            preferences[minuteKey] = minute
            val key = enabledAtKey(NoticeKind.DAILY)
            if (preferences[key] != null) preferences[key] = System.currentTimeMillis()
        }
    }

    suspend fun claim(kind: NoticeKind, id: Long): Boolean {
        val oldKey = "${kind.name}:$id"
        // Earlier builds recorded these two reminders even when delivery was disabled.
        val key = if (kind == NoticeKind.GOAL || kind == NoticeKind.OVER_BUDGET) "v2:$oldKey" else oldKey
        var claimed = false
        store.edit { preferences ->
            if (preferences[enabledAtKey(kind)] == null) return@edit
            val history = preferences[historyKey].orEmpty()
            if (key !in history) {
                preferences[historyKey] = history - oldKey + key
                claimed = true
            }
        }
        return claimed
    }
}
