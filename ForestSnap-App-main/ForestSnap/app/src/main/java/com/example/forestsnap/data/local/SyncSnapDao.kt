package com.example.forestsnap.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncSnapDao {
    @Insert
    suspend fun insertSnap(snap: SyncSnapEntity)

    @Insert
    suspend fun insertSnapReturningId(snap: SyncSnapEntity): Long

    @Query("SELECT * FROM sync_snaps WHERE isSynced = 0 ORDER BY timestamp DESC")
    fun getPendingSnaps(): Flow<List<SyncSnapEntity>>

    @Query("UPDATE sync_snaps SET isSynced = 1 WHERE id = :snapId")
    suspend fun markAsSynced(snapId: Int)

    @Query("SELECT COUNT(*) FROM sync_snaps WHERE isSynced = 0")
    fun getUnsyncedCount(): Flow<Int>

    @Query("DELETE FROM sync_snaps WHERE isSynced = 1")
    suspend fun clearSyncedSnaps()

    @Query("SELECT * FROM sync_snaps ORDER BY timestamp DESC LIMIT 1")
    fun getLatestSnap(): Flow<SyncSnapEntity?>

    @Query("SELECT * FROM sync_snaps ORDER BY timestamp DESC")
    fun getAllSnaps(): Flow<List<SyncSnapEntity>>

    @Query("""
        UPDATE sync_snaps
        SET isSynced = 1, zoneId = :zoneId, zoneScore = :zoneScore, zoneConfidence = :zoneConfidence
        WHERE id = :snapId
    """)
    suspend fun markSyncedWithZone(
        snapId: Int,
        zoneId: String,
        zoneScore: Float,
        zoneConfidence: Float
    )

    @Query("UPDATE sync_snaps SET submissionStatus = :status WHERE id = :snapId")
    suspend fun updateSubmissionStatus(snapId: Int, status: String)

    @Query("UPDATE sync_snaps SET campsiteVerdict = :verdict WHERE id = :snapId")
    suspend fun updateCampsiteVerdict(snapId: Int, verdict: String)
}