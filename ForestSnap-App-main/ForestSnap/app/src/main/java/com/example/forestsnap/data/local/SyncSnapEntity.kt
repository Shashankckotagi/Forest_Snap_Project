package com.example.forestsnap.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_snaps")
data class SyncSnapEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val photoPath: String,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val isSynced: Boolean = false,
    val riskScore: Float = 0.0f,           // AI score 0.0–1.0 computed on-device
    val gpsAccuracy: Float = 10.0f,        // metres — lower is better
    val bearing: Float = 0f,               // compass bearing at moment of capture (0–360°)
    val zoneId: String = "",               // computed from GPS, stored after sync
    val zoneScore: Float = 0.0f,           // trust-weighted zone score from backend
    val zoneConfidence: Float = 0.0f,      // confidence % from backend
    val submissionStatus: String = "",     // "New Submission" or "Already Recorded" after sync
    val campsiteVerdict: String = ""       // campsite scan result: "NOT SAFE", "CAUTION", or "SAFE TO CAMP"
)