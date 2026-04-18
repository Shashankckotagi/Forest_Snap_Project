package com.example.forestsnap.data.sync

import com.example.forestsnap.data.local.SyncSnapEntity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

object FirebaseRepository {

    private val db = FirebaseFirestore.getInstance()

    // Patent Trust Formula — weighted by GPS accuracy and time decay
    private fun computeTrustWeight(gpsAccuracy: Float, timestamp: Long): Float {
        val accuracyWeight = 1.0f / (1.0f + gpsAccuracy / 10.0f)
        val ageHours       = (System.currentTimeMillis() - timestamp) / 3_600_000.0f
        val timeDecay      = Math.exp((-0.1f * ageHours).toDouble()).toFloat()
        return accuracyWeight * timeDecay
    }

    /**
     * Stage 1: Submits a snap to Firestore.
     * - Checks if a zone document already exists for this zoneId.
     * - If YES: returns ("Already Recorded", existingScore, existingConfidence)
     * - If NO:  writes new document, returns ("New Submission", newScore, newConfidence)
     *
     * Returns: Triple<submissionStatus, zoneScore, zoneConfidence>
     */
    suspend fun submitSnap(snap: SyncSnapEntity): Triple<String, Float, Float> {
        // Zone ID from GPS — ~1km grid cells around Bannerghatta / Bangalore
        val zoneRow = ((snap.latitude  - 12.0) * 100).toInt()
        val zoneCol = ((snap.longitude - 76.0) * 100).toInt()
        val zoneId  = "${zoneRow}_${zoneCol}"

        // --- Stage 1: Check if this zone already exists in Firestore ---
        val existingDoc = db.collection("zones").document(zoneId).get().await()

        if (existingDoc.exists()) {
            // Zone already recorded — read back existing data, do NOT overwrite
            val existingScore      = (existingDoc.getDouble("score")      ?: 0.0).toFloat()
            val existingConfidence = (existingDoc.getDouble("confidence") ?: 0.0).toFloat()
            return Triple("Already Recorded", existingScore, existingConfidence)
        }

        // Zone is new — compute trust weight and write to Firestore
        val weight = computeTrustWeight(snap.gpsAccuracy, snap.timestamp)

        // Write individual submission record
        val submissionData = hashMapOf(
            "snapId"       to snap.id,
            "zoneId"       to zoneId,
            "riskScore"    to snap.riskScore,
            "gpsAccuracy"  to snap.gpsAccuracy,
            "bearing"      to snap.bearing,
            "timestamp"    to snap.timestamp,
            "trustWeight"  to weight,
            "latitude"     to snap.latitude,
            "longitude"    to snap.longitude
        )
        db.collection("submissions").add(submissionData).await()

        // Read all submissions for this zone to compute trust-weighted zone score (Patent Claim 2)
        val submissions = db.collection("submissions")
            .whereEqualTo("zoneId", zoneId)
            .get().await()

        var weightedSum = 0.0f
        var totalWeight = 0.0f
        for (doc in submissions) {
            val w = (doc.getDouble("trustWeight") ?: 0.0).toFloat()
            val r = (doc.getDouble("riskScore")   ?: 0.0).toFloat()
            weightedSum += w * r
            totalWeight += w
        }

        val zoneScore    = if (totalWeight > 0) weightedSum / totalWeight else snap.riskScore
        val contributors = submissions.size()
        val confidence   = (contributors / (contributors + 1.0f))

        // Write zone summary document (Patent Claim 2 output)
        val zoneData = hashMapOf(
            "zoneId"       to zoneId,
            "score"        to zoneScore,
            "confidence"   to confidence,
            "contributors" to contributors,
            "bearing"      to snap.bearing,
            "lastUpdated"  to System.currentTimeMillis()
        )
        db.collection("zones").document(zoneId)
            .set(zoneData, SetOptions.merge()).await()

        return Triple("New Submission", zoneScore, confidence)
    }

    suspend fun fetchAllZones(): List<Map<String, Any>> {
        return db.collection("zones")
            .get().await()
            .documents
            .mapNotNull { it.data }
    }

    /**
     * Stage 4: Real-time listener that instantly pushes all zones over a Flow.
     * Required for real-time emergency fire escape alerts.
     */
    fun listenToZones(): Flow<List<Map<String, Any>>> = callbackFlow {
        val listener = db.collection("zones").addSnapshotListener { snap, _ ->
            if (snap != null) trySend(snap.documents.mapNotNull { it.data })
        }
        awaitClose { listener.remove() }
    }

    /**
     * Stage 4: Report an active fire by marking activeFire=true on the zone doc.
     */
    suspend fun reportActiveFire(zoneId: String) {
        val data = hashMapOf<String, Any>(
            "activeFire"     to true,
            "fireReportedAt" to System.currentTimeMillis()
        )
        db.collection("zones").document(zoneId).set(data, SetOptions.merge()).await()
    }

    /**
     * Stage 2: Stores the 360° campsite scan results under the zone document.
     * Merges into zones/{zoneId} with key "campsiteScan" containing all 4 direction scores + verdict.
     */
    suspend fun submitCampsiteScan(
        zoneId: String,
        scores: Map<String, Float>,    // e.g. {"N": 0.8, "E": 0.3, "S": 0.5, "W": 0.2}
        verdict: String
    ) {
        val campsiteScanData = hashMapOf(
            "campsiteScan" to hashMapOf(
                "N"         to (scores["N"] ?: 0f),
                "E"         to (scores["E"] ?: 0f),
                "S"         to (scores["S"] ?: 0f),
                "W"         to (scores["W"] ?: 0f),
                "verdict"   to verdict,
                "timestamp" to System.currentTimeMillis()
            )
        )
        db.collection("zones").document(zoneId)
            .set(campsiteScanData, SetOptions.merge()).await()
    }
}
