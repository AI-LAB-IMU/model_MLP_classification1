package com.example.classification_0623.geo

data class GeoEvent(
    val deviceId: String,
    val timestampIso: String,
    val latitude: Double,
    val longitude: Double
)
