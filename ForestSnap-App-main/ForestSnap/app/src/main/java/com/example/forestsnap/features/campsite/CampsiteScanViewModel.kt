package com.example.forestsnap.features.campsite

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.forestsnap.data.local.ForestDatabase
import com.example.forestsnap.data.sync.FirebaseRepository
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// Bearing → Direction mapping per spec
fun bearingToDirection(bearing: Float): String = when {
    bearing >= 315f || bearing < 45f  -> "N"
    bearing >= 45f  && bearing < 135f -> "E"
    bearing >= 135f && bearing < 225f -> "S"
    else                              -> "W"
}

fun directionLabel(dir: String): String = when (dir) {
    "N" -> "NORTH"
    "E" -> "EAST"
    "S" -> "SOUTH"
    "W" -> "WEST"
    else -> dir
}

// Per-sector data — score and the Room snap ID for later verdict update
data class SectorEntry(val score: Float, val snapId: Int)

class CampsiteScanViewModel(application: Application) : AndroidViewModel(application) {

    private val db  = ForestDatabase.getDatabase(application)
    private val dao = db.syncSnapDao()
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)

    // --- Sector state ---
    // direction → SectorEntry (null = not yet captured)
    private val _sectorData = MutableStateFlow<Map<String, SectorEntry>>(emptyMap())

    val sectorScores: StateFlow<Map<String, Float?>> = _sectorData
        .map { data ->
            mapOf(
                "N" to data["N"]?.score,
                "E" to data["E"]?.score,
                "S" to data["S"]?.score,
                "W" to data["W"]?.score
            )
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            mapOf("N" to null, "E" to null, "S" to null, "W" to null)
        )

    val isComplete: StateFlow<Boolean> = _sectorData
        .map { it.size == 4 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val verdict: StateFlow<String> = _sectorData
        .map { data ->
            if (data.size < 4) return@map ""
            val worstScore = data.values.maxOf { it.score }
            when {
                worstScore >= 0.7f -> "NOT SAFE — Campfire BANNED"
                worstScore >= 0.4f -> "CAUTION — Campfire Discouraged"
                else               -> "SAFE TO CAMP — Campfire Permitted"
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // --- Zone tracking ---
    private val _currentZoneId = MutableStateFlow("")
    val currentZoneId: StateFlow<String> = _currentZoneId.asStateFlow()

    private val _currentLatLon = MutableStateFlow("Fetching GPS…")
    val currentLatLon: StateFlow<String> = _currentLatLon.asStateFlow()

    // --- Submission state ---
    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    private val _submitSuccess = MutableStateFlow(false)
    val submitSuccess: StateFlow<Boolean> = _submitSuccess.asStateFlow()

    init {
        fetchLocation()
    }

    @SuppressLint("MissingPermission")
    fun fetchLocation() {
        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            CancellationTokenSource().token
        ).addOnSuccessListener { location ->
            if (location != null) {
                val row = ((location.latitude  - 12.0) * 100).toInt()
                val col = ((location.longitude - 76.0) * 100).toInt()
                _currentZoneId.value = "${row}_${col}"
                _currentLatLon.value = "%.4f°N, %.4f°E".format(location.latitude, location.longitude)
            } else {
                _currentLatLon.value = "GPS unavailable"
            }
        }.addOnFailureListener {
            _currentLatLon.value = "GPS error"
        }
    }

    /**
     * Called after a photo is taken and scored.
     * Inserts a new snap into Room and assigns the score to the sector.
     * A sector already filled cannot be overwritten (per spec).
     */
    fun addSectorScore(direction: String, photoPath: String, score: Float) {
        if (_sectorData.value.containsKey(direction)) return  // cannot overwrite

        viewModelScope.launch {
            // Insert snap to Room so it appears in Sync Queue
            val snapId = dao.insertSnapReturningId(
                com.example.forestsnap.data.local.SyncSnapEntity(
                    photoPath   = photoPath,
                    latitude    = parseLatFromZone(_currentZoneId.value),
                    longitude   = parseLonFromZone(_currentZoneId.value),
                    timestamp   = System.currentTimeMillis(),
                    riskScore   = score,
                    bearing     = directionToBearing(direction)
                )
            )

            val newData = _sectorData.value + (direction to SectorEntry(score, snapId.toInt()))
            _sectorData.value = newData

            // When all 4 sectors done, compute verdict and persist to each snap
            if (newData.size == 4) {
                val worstScore = newData.values.maxOf { it.score }
                val verdictText = when {
                    worstScore >= 0.7f -> "NOT SAFE — Campfire BANNED"
                    worstScore >= 0.4f -> "CAUTION — Campfire Discouraged"
                    else               -> "SAFE TO CAMP — Campfire Permitted"
                }
                newData.values.forEach { entry ->
                    dao.updateCampsiteVerdict(entry.snapId, verdictText)
                }
            }
        }
    }

    /**
     * Submits the completed campsite scan to Firestore.
     */
    fun submitCampsiteScan() {
        val zoneId = _currentZoneId.value
        if (zoneId.isEmpty() || _sectorData.value.size < 4) return

        val scores   = _sectorData.value.mapValues { it.value.score }
        val verdictText = verdict.value

        viewModelScope.launch {
            _isSubmitting.value = true
            try {
                FirebaseRepository.submitCampsiteScan(zoneId, scores, verdictText)
                _submitSuccess.value = true
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isSubmitting.value = false
            }
        }
    }

    // Helper: parse lat back from zone ID for Room storage
    private fun parseLatFromZone(zoneId: String): Double {
        val parts = zoneId.split("_")
        return if (parts.size == 2) (parts[0].toIntOrNull() ?: 0) / 100.0 + 12.0 else 0.0
    }

    private fun parseLonFromZone(zoneId: String): Double {
        val parts = zoneId.split("_")
        return if (parts.size == 2) (parts[1].toIntOrNull() ?: 0) / 100.0 + 76.0 else 0.0
    }

    // Helper: direction code → representative bearing (center of sector)
    private fun directionToBearing(dir: String): Float = when (dir) {
        "N" -> 0f
        "E" -> 90f
        "S" -> 180f
        "W" -> 270f
        else -> 0f
    }
}
