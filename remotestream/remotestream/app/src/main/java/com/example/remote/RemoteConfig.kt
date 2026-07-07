package com.example.remote

data class RemoteConfig(
    val relayServerUrl: String = "ws://192.168.1.100:8080",
    val videoWidth: Int = 720,
    val videoHeight: Int = 1280,
    val videoDpi: Int = 320,
    val bitRate: Int = 2000000,
    val frameRate: Int = 30,
    val iFrameInterval: Int = 1
)
