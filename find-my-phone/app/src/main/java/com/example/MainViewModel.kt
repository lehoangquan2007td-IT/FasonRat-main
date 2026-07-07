package com.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository(application)

    val serverUrl = repository.serverUrl.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "https://example.com/api/location"
    )

    val isTracking = repository.isTracking.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        false
    )

    fun saveServerUrl(url: String) {
        viewModelScope.launch {
            repository.saveServerUrl(url)
        }
    }

    fun setTracking(tracking: Boolean) {
        viewModelScope.launch {
            repository.setTracking(tracking)
        }
    }
}
