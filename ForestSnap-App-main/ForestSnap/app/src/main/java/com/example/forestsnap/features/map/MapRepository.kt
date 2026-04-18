package com.example.forestsnap.features.map

import com.example.forestsnap.data.sync.FirebaseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class ZoneInfo(
    val zoneId:          String,
    val score:           Float,
    val confidence:      Float,
    val contributors:    Int,
    val campsiteVerdict: String  = "",
    val campfireBanned:  Boolean = false,
    val activeFire:      Boolean = false // Stage 4
)

data class SpreadInfo(
    val sourceZone: String,
    val spread:     Map<String, Float>
)

/** Zone ID → centre lat/lon (mid-point of the ~1 km cell). */
fun zoneIdToLatLon(zoneId: String): Pair<Double, Double> {
    val parts = zoneId.split("_")
    if (parts.size != 2) return Pair(12.45, 77.58)
    val row = parts[0].toIntOrNull() ?: 0
    val col = parts[1].toIntOrNull() ?: 0
    return Pair(row / 100.0 + 12.005, col / 100.0 + 76.005)
}

object MapRepository {

    /**
     * Helper to map a raw Firestore zone map into a Domain ZoneInfo.
     */
    private fun mapRawDataToZoneInfo(data: Map<String, Any>): ZoneInfo {
        val id    = data["zoneId"]       as? String ?: ""
        val score = (data["score"]        as? Double)?.toFloat() ?: 0f
        val conf  = (data["confidence"]   as? Double)?.toFloat() ?: 0f
        val count = (data["contributors"] as? Long)?.toInt()    ?: 0
        
        val activeFire = data["activeFire"] as? Boolean ?: false

        @Suppress("UNCHECKED_CAST")
        val csMap   = data["campsiteScan"] as? Map<String, Any>
        val verdict = csMap?.get("verdict") as? String ?: ""

        return ZoneInfo(
            zoneId          = id,
            score           = score,
            confidence      = conf,
            contributors    = count,
            campsiteVerdict = verdict,
            campfireBanned  = verdict.startsWith("NOT SAFE"),
            activeFire      = activeFire
        )
    }

    /** Real-time flow of all zones. Used for Dashboard map and Emergency Detection. */
    fun observeZonesFlow(): Flow<Map<String, ZoneInfo>> {
        return FirebaseRepository.listenToZones().map { list ->
            list.associate { data -> 
                val z = mapRawDataToZoneInfo(data)
                z.zoneId to z 
            }
        }
    }

    suspend fun fetchZones(): Map<String, ZoneInfo> {
        return try {
            FirebaseRepository.fetchAllZones().associate { data ->
                val z = mapRawDataToZoneInfo(data)
                z.zoneId to z
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // Fire spread: neighbor zones with higher scores get higher probability
    suspend fun fetchSpread(zoneId: String): SpreadInfo {
        return try {
            val parts = zoneId.split("_")
            if (parts.size != 2) return SpreadInfo(zoneId, emptyMap())
            val row = parts[0].toInt()
            val col = parts[1].toInt()

            val neighbors = listOf(
                "${row - 1}_${col}",  "${row + 1}_${col}",
                "${row}_${col - 1}", "${row}_${col + 1}",
                "${row - 1}_${col - 1}", "${row - 1}_${col + 1}",
                "${row + 1}_${col - 1}", "${row + 1}_${col + 1}"
            )

            val allZones = fetchZones()
            val spread   = mutableMapOf<String, Float>()
            for (neighborId in neighbors) {
                val neighborZone = allZones[neighborId]
                val spreadProb   = if (neighborZone != null)
                    neighborZone.score * 0.85f   // Patent Claim 3 formula
                else 0.10f                       // default per spec
                spread[neighborId] = spreadProb
            }
            SpreadInfo(sourceZone = zoneId, spread = spread)
        } catch (e: Exception) {
            SpreadInfo(zoneId, emptyMap())
        }
    }
}
