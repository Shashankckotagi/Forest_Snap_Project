package com.example.forestsnap.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.forestsnap.data.local.ForestDatabase
import kotlinx.coroutines.flow.first

class CloudSyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val database = ForestDatabase.getDatabase(applicationContext)
        val dao      = database.syncSnapDao()

        return try {
            val pendingSnaps = dao.getPendingSnaps().first()
            if (pendingSnaps.isEmpty()) return Result.success()

            for (snap in pendingSnaps) {
                // Compute zone ID from GPS — ~1km grid cells (Patent Claim 2)
                val zoneRow = ((snap.latitude  - 12.0) * 100).toInt()
                val zoneCol = ((snap.longitude - 76.0) * 100).toInt()
                val zoneId  = "${zoneRow}_${zoneCol}"

                // Submit to Firebase — returns (status, zoneScore, confidence)
                val (submissionStatus, zoneScore, confidence) =
                    FirebaseRepository.submitSnap(snap)

                // Persist zone result back to Room
                dao.markSyncedWithZone(
                    snapId         = snap.id,
                    zoneId         = zoneId,
                    zoneScore      = zoneScore,
                    zoneConfidence = confidence
                )

                // Persist "New Submission" or "Already Recorded" badge
                dao.updateSubmissionStatus(snap.id, submissionStatus)
            }
            Result.success()

        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}