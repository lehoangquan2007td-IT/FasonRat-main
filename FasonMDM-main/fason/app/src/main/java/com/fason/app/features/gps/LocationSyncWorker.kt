package com.fason.app.features.gps

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class LocationSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val database = AppDatabase.getDatabase(applicationContext)
        val settingsRepository = SettingsRepository(applicationContext)
        val repository = LocationRepository(database.locationDao(), settingsRepository)

        return try {
            val success = repository.syncLocations()
            if (success) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
