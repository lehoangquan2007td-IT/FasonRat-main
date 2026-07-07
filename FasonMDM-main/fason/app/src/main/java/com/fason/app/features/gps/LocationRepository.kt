package com.fason.app.features.gps

import android.content.Context
import kotlinx.coroutines.flow.first
import com.fason.app.core.network.SocketClient
import com.fason.app.core.Protocol
import org.json.JSONObject

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
        val locations = locationDao.getAllLocationsSync()
        if (locations.isEmpty()) return true

        val socketClient = SocketClient.getInstance()
        if (!socketClient.isConnected) return false

        val socket = socketClient.socket ?: return false

        var allSynced = true
        val syncedIds = mutableListOf<Int>()

        for (location in locations) {
            try {
                val json = JSONObject()
                json.put("latitude", location.lat)
                json.put("longitude", location.lng)
                json.put("timestamp", location.timestamp)
                json.put("deviceId", location.deviceId)
                
                socket.emit(Protocol.LOCATION, json)
                syncedIds.add(location.id)
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
