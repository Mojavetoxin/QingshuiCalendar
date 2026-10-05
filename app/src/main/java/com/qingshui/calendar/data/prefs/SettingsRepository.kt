package com.qingshui.calendar.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.qingshui.calendar.domain.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "qingshui_settings")

/** DataStore 设置仓库：所有设置项的读写都走这里 */
class SettingsRepository(private val context: Context) {

    private object K {
        val weekStartMonday = booleanPreferencesKey("week_start_monday")
        val showLunar = booleanPreferencesKey("show_lunar")
        val showSolarTerm = booleanPreferencesKey("show_solar_term")
        val showHoliday = booleanPreferencesKey("show_holiday")
        val showWeekNumber = booleanPreferencesKey("show_week_number")
        val themeMode = intPreferencesKey("theme_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val defaultReminderMinutes = intPreferencesKey("default_reminder_minutes")
        val docImportEnabled = booleanPreferencesKey("doc_import_enabled")
        val docUri = stringPreferencesKey("doc_uri")
        val docName = stringPreferencesKey("doc_name")
        val docHour = intPreferencesKey("doc_hour")
        val docMinute = intPreferencesKey("doc_minute")
        val notifPermissionAsked = booleanPreferencesKey("notif_permission_asked")
        val soundEnabled = booleanPreferencesKey("sound_enabled")
        val hapticEnabled = booleanPreferencesKey("haptic_enabled")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            weekStartMonday = p[K.weekStartMonday] ?: true,
            showLunar = p[K.showLunar] ?: true,
            showSolarTerm = p[K.showSolarTerm] ?: true,
            showHoliday = p[K.showHoliday] ?: true,
            showWeekNumber = p[K.showWeekNumber] ?: false,
            themeMode = p[K.themeMode] ?: 0,
            dynamicColor = p[K.dynamicColor] ?: true,
            defaultReminderMinutes = p[K.defaultReminderMinutes] ?: 15,
            docImportEnabled = p[K.docImportEnabled] ?: false,
            docUri = p[K.docUri] ?: "",
            docName = p[K.docName] ?: "",
            docHour = p[K.docHour] ?: 7,
            docMinute = p[K.docMinute] ?: 30,
            notifPermissionAsked = p[K.notifPermissionAsked] ?: false,
            soundEnabled = p[K.soundEnabled] ?: true,
            hapticEnabled = p[K.hapticEnabled] ?: true
        )
    }

    suspend fun setWeekStartMonday(v: Boolean) = context.dataStore.edit { it[K.weekStartMonday] = v }
    suspend fun setShowLunar(v: Boolean) = context.dataStore.edit { it[K.showLunar] = v }
    suspend fun setShowSolarTerm(v: Boolean) = context.dataStore.edit { it[K.showSolarTerm] = v }
    suspend fun setShowHoliday(v: Boolean) = context.dataStore.edit { it[K.showHoliday] = v }
    suspend fun setShowWeekNumber(v: Boolean) = context.dataStore.edit { it[K.showWeekNumber] = v }
    suspend fun setThemeMode(v: Int) = context.dataStore.edit { it[K.themeMode] = v }
    suspend fun setDynamicColor(v: Boolean) = context.dataStore.edit { it[K.dynamicColor] = v }
    suspend fun setDefaultReminderMinutes(v: Int) = context.dataStore.edit { it[K.defaultReminderMinutes] = v }
    suspend fun setNotifPermissionAsked() = context.dataStore.edit { it[K.notifPermissionAsked] = true }

    suspend fun setSoundEnabled(v: Boolean) = context.dataStore.edit { it[K.soundEnabled] = v }

    suspend fun setHapticEnabled(v: Boolean) = context.dataStore.edit { it[K.hapticEnabled] = v }

    /** 文档导入相关设置一次写入 */
    suspend fun setDocImport(enabled: Boolean, uri: String, name: String) =
        context.dataStore.edit {
            it[K.docImportEnabled] = enabled
            it[K.docUri] = uri
            it[K.docName] = name
        }

    suspend fun setDocImportEnabled(enabled: Boolean) =
        context.dataStore.edit { it[K.docImportEnabled] = enabled }

    suspend fun setDocTime(hour: Int, minute: Int) =
        context.dataStore.edit { it[K.docHour] = hour; it[K.docMinute] = minute }
}
