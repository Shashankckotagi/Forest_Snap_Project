package com.example.forestsnap.features.dashboard

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.forestsnap.data.local.ForestDatabase
import com.example.forestsnap.data.remote.WeatherService
import com.example.forestsnap.features.map.MapRepository
import com.example.forestsnap.features.map.ZoneInfo
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

// 1. UI State Definition
data class DashboardUiState(
    val isOnline: Boolean = true,
    val locationText: String = "Fetching GPS...",
    val weatherText: String = "Loading...",
    val riskLevel: String = "No data yet",
    val isRefreshing: Boolean = false,
    val userLat: Double = 12.45,   // Bannerghatta default
    val userLon: Double = 77.58,   // Bannerghatta default
    // Stage 4: Emergency Escape Data
    val isEmergency: Boolean    = false,
    val emergencyZoneId: String = "",
    val escapeDirection: String = ""
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)
    private val connectivityManager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // Zones from Firestore — used by MapDashboard to draw circles
    private val _zones = MutableStateFlow<Map<String, ZoneInfo>>(emptyMap())
    val zones: StateFlow<Map<String, ZoneInfo>> = _zones.asStateFlow()

    // Initialize Retrofit for Real-time Weather
    private val weatherApi = Retrofit.Builder()
        .baseUrl("https://api.open-meteo.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(WeatherService::class.java)

    private val db  = ForestDatabase.getDatabase(application)
    private val dao = db.syncSnapDao()

    init {
        monitorNetworkConnection()
        refreshData()
        observeLatestRisk()
        observeZonesInRealTime()
    }

    /** Stage 4: Real-time listener for zones and emergency detection. */
    private fun observeZonesInRealTime() {
        viewModelScope.launch {
            MapRepository.observeZonesFlow().collect { incomingZones ->
                _zones.value = incomingZones
                checkEmergencyState(incomingZones, _uiState.value.userLat, _uiState.value.userLon)
            }
        }
    }

    // fallback for manual pull to refresh
    fun fetchZones() {}

    /** State 4: Writes active fire flag to current GPS zone */
    fun reportActiveFire() {
        viewModelScope.launch {
            val lat = _uiState.value.userLat
            val lon = _uiState.value.userLon
            val zoneId = "${((lat - 12.0) * 100).toInt()}_${((lon - 76.0) * 100).toInt()}"
            try { com.example.forestsnap.data.sync.FirebaseRepository.reportActiveFire(zoneId) } catch (e: Exception) {}
        }
    }

    private fun checkEmergencyState(allZones: Map<String, ZoneInfo>, lat: Double, lon: Double) {
        val userZoneId = "${((lat - 12.0) * 100).toInt()}_${((lon - 76.0) * 100).toInt()}"
        
        // Find if user zone OR any neighbor is on fire
        val parts = userZoneId.split("_")
        if (parts.size != 2) return
        val r = parts[0].toInt()
        val c = parts[1].toInt()

        val neighborhood = listOf(
            userZoneId,
            "${r - 1}_${c}",  "${r + 1}_${c}",
            "${r}_${c - 1}",  "${r}_${c + 1}",
            "${r - 1}_${c - 1}", "${r - 1}_${c + 1}",
            "${r + 1}_${c - 1}", "${r + 1}_${c + 1}"
        )

        val fireZoneId = neighborhood.find { allZones[it]?.activeFire == true }
        
        if (fireZoneId != null) {
            // WE HAVE AN EMERGENCY! Compute safest route (Patent Claim 3)
            viewModelScope.launch {
                val spreadData = MapRepository.fetchSpread(userZoneId)
                val safestNeighborId = spreadData.spread.minByOrNull { it.value }?.key

                var escapeDir = "AWAY FROM FIRE"
                if (safestNeighborId != null) {
                    val (safeLat, safeLon) = com.example.forestsnap.features.map.zoneIdToLatLon(safestNeighborId)
                    val latDiff = safeLat - lat
                    val lonDiff = safeLon - lon

                    escapeDir = when {
                        Math.abs(latDiff) > Math.abs(lonDiff) -> if (latDiff > 0) "NORTH (↑)" else "SOUTH (↓)"
                        else -> if (lonDiff > 0) "EAST (→)" else "WEST (←)"
                    }
                }

                _uiState.update { 
                    it.copy(isEmergency = true, emergencyZoneId = fireZoneId, escapeDirection = escapeDir) 
                }
            }
        } else {
            _uiState.update { it.copy(isEmergency = false, emergencyZoneId = "", escapeDirection = "") }
        }
    }

    private fun observeLatestRisk() {
        viewModelScope.launch {
            dao.getLatestSnap().collect { snap ->
                if (snap != null) {
                    val label = if (snap.isSynced && snap.zoneScore > 0f) {
                        val zoneLabel = when {
                            snap.zoneScore >= 0.7f -> "HIGH"
                            snap.zoneScore >= 0.4f -> "MEDIUM"
                            else                   -> "LOW"
                        }
                        "$zoneLabel · Zone ${snap.zoneId} · ${(snap.zoneConfidence * 100).toInt()}% confidence"
                    } else {
                        val deviceLabel = when {
                            snap.riskScore >= 0.7f -> "HIGH"
                            snap.riskScore >= 0.4f -> "MEDIUM"
                            else                   -> "LOW"
                        }
                        "$deviceLabel (${"%.2f".format(snap.riskScore)}) · pending sync"
                    }
                    _uiState.update { it.copy(riskLevel = label) }
                }
            }
        }
    }

    // 2. Handle Pull-to-Refresh & Initial Load
    fun refreshData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, locationText = "Fetching GPS...", weatherText = "Updating...") }

            fetchLocation()

            // Allow time for the refreshing animation to be visible
            delay(1000)
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    // 3. Fetch Real GPS Location with High Accuracy
    @SuppressLint("MissingPermission")
    fun fetchLocation() {
        val context = getApplication<Application>().applicationContext

        val hasFineLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (hasFineLocation || hasCoarseLocation) {
            // Forces a fresh location request, bypassing the stale emulator cache
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                CancellationTokenSource().token
            ).addOnSuccessListener { location: Location? ->
                if (location != null) {
                    val lat = location.latitude
                    val lng = location.longitude

                    _uiState.update {
                        it.copy(
                            locationText = "Lat: ${String.format(Locale.US, "%.4f", lat)}, Lng: ${String.format(Locale.US, "%.4f", lng)}",
                            userLat = lat,
                            userLon = lng
                        )
                    }
                    // Re-check emergency on new GPS location
                    checkEmergencyState(_zones.value, lat, lng)

                    // Trigger real-time weather fetch once coordinates are known
                    fetchRealWeather(lat, lng)
                } else {
                    _uiState.update { it.copy(locationText = "Location unavailable") }
                }
            }.addOnFailureListener {
                _uiState.update { it.copy(locationText = "GPS Fetch Failed") }
            }
        } else {
            _uiState.update { it.copy(locationText = "Permissions Required") }
        }
    }

    // 4. Fetch Real-time Weather from Open-Meteo
    private fun fetchRealWeather(lat: Double, lon: Double) {
        viewModelScope.launch {
            try {
                val response = weatherApi.getStatus(lat, lon)
                val temp = response.current_weather.temperature
                val description = mapWeatherCode(response.current_weather.weathercode)

                _uiState.update { it.copy(weatherText = "$temp°C - $description") }
            } catch (e: Exception) {
                _uiState.update { it.copy(weatherText = "Offline Mode - Weather N/A") }
            }
        }
    }

    // 5. Automatic Network Monitoring
    private fun monitorNetworkConnection() {
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _uiState.update { it.copy(isOnline = true) }
            }

            override fun onLost(network: Network) {
                _uiState.update { it.copy(isOnline = false) }
            }
        }

        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        // Initial state check
        val activeNetwork = connectivityManager.activeNetwork
        val isInitiallyOnline = activeNetwork != null
        _uiState.update { it.copy(isOnline = isInitiallyOnline) }
    }

    // Simple mapper for Open-Meteo WMO Weather interpretation codes
    private fun mapWeatherCode(code: Int): String {
        return when (code) {
            0 -> "Clear sky"
            1, 2, 3 -> "Partly cloudy"
            45, 48 -> "Foggy"
            51, 53, 55 -> "Drizzle"
            61, 63, 65 -> "Rainy"
            71, 73, 75 -> "Snowy"
            95, 96, 99 -> "Thunderstorm"
            else -> "Cloudy"
        }
    }
}