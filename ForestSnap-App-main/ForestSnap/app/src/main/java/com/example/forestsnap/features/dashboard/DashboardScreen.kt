package com.example.forestsnap.features.dashboard

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.forestsnap.features.map.ZoneInfo
import com.example.forestsnap.features.map.zoneIdToLatLon
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.rememberCameraPositionState

// Bannerghatta National Park boundary — approximate polygon
private val BNP_BOUNDARY = listOf(
    LatLng(12.580, 77.530), LatLng(12.575, 77.600),
    LatLng(12.550, 77.650), LatLng(12.510, 77.680),
    LatLng(12.470, 77.700), LatLng(12.430, 77.690),
    LatLng(12.390, 77.660), LatLng(12.360, 77.620),
    LatLng(12.340, 77.570), LatLng(12.350, 77.510),
    LatLng(12.380, 77.470), LatLng(12.420, 77.450),
    LatLng(12.470, 77.460), LatLng(12.520, 77.490),
    LatLng(12.560, 77.510), LatLng(12.580, 77.530)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    navController: NavController,
    viewModel: DashboardViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val zones   by viewModel.zones.collectAsState()

    var selectedZone by remember { mutableStateOf<ZoneInfo?>(null) }
    var showFireDialog by remember { mutableStateOf(false) }
    val sheetState   = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            LatLng(uiState.userLat, uiState.userLon), 11f
        )
    }

    // Animate camera to user's GPS location once it's fetched
    LaunchedEffect(uiState.userLat, uiState.userLon) {
        cameraPositionState.animate(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(uiState.userLat, uiState.userLon), 12f
            )
        )
    }

    // Request location permission on first launch
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.refreshData() }

    LaunchedEffect(Unit) {
        permLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // ─── Google Map ─────────────────────────────────────────────────────
        GoogleMap(
            modifier           = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties         = MapProperties(isMyLocationEnabled = true),
            uiSettings         = MapUiSettings(myLocationButtonEnabled = false)
        ) {
            // Bannerghatta National Park boundary polygon
            Polygon(
                points      = BNP_BOUNDARY,
                fillColor   = Color(0x2027AE60),
                strokeColor = Color(0xFF27AE60),
                strokeWidth = 5f
            )

            // Zone circles — one per Firestore zone document
            zones.values.forEach { zone ->
                val (lat, lon) = zoneIdToLatLon(zone.zoneId)
                val center = LatLng(lat, lon)

                val isHighRisk = zone.campfireBanned || zone.score >= 0.7f
                val isMedium   = !isHighRisk && zone.score >= 0.4f

                val fillColor = when {
                    isHighRisk -> Color(0x80E53935)   // red
                    isMedium   -> Color(0x80FB8C00)   // orange
                    else       -> Color(0x8043A047)   // green
                }
                val strokeColor = when {
                    isHighRisk -> Color(0xFFE53935)
                    isMedium   -> Color(0xFFFB8C00)
                    else       -> Color(0xFF43A047)
                }
                val hue = when {
                    isHighRisk -> BitmapDescriptorFactory.HUE_RED
                    isMedium   -> BitmapDescriptorFactory.HUE_ORANGE
                    else       -> BitmapDescriptorFactory.HUE_GREEN
                }

                // Tappable marker — opens bottom sheet
                // If this is the active emergency zone, make it flash/red
                val isEmergencyTarget = uiState.isEmergency && zone.zoneId == uiState.emergencyZoneId
                
                // Visual radius circle
                Circle(
                    center      = center,
                    radius      = 600.0,
                    fillColor   = if (isEmergencyTarget || zone.activeFire) Color(0x99FF0000) else fillColor,
                    strokeColor = if (isEmergencyTarget || zone.activeFire) Color(0xFFFF0000) else strokeColor,
                    strokeWidth = if (isEmergencyTarget || zone.activeFire) 5f else 3f
                )

                // Tappable marker — opens bottom sheet
                Marker(
                    state = MarkerState(position = center),
                    icon  = BitmapDescriptorFactory.defaultMarker(hue),
                    title = "Zone ${zone.zoneId}",
                    onClick = { selectedZone = zone; false }
                )
            }
        }

        // ─── Top Info Bar ────────────────────────────────────────────────────
        TopInfoBar(
            locationText = uiState.locationText,
            weatherText  = uiState.weatherText,
            isOnline     = uiState.isOnline,
            onRefresh    = { viewModel.refreshData(); viewModel.fetchZones() },
            modifier     = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(12.dp)
        )

        // ─── FABs ─────────────────────────────────────────────────────────
        Column(
            modifier              = Modifier
                .align(Alignment.BottomEnd)
                // Add padding so it doesn't overlap with the emergency drawer if visible
                .padding(bottom = if (uiState.isEmergency) 120.dp else 16.dp, end = 16.dp),
            verticalArrangement   = Arrangement.spacedBy(12.dp),
            horizontalAlignment   = Alignment.End
        ) {
            // Stage 4: Report Fire Button
            FloatingActionButton(
                onClick        = { showFireDialog = true },
                containerColor = Color(0xFFD32F2F),
                modifier       = Modifier.size(52.dp)
            ) {
                Icon(Icons.Default.LocalFireDepartment, contentDescription = "Report Fire", tint = Color.White)
            }
            FloatingActionButton(
                onClick        = { navController.navigate("campsite_scan") },
                containerColor = Color(0xFF1565C0),
                modifier       = Modifier.size(52.dp)
            ) {
                Icon(Icons.Default.Explore, contentDescription = "360° Scan", tint = Color.White)
            }
            FloatingActionButton(
                onClick        = { navController.navigate("camera") },
                containerColor = Color(0xFF2E7D32)
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = "Camera", tint = Color.White)
            }
        }

        // ─── Zone count badge ─────────────────────────────────────────────
        if (zones.isNotEmpty()) {
            val highCount = zones.values.count { it.campfireBanned || it.score >= 0.7f }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
                    .background(
                        color = Color(0xCC1B1B1B),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text      = "${zones.size} zones  •  $highCount high risk",
                    color     = Color.White,
                    fontSize  = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        // ─── Stage 4: Emergency Escape Drawer ──────────────────────────────
        AnimatedVisibility(
            visible  = uiState.isEmergency,
            enter    = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFB71C1C)),
                elevation = CardDefaults.cardElevation(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = "Warning", tint = Color.Yellow, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "ACTIVE FIRE REPORTED!",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 18.sp
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Evacuate ${uiState.escapeDirection} immediately.",
                        color = Color.Yellow,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }

    // ─── Fire Report Confirmation Dialog ──────────────────────────────────
    if (showFireDialog) {
        AlertDialog(
            onDismissRequest = { showFireDialog = false },
            title = { Text("Report Active Fire?") },
            text = { Text("Are you sure you want to report an active fire at your current location? This will instantly trigger an emergency alert for ALL rangers nearby.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                    onClick = {
                        viewModel.reportActiveFire()
                        showFireDialog = false
                    }
                ) { Text("YES, REPORT FIRE", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showFireDialog = false }) { Text("CANCEL") }
            }
        )
    }

    // ─── Zone Detail Bottom Sheet ──────────────────────────────────────────
    if (selectedZone != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedZone = null },
            sheetState       = sheetState
        ) {
            ZoneDetailSheet(zone = selectedZone!!)
        }
    }
}

// ─── Top Info Bar ─────────────────────────────────────────────────────────────

@Composable
fun TopInfoBar(
    locationText: String,
    weatherText:  String,
    isOnline:     Boolean,
    onRefresh:    () -> Unit,
    modifier:     Modifier = Modifier
) {
    Card(
        modifier  = modifier,
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = Color(0xEE1B5E20)),
        elevation = CardDefaults.cardElevation(6.dp)
    ) {
        Row(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment   = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text      = "🌿 Forest Ranger",
                    color     = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize  = 14.sp
                )
                Text(
                    text     = locationText,
                    color    = Color(0xCCFFFFFF),
                    fontSize = 11.sp
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text      = weatherText,
                    color     = Color.White,
                    fontSize  = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(
                                if (isOnline) Color(0xFF69F0AE) else Color(0xFFFF5252),
                                shape = RoundedCornerShape(4.dp)
                            )
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text     = if (isOnline) "Online" else "Offline",
                        color    = Color(0xCCFFFFFF),
                        fontSize = 11.sp
                    )
                }
            }
            IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.White)
            }
        }
    }
}

// ─── Zone Detail Bottom Sheet ─────────────────────────────────────────────────

@Composable
fun ZoneDetailSheet(zone: ZoneInfo) {
    val (accentColor, riskLabel) = when {
        zone.campfireBanned || zone.score >= 0.7f -> Color(0xFFE53935) to "HIGH RISK"
        zone.score >= 0.4f                        -> Color(0xFFFB8C00) to "MEDIUM RISK"
        else                                      -> Color(0xFF43A047) to "LOW RISK"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text       = "Zone ${zone.zoneId}",
                style      = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                modifier   = Modifier.weight(1f)
            )
            Surface(
                color = accentColor,
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    text     = riskLabel,
                    color    = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        HorizontalDivider()

        // Stats row
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ZoneStatItem("Risk Score",    "%.3f".format(zone.score),              accentColor)
            ZoneStatItem("Confidence",   "%.0f%%".format(zone.confidence * 100), Color(0xFF1565C0))
            ZoneStatItem("Contributors", zone.contributors.toString(),            Color(0xFF6A1B9A))
        }

        // Campfire status
        val (fireColor, fireTxt) = if (zone.campfireBanned)
            Color(0xFFE53935) to "🔥 CAMPFIRE BANNED"
        else
            Color(0xFF43A047) to "✅ Campfire Permitted"

        Surface(
            color = fireColor.copy(alpha = 0.1f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text       = fireTxt,
                color      = fireColor,
                fontWeight = FontWeight.Bold,
                modifier   = Modifier.padding(12.dp)
            )
        }

        // Campsite scan verdict (if available)
        if (zone.campsiteVerdict.isNotEmpty()) {
            val verdictColor = when {
                zone.campsiteVerdict.startsWith("NOT SAFE") -> Color(0xFFE53935)
                zone.campsiteVerdict.startsWith("CAUTION")  -> Color(0xFFE65100)
                else                                        -> Color(0xFF2E7D32)
            }
            Surface(
                color = verdictColor.copy(alpha = 0.08f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("360° Campsite Scan",
                         style = MaterialTheme.typography.labelSmall,
                         color = Color.Gray)
                    Text(
                        text       = zone.campsiteVerdict,
                        color      = verdictColor,
                        fontWeight = FontWeight.Bold,
                        fontSize   = 13.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun ZoneStatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text       = value,
            fontWeight = FontWeight.ExtraBold,
            fontSize   = 22.sp,
            color      = color
        )
        Text(
            text     = label,
            fontSize = 11.sp,
            color    = Color.Gray,
            textAlign = TextAlign.Center
        )
    }
}

// Keep LocationWarningBanner for backward compat (unused but shouldn't cause compile errors)
@Composable
fun LocationWarningBanner(onDismiss: () -> Unit) {}
@Composable
fun DashboardCard(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, containerColor: Color) {}