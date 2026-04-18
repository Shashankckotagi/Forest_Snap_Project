package com.example.forestsnap.features.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.forestsnap.features.dashboard.DashboardViewModel
import kotlinx.coroutines.launch

@Composable
fun MapScreen(viewModel: DashboardViewModel) {
    val scope        = rememberCoroutineScope()
    var zones        by remember { mutableStateOf<Map<String, ZoneInfo>>(emptyMap()) }
    var selectedZone by remember { mutableStateOf<String?>(null) }
    var spreadInfo   by remember { mutableStateOf<SpreadInfo?>(null) }
    var isLoading    by remember { mutableStateOf(false) }
    var errorMsg     by remember { mutableStateOf<String?>(null) }

    fun loadZones() {
        scope.launch {
            isLoading = true
            errorMsg  = null
            zones     = MapRepository.fetchZones()
            isLoading = false
            if (zones.isEmpty()) errorMsg = "No zone data. Take photos and sync first."
        }
    }

    LaunchedEffect(Unit) { loadZones() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Risk Zones", style = MaterialTheme.typography.headlineMedium,
                 fontWeight = FontWeight.Bold)
            IconButton(onClick = ::loadZones) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh zones")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        when {
            isLoading -> {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            errorMsg != null -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors   = CardDefaults.cardColors(containerColor = Color(0xFFFFF9C4))
                ) {
                    Text(errorMsg!!, modifier = Modifier.padding(16.dp),
                         style = MaterialTheme.typography.bodyMedium)
                }
            }

            else -> {
                // Alert banner for high-risk zones
                val highRisk = zones.values.filter { it.score >= 0.7f }
                if (highRisk.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        colors   = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
                    ) {
                        Row(modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null,
                                 tint = Color(0xFFE53935))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "${highRisk.size} HIGH RISK zone(s). Tap to see fire spread risk.",
                                style      = MaterialTheme.typography.bodySmall,
                                color      = Color(0xFFE53935),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(zones.entries.sortedByDescending { it.value.score }) { (zoneId, info) ->
                        ZoneCard(
                            info       = info,
                            isSelected = (selectedZone == zoneId),
                            spreadInfo = if (selectedZone == zoneId) spreadInfo else null,
                            onClick    = {
                                if (selectedZone == zoneId) {
                                    selectedZone = null
                                    spreadInfo   = null
                                } else {
                                    selectedZone = zoneId
                                    spreadInfo   = null
                                    scope.launch {
                                        spreadInfo = MapRepository.fetchSpread(zoneId)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ZoneCard(
    info: ZoneInfo,
    isSelected: Boolean,
    spreadInfo: SpreadInfo?,
    onClick: () -> Unit
) {
    val (bgColor, accentColor, riskLabel) = when {
        info.score >= 0.7f -> Triple(Color(0xFFFFEBEE), Color(0xFFE53935), "HIGH RISK")
        info.score >= 0.4f -> Triple(Color(0xFFFFF3E0), Color(0xFFFB8C00), "MEDIUM RISK")
        else               -> Triple(Color(0xFFE8F5E9), Color(0xFF43A047), "LOW RISK")
    }

    Card(
        modifier  = Modifier.fillMaxWidth().clickable { onClick() },
        elevation = CardDefaults.cardElevation(if (isSelected) 6.dp else 2.dp),
        colors    = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(accentColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Zone ${info.zoneId}", fontWeight = FontWeight.Bold,
                     style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.weight(1f))
                Text(riskLabel, color = accentColor, fontWeight = FontWeight.Bold,
                     style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Score: ${"%.3f".format(info.score)}  ·  " +
                "Confidence: ${"%.0f".format(info.confidence * 100)}%  ·  " +
                "${info.contributors} contributor(s)",
                style = MaterialTheme.typography.bodySmall,
                color = Color.DarkGray
            )

            if (isSelected) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text("Fire spread risk to neighbor zones:",
                     fontWeight = FontWeight.Bold,
                     style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(4.dp))

                if (spreadInfo == null) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else if (spreadInfo.spread.isEmpty()) {
                    Text("No neighbor zone data yet.",
                         style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                } else {
                    spreadInfo.spread.entries
                        .sortedByDescending { it.value }
                        .forEach { (neighborId, probability) ->
                            val pct   = (probability * 100).toInt()
                            val color = when {
                                probability >= 0.7f -> Color(0xFFE53935)
                                probability >= 0.4f -> Color(0xFFFB8C00)
                                else               -> Color(0xFF43A047)
                            }
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("→ Zone $neighborId",
                                     style = MaterialTheme.typography.bodySmall)
                                Text("$pct% spread risk", color = color,
                                     fontWeight = FontWeight.Bold,
                                     style = MaterialTheme.typography.bodySmall)
                            }
                        }
                }
            }
        }
    }
}