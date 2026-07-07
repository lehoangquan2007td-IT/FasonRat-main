package com.example

import android.content.Context
import kotlinx.coroutines.flow.first

class LocationRepository(
    private val locationDao: LocationDao,
    private val settingsRepository: SettingsRepository
) {
    suspend fun saveLocationLocally(lat: Double, lng: Double, timestamp: Long, deviceId: String) {
        val entity = LocationEntity(
            lat = lat,
            lng = lng,
            timestamp = timestamp,
            deviceId = deviceId
        )
        locationDao.insertLocation(entity)
    }

    suspend fun syncLocations(): Boolean {
        val serverUrl = settingsRepository.serverUrl.first()
        if (serverUrl.isBlank()) return false

        val locations = locationDao.getAllLocationsSync()
        if (locations.isEmpty()) return true

        var allSynced = true
        val syncedIds = mutableListOf<Int>()

        for (location in locations) {
            try {
                val payload = LocationPayload(
                    lat = location.lat,
                    lng = location.lng,
                    timestamp = location.timestamp,
                    deviceId = location.deviceId
                )
                val response = RetrofitClient.api.sendLocation(serverUrl, payload)
                if (response.isSuccessful) {
                    syncedIds.add(location.id)
                } else {
                    allSynced = false
                }
            } catch (e: Exception) {
                allSynced = false
            }
        }

        if (syncedIds.isNotEmpty()) {
            locationDao.deleteLocations(syncedIds)
        }

        return allSynced
    }
}
