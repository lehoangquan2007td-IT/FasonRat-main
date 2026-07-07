package com.example

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    companion object {
        val SERVER_URL_KEY = stringPreferencesKey("server_url")
        val IS_TRACKING_KEY = booleanPreferencesKey("is_tracking")
    }

    val serverUrl: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[SERVER_URL_KEY] ?: "https://example.com/api/location"
        }

    val isTracking: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[IS_TRACKING_KEY] ?: false
        }

    suspend fun saveServerUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[SERVER_URL_KEY] = url
        }
    }

    suspend fun setTracking(isTracking: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[IS_TRACKING_KEY] = isTracking
        }
    }
}
