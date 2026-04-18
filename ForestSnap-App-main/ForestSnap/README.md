# ForestSnap — Agent Development README

> This file is written for an AI coding agent (Claude Code or similar).
> Read this entire file before touching any code.
> Each stage is a separate, completable unit of work.
> Do NOT start Stage N+1 until Stage N builds and runs without errors.

---

## What This Project Is

ForestSnap is an Android application for forest fire risk detection.
Rangers and hikers take photos in forests with no internet. The phone scores
each photo for fire risk using an on-device AI model — offline, no network needed.
When the user returns to coverage, data syncs to a backend which applies a
trust-weighted zone confidence score across multiple contributors.
That score feeds a fire spread prediction model that alerts forest officials
before a fire spreads to a neighboring zone.

The three patent-worthy components are:
1. On-device offline AI scoring at moment of photo capture (Patent Claim 1)
2. GPS-accuracy-weighted multi-contributor trust formula → zone confidence score (Patent Claim 2)
3. Zone confidence score feeding a graph-based fire spread propagation model (Patent Claim 3)

---

## Tech Stack

- Language: Kotlin
- UI: Jetpack Compose
- Architecture: MVVM + Repository pattern
- Local DB: Room (KSP annotation processor)
- Async: Coroutines + Flow
- Camera: CameraX
- Maps: Google Maps Compose
- Networking: Retrofit2 + OkHttp3
- Background sync: WorkManager
- Image loading: Coil
- Location: Google Fused Location Provider
- Weather: Open-Meteo API (free, no key needed)
- AI to be added in Stage 1: TensorFlow Lite
- Backend to be added in Stage 2: Python Flask + ngrok

---

## Current Project Structure

```
ForestSnap/
└── app/src/main/java/com/example/forestsnap/
    ├── MainActivity.kt
    ├── ForestSnapApplication.kt
    ├── core/
    │   ├── navigation/NavGraph.kt          — bottom nav + screen routing
    │   ├── theme/Theme.kt
    │   ├── theme/Type.kt
    │   ├── components/EarthLoader.kt       — loading animation component
    │   └── utils/PreferenceManager.kt      — DataStore preference keys
    ├── data/
    │   ├── local/
    │   │   ├── ForestDatabase.kt           — Room DB singleton, version 1
    │   │   ├── SyncSnapDao.kt              — insert, getPendingSnaps, markAsSynced, getUnsyncedCount, clearSynced
    │   │   └── SyncSnapEntity.kt           — id, photoPath, lat, lon, timestamp, isSynced
    │   ├── remote/
    │   │   └── WeatherService.kt           — Retrofit interface for Open-Meteo
    │   ├── repository/
    │   │   └── SyncSnapRepository.kt
    │   └── sync/
    │       └── CloudSyncWorker.kt          — WorkManager worker (has TODO placeholder — fix in Stage 3)
    └── features/
        ├── dashboard/
        │   ├── DashboardScreen.kt          — home: location card, weather card, risk card, camera button
        │   ├── DashboardViewModel.kt       — GPS fetch, weather fetch, network monitor, UI state
        │   └── CameraScreen.kt             — CameraX capture + GPS tag + Room insert
        ├── map/
        │   └── MapScreen.kt                — grey placeholder box (replace in Stage 4)
        ├── syncqueue/
        │   ├── SyncQueueScreen.kt          — list of unsynced photos, Force Sync button
        │   └── SyncQueueViewModel.kt       — exposes pendingQueue Flow from DAO
        └── settings/
            └── SettingsScreen.kt           — compression, strict location, offline mode toggles
```

---

## What Already Works — Do Not Break These

- Camera captures photos + GPS using CameraX and Fused Location Provider
- Photos saved to Room DB as SyncSnapEntity (photoPath, lat, lon, timestamp, isSynced=false)
- SyncQueueScreen shows all unsynced photos as cards with thumbnail, snap ID, time, coordinates
- Force Sync button triggers CloudSyncWorker via WorkManager
- DashboardScreen shows live GPS, real-time weather (Open-Meteo), online/offline dot indicator
- Network connectivity monitored automatically — state updates in real time
- Bottom navigation: Home, Map, Sync, Settings tabs
- Settings screen has working toggle switches

---

## Permissions Already in AndroidManifest.xml

INTERNET, CAMERA, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION,
READ_EXTERNAL_STORAGE (maxSdk 32), WRITE_EXTERNAL_STORAGE (maxSdk 28),
READ_MEDIA_IMAGES, ACCESS_NETWORK_STATE

---

## Dependencies Already in app/build.gradle.kts

androidx.core:core-ktx:1.12.0, compose BOM 2023.10.00, material3:1.3.0,
material-icons-extended, navigation-compose:2.7.4, datastore-preferences:1.0.0,
room-runtime/ktx/compiler:2.6.1 via KSP, coroutines:1.7.3,
lifecycle-viewmodel-compose:2.6.2, camera-core/camera2/lifecycle/view:1.3.1,
work-runtime-ktx:2.9.0, coil-compose:2.6.0, play-services-location:21.2.0,
retrofit:2.9.0 + converter-gson, maps-compose:4.1.1, play-services-maps:18.2.0,
guava:32.1.2-android

---

## Known Issues to Confirm Before Starting Stage 1

1. Room KSP annotation processor is declared correctly — verify with a clean build
2. CloudSyncWorker.kt has delay(1000) as a TODO placeholder — this is intentional, fix in Stage 3
3. MapScreen.kt has a grey placeholder Box — intentional, fix in Stage 4
4. SyncSnapEntity is at DB version 1 with no riskScore field — add this in Stage 1

---

---

# STAGE 1 — On-Device AI Scoring (Offline)

## Goal

Every photo taken must be scored for fire risk on the device itself before
any network call. The score is saved to Room alongside the photo.
No internet used at any point in this stage.

After this stage:
- User takes photo → phone runs TFLite model in ~2 seconds → risk score 0.0–1.0 produced
- Score saved to Room with the photo record
- Dashboard Risk Level card shows the latest score
- Sync queue shows score label next to each pending photo
- Everything works in airplane mode

---

### 1.1 — Add TFLite dependency

In `app/build.gradle.kts` inside `dependencies {}` add:

```kotlin
implementation("org.tensorflow:tensorflow-lite:2.13.0")
implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
```

Inside the `android {}` block add:

```kotlin
aaptOptions {
    noCompress += "tflite"
}
```

---

### 1.2 — Add model files (human step — agent cannot do this)

A human must:
1. Go to teachablemachine.withgoogle.com → Image Project → Standard model
2. Create 3 classes: HIGH_RISK, MEDIUM_RISK, LOW_RISK
3. Upload ~80 photos per class (dry brown leaves = HIGH, mixed = MEDIUM, green = LOW)
4. Train → Export as TensorFlow Lite Floating Point
5. Download model.tflite and labels.txt

Place both files at:
```
app/src/main/assets/model.tflite
app/src/main/assets/labels.txt
```

Create the assets/ folder if it does not exist at `app/src/main/assets/`

---

### 1.3 — Create MLScorer.kt

Create new file:
`app/src/main/java/com/example/forestsnap/features/dashboard/MLScorer.kt`

```kotlin
package com.example.forestsnap.features.dashboard

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class MLScorer(private val context: Context) {

    private val interpreter: Interpreter by lazy {
        val assetFd = context.assets.openFd("model.tflite")
        val stream = FileInputStream(assetFd.fileDescriptor)
        val buffer = stream.channel.map(
            FileChannel.MapMode.READ_ONLY,
            assetFd.startOffset,
            assetFd.declaredLength
        )
        Interpreter(buffer)
    }

    // Returns risk score 0.0 (safe) to 1.0 (extreme risk)
    fun scoreImage(bitmap: Bitmap): Float {
        val resized = Bitmap.createScaledBitmap(bitmap, 224, 224, true)
        val byteBuffer = ByteBuffer.allocateDirect(4 * 224 * 224 * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        for (y in 0 until 224) {
            for (x in 0 until 224) {
                val pixel = resized.getPixel(x, y)
                byteBuffer.putFloat((pixel shr 16 and 0xFF) / 255.0f)
                byteBuffer.putFloat((pixel shr 8  and 0xFF) / 255.0f)
                byteBuffer.putFloat((pixel        and 0xFF) / 255.0f)
            }
        }

        // Teachable Machine output order: [LOW_RISK, MEDIUM_RISK, HIGH_RISK]
        val output = Array(1) { FloatArray(3) }
        interpreter.run(byteBuffer, output)

        val low    = output[0][0]
        val medium = output[0][1]
        val high   = output[0][2]

        // Weighted continuous score
        return (low * 0.1f) + (medium * 0.5f) + (high * 0.9f)
    }

    fun scoreLabel(score: Float): String = when {
        score >= 0.7f -> "HIGH"
        score >= 0.4f -> "MEDIUM"
        else          -> "LOW"
    }
}
```

---

### 1.4 — Update SyncSnapEntity.kt

Add riskScore and gpsAccuracy fields. Replace entire file:

```kotlin
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
    val riskScore: Float = 0.0f,       // AI score 0.0–1.0 computed on-device
    val gpsAccuracy: Float = 10.0f     // GPS accuracy in metres — lower is better
)
```

---

### 1.5 — Update ForestDatabase.kt

Bump version to 2 and add destructive migration fallback:

```kotlin
package com.example.forestsnap.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SyncSnapEntity::class], version = 2, exportSchema = false)
abstract class ForestDatabase : RoomDatabase() {
    abstract fun syncSnapDao(): SyncSnapDao

    companion object {
        @Volatile
        private var INSTANCE: ForestDatabase? = null

        fun getDatabase(context: Context): ForestDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ForestDatabase::class.java,
                    "forest_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
```

---

### 1.6 — Add getLatestSnap() to SyncSnapDao.kt

Add this query to the existing interface — do not remove any existing queries:

```kotlin
@Query("SELECT * FROM sync_snaps ORDER BY timestamp DESC LIMIT 1")
fun getLatestSnap(): Flow<SyncSnapEntity?>
```

---

### 1.7 — Update CameraScreen.kt

Inside the `addOnSuccessListener` block, replace the existing `coroutineScope.launch` block
with this version that scores the image before saving:

```kotlin
fusedLocationClient.getCurrentLocation(
    Priority.PRIORITY_HIGH_ACCURACY,
    CancellationTokenSource().token
).addOnSuccessListener { location: Location? ->
    val lat      = location?.latitude  ?: 0.0
    val lon      = location?.longitude ?: 0.0
    val accuracy = location?.accuracy  ?: 10f

    // Score on-device before saving — no internet needed
    val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
    val score  = if (bitmap != null) MLScorer(context).scoreImage(bitmap) else 0.0f

    coroutineScope.launch {
        syncDao.insertSnap(
            SyncSnapEntity(
                photoPath   = file.absolutePath,
                latitude    = lat,
                longitude   = lon,
                timestamp   = System.currentTimeMillis(),
                riskScore   = score,
                gpsAccuracy = accuracy
            )
        )
        navController.popBackStack()
    }
}
```

---

### 1.8 — Update DashboardViewModel.kt

In `DashboardUiState` change the default riskLevel value:
```kotlin
val riskLevel: String = "No data yet"
```

Add the Room DB and `observeLatestRisk()` method to the ViewModel class.
Add these properties at the top of the class body (after existing properties):

```kotlin
private val db  = ForestDatabase.getDatabase(application)
private val dao = db.syncSnapDao()
```

Add this method to the class:

```kotlin
private fun observeLatestRisk() {
    viewModelScope.launch {
        dao.getLatestSnap().collect { snap ->
            if (snap != null) {
                val label = when {
                    snap.riskScore >= 0.7f -> "HIGH (${"%.2f".format(snap.riskScore)})"
                    snap.riskScore >= 0.4f -> "MEDIUM (${"%.2f".format(snap.riskScore)})"
                    else                   -> "LOW (${"%.2f".format(snap.riskScore)})"
                }
                _uiState.update { it.copy(riskLevel = label) }
            }
        }
    }
}
```

Call `observeLatestRisk()` from the `init` block after `refreshData()`.

---

### 1.9 — Update SyncQueueScreen.kt

Inside the card for each snap, add score display after the longitude Text line:

```kotlin
val scoreLabel = when {
    snap.riskScore >= 0.7f -> "HIGH RISK"
    snap.riskScore >= 0.4f -> "MEDIUM RISK"
    else                   -> "LOW RISK"
}
Text(
    text  = "$scoreLabel — Score: ${"%.2f".format(snap.riskScore)}",
    style = MaterialTheme.typography.bodyMedium,
    fontWeight = FontWeight.Bold,
    color = when {
        snap.riskScore >= 0.7f -> Color(0xFFE53935)
        snap.riskScore >= 0.4f -> Color(0xFFFB8C00)
        else                   -> Color(0xFF43A047)
    }
)
```

---

## Stage 1 Complete When

- Clean build with no errors
- Taking a photo → risk score appears on Dashboard Risk Level card within 3 seconds
- Sync queue card shows colored score label next to each photo
- All of the above works with airplane mode ON

---

---

# STAGE 2 — Flask Backend with Trust Formula

## Goal

Build a Python backend that receives scored photo data, applies the
GPS-accuracy-weighted multi-contributor trust formula, and returns a
zone confidence score. This is Patent Claim 2.

The backend lives in a new `backend/` folder at the project root,
alongside the `ForestSnap/` Android folder.

After this stage:
- POST /submit accepts zone_id, risk_score, gps_accuracy, timestamp, contributor_id
- Trust formula computes weighted zone score across all contributors
- GET /zones returns all zone scores
- GET /spread/<zone_id> returns spread probability to neighbor zones
- Backend reachable from Android device via ngrok URL

---

### 2.1 — Create backend/app.py

```python
from flask import Flask, request, jsonify
import time

app = Flask(__name__)

# In-memory store: zone_id -> list of submission dicts
zone_data = {}


@app.route('/health', methods=['GET'])
def health():
    return jsonify({'status': 'ok', 'zones': len(zone_data)})


@app.route('/submit', methods=['POST'])
def submit():
    """
    Accepts a scored photo submission from the Android app.
    Body JSON:
      zone_id:        str    — grid cell identifier computed from GPS
      risk_score:     float  — 0.0 to 1.0 from on-device TFLite model
      gps_accuracy:   float  — metres, lower is better
      timestamp:      int    — epoch milliseconds from Android
      contributor_id: str    — Android device ID
    Returns: zone_id, zone_score, confidence, contributors
    """
    data = request.get_json()
    if not data:
        return jsonify({'error': 'no json body'}), 400

    zone_id = data.get('zone_id')
    if not zone_id:
        return jsonify({'error': 'zone_id required'}), 400

    if zone_id not in zone_data:
        zone_data[zone_id] = []

    zone_data[zone_id].append({
        'risk_score':     float(data.get('risk_score', 0.5)),
        'gps_accuracy':   float(data.get('gps_accuracy', 10.0)),
        'timestamp':      int(data.get('timestamp', time.time() * 1000)),
        'contributor_id': str(data.get('contributor_id', 'unknown'))
    })

    result = calculate_trust_score(zone_data[zone_id])
    return jsonify({
        'zone_id':      zone_id,
        'zone_score':   result['score'],
        'confidence':   result['confidence'],
        'contributors': len(zone_data[zone_id])
    })


@app.route('/zones', methods=['GET'])
def get_zones():
    """Returns trust-weighted score for every zone that has data."""
    result = {}
    for zone_id, submissions in zone_data.items():
        scored = calculate_trust_score(submissions)
        result[zone_id] = {
            'score':        scored['score'],
            'confidence':   scored['confidence'],
            'contributors': len(submissions)
        }
    return jsonify(result)


@app.route('/spread/<zone_id>', methods=['GET'])
def get_spread(zone_id):
    """
    Given a zone, returns spread probability to each neighbor zone.
    Spread probability = neighbor_zone_score * 0.85
    (fuel load is primary determinant of spread)
    """
    neighbors = get_neighbors(zone_id)
    spread = {}
    for n in neighbors:
        if n in zone_data:
            score = calculate_trust_score(zone_data[n])['score']
            spread[n] = round(score * 0.85, 2)
        else:
            spread[n] = 0.10   # unknown zone = low default spread risk
    return jsonify({'source_zone': zone_id, 'spread': spread})


@app.route('/reset', methods=['POST'])
def reset():
    """Clears all zone data. For testing only."""
    zone_data.clear()
    return jsonify({'status': 'cleared'})


def calculate_trust_score(submissions):
    """
    Patent Claim 2 — GPS-accuracy-weighted multi-contributor trust formula.

    Each submission is weighted by:
      GPS weight:  perfect 3m = 1.0, poor 20m = 0.0  (linear)
      Time weight: fresh 0h  = 1.0, old  72h = 0.3  (linear decay)

    Zone score  = sum(risk_score * weight) / sum(weight)
    Confidence  = min(0.99, 0.5 + contributors * 0.1)
    """
    total_weight   = 0.0
    weighted_score = 0.0
    now_ms         = time.time() * 1000

    for s in submissions:
        gps_accuracy = s.get('gps_accuracy', 10.0)
        timestamp_ms = s.get('timestamp', now_ms)
        risk_score   = s.get('risk_score', 0.5)

        # GPS weight: 3m -> 1.0, 20m+ -> 0.0
        gps_weight  = max(0.0, 1.0 - ((gps_accuracy - 3.0) / 17.0))

        # Time weight: 0h -> 1.0, 72h -> 0.3
        age_hours   = (now_ms - timestamp_ms) / (1000 * 3600)
        time_weight = max(0.3, 1.0 - (age_hours / 72.0))

        weight          = gps_weight * time_weight
        weighted_score += risk_score * weight
        total_weight   += weight

    final_score = (weighted_score / total_weight) if total_weight > 0 else 0.0
    confidence  = min(0.99, 0.5 + len(submissions) * 0.1)

    return {
        'score':      round(final_score, 3),
        'confidence': round(confidence, 3)
    }


def get_neighbors(zone_id):
    """
    Zone IDs are "row_col" strings e.g. "12_76".
    Neighbors are the 4 adjacent grid cells (N, S, E, W).
    Grid formula: row = int((lat - 12.0) * 100), col = int((lon - 76.0) * 100)
    Each cell is approximately 1km x 1km around the Bandipur/Bangalore region.
    Adjust the 12.0 and 76.0 offsets for other regions.
    """
    try:
        parts = zone_id.split('_')
        r, c  = int(parts[0]), int(parts[1])
        return [f"{r-1}_{c}", f"{r+1}_{c}", f"{r}_{c-1}", f"{r}_{c+1}"]
    except (ValueError, IndexError):
        return []


if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, debug=True)
```

---

### 2.2 — Create backend/requirements.txt

```
flask==3.0.0
pyngrok==7.0.0
```

---

### 2.3 — Create backend/run_with_ngrok.py

```python
"""
Run this to start Flask backend with a public ngrok URL.
Steps:
  1. pip install flask pyngrok
  2. Sign up at ngrok.com, get authtoken
  3. Run: ngrok config add-authtoken YOUR_TOKEN
  4. Run: python run_with_ngrok.py
  5. Copy the printed URL into Constants.kt as BACKEND_URL
"""
from pyngrok import ngrok
import threading
import time
from app import app


def run_flask():
    app.run(host='0.0.0.0', port=5000, debug=False, use_reloader=False)


if __name__ == '__main__':
    flask_thread = threading.Thread(target=run_flask, daemon=True)
    flask_thread.start()
    time.sleep(1)

    tunnel     = ngrok.connect(5000)
    public_url = tunnel.public_url

    print("\n" + "=" * 55)
    print(f"  Backend URL : {public_url}")
    print(f"  Health check: {public_url}/health")
    print(f"  Paste URL into Constants.kt as BACKEND_URL")
    print("=" * 55 + "\n")

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("Shutting down.")
        ngrok.kill()
```

---

## Stage 2 Complete When

- `python backend/app.py` starts without errors
- `curl http://localhost:5000/health` returns `{"status":"ok","zones":0}`
- Posting two different submissions for the same zone_id returns an averaged trust-weighted score
- `python backend/run_with_ngrok.py` prints a public HTTPS URL

---

---

# STAGE 3 — Connect Android App to Backend

## Goal

Replace the placeholder `delay(1000)` TODO in CloudSyncWorker with real HTTP calls.
When the phone gets internet, all cached scored photos submit to the Flask backend
and receive zone confidence scores back, which are stored in Room.

After this stage:
- CloudSyncWorker sends each photo's data to POST /submit on the backend
- Returned zone_score and confidence stored in Room
- Dashboard shows zone-level confidence score after sync

---

### 3.1 — Update SyncSnapEntity.kt

Add three more fields for backend response. Bump DB version to 3.
Replace entire file:

```kotlin
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
    val riskScore: Float = 0.0f,           // on-device AI score
    val gpsAccuracy: Float = 10.0f,        // metres
    val zoneId: String = "",               // computed from GPS, stored after sync
    val zoneScore: Float = 0.0f,           // trust-weighted zone score from backend
    val zoneConfidence: Float = 0.0f       // confidence % from backend
)
```

---

### 3.2 — Update ForestDatabase.kt

Bump version to 3. Keep fallbackToDestructiveMigration():

```kotlin
@Database(entities = [SyncSnapEntity::class], version = 3, exportSchema = false)
```

---

### 3.3 — Add markSyncedWithZone() to SyncSnapDao.kt

Add this query — do not remove existing queries:

```kotlin
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
```

---

### 3.4 — Create Constants.kt

Create new file:
`app/src/main/java/com/example/forestsnap/core/utils/Constants.kt`

```kotlin
package com.example.forestsnap.core.utils

object Constants {
    // Use http://10.0.2.2:5000 for Android emulator (maps to localhost on host machine)
    // Use your ngrok HTTPS URL for physical device testing
    // Example: "https://abc123.ngrok-free.app"
    const val BACKEND_URL = "http://10.0.2.2:5000"
}
```

---

### 3.5 — Add OkHttp dependency

In `app/build.gradle.kts` add if not already present:

```kotlin
implementation("com.squareup.okhttp3:okhttp:4.12.0")
```

---

### 3.6 — Replace CloudSyncWorker.kt entirely

```kotlin
package com.example.forestsnap.data.sync

import android.content.Context
import android.provider.Settings
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.forestsnap.core.utils.Constants
import com.example.forestsnap.data.local.ForestDatabase
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CloudSyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        val database = ForestDatabase.getDatabase(applicationContext)
        val dao      = database.syncSnapDao()
        val deviceId = Settings.Secure.getString(
            applicationContext.contentResolver,
            Settings.Secure.ANDROID_ID
        )

        return try {
            val pendingSnaps = dao.getPendingSnaps().first()
            if (pendingSnaps.isEmpty()) return Result.success()

            for (snap in pendingSnaps) {
                // Compute zone ID from GPS — ~1km grid cells
                // Adjust 12.0 / 76.0 offsets for regions outside Bangalore/Bandipur
                val zoneRow = ((snap.latitude  - 12.0) * 100).toInt()
                val zoneCol = ((snap.longitude - 76.0) * 100).toInt()
                val zoneId  = "${zoneRow}_${zoneCol}"

                val body = JSONObject().apply {
                    put("zone_id",        zoneId)
                    put("risk_score",     snap.riskScore)
                    put("gps_accuracy",   snap.gpsAccuracy)
                    put("timestamp",      snap.timestamp)
                    put("contributor_id", deviceId)
                }.toString()

                val request = Request.Builder()
                    .url("${Constants.BACKEND_URL}/submit")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val json = JSONObject(response.body?.string() ?: "{}")
                        dao.markSyncedWithZone(
                            snapId         = snap.id,
                            zoneId         = json.optString("zone_id", zoneId),
                            zoneScore      = json.optDouble("zone_score", 0.0).toFloat(),
                            zoneConfidence = json.optDouble("confidence", 0.0).toFloat()
                        )
                    }
                }
            }
            Result.success()

        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
```

---

### 3.7 — Update DashboardViewModel.kt observeLatestRisk()

Replace the `observeLatestRisk()` method added in Stage 1 with this updated version
that shows zone data when available after sync:

```kotlin
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
```

---

## Stage 3 Complete When

- Backend is running locally (`python backend/app.py`)
- Force Sync button sends data to backend — Flask terminal shows POST /submit requests
- After sync, Room DB rows have isSynced=true, non-zero zoneScore and zoneConfidence
- Dashboard Risk Level card shows zone confidence score after sync

---

---

# STAGE 4 — Map Screen with Risk Zones and Spread Model

## Goal

Replace the grey MapScreen placeholder with a real screen showing
zone risk data and fire spread probabilities. This is the visual
demonstration of Patent Claim 3.

After this stage:
- Map screen loads zone scores from backend
- Zones displayed as color-coded cards sorted by risk (highest first)
- Red alert banner when any zone is HIGH
- Tapping a zone fetches and shows spread probability to all neighbor zones

---

### 4.1 — Create MapRepository.kt

Create new file:
`app/src/main/java/com/example/forestsnap/features/map/MapRepository.kt`

```kotlin
package com.example.forestsnap.features.map

import com.example.forestsnap.core.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class ZoneInfo(
    val zoneId:       String,
    val score:        Float,
    val confidence:   Float,
    val contributors: Int
)

data class SpreadInfo(
    val sourceZone: String,
    val spread:     Map<String, Float>
)

object MapRepository {

    private val client = OkHttpClient()

    suspend fun fetchZones(): Map<String, ZoneInfo> = withContext(Dispatchers.IO) {
        try {
            val request  = Request.Builder().url("${Constants.BACKEND_URL}/zones").build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyMap()

            val json   = JSONObject(response.body?.string() ?: "{}")
            val result = mutableMapOf<String, ZoneInfo>()

            for (key in json.keys()) {
                val obj = json.getJSONObject(key)
                result[key] = ZoneInfo(
                    zoneId       = key,
                    score        = obj.optDouble("score", 0.0).toFloat(),
                    confidence   = obj.optDouble("confidence", 0.0).toFloat(),
                    contributors = obj.optInt("contributors", 0)
                )
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    suspend fun fetchSpread(zoneId: String): SpreadInfo = withContext(Dispatchers.IO) {
        try {
            val request  = Request.Builder().url("${Constants.BACKEND_URL}/spread/$zoneId").build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext SpreadInfo(zoneId, emptyMap())

            val json      = JSONObject(response.body?.string() ?: "{}")
            val spreadObj = json.optJSONObject("spread") ?: return@withContext SpreadInfo(zoneId, emptyMap())
            val spreadMap = mutableMapOf<String, Float>()

            for (key in spreadObj.keys()) {
                spreadMap[key] = spreadObj.optDouble(key, 0.0).toFloat()
            }
            SpreadInfo(sourceZone = zoneId, spread = spreadMap)
        } catch (e: Exception) {
            SpreadInfo(zoneId, emptyMap())
        }
    }
}
```

---

### 4.2 — Replace MapScreen.kt entirely

```kotlin
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
                Divider()
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
```

---

## Stage 4 Complete When

- Map screen loads real zone data from backend (not placeholder)
- Zones shown sorted highest risk first
- Red banner appears when any zone is HIGH RISK
- Tapping a zone expands it and shows spread % to neighbors
- High spread risk neighbors shown in red

---

---

# STAGE 5 — Final Polish and End-to-End Wiring

## Goal

Wire all pieces together for a clean demo-ready build.
Dashboard reflects the full pipeline. Sync queue shows zone data.
Settings updated to reflect new app version.

After this stage the complete demo flow works:
1. Ranger takes photo in forest with no internet — risk score appears in ~2s
2. Returns to network — Force Sync sends data to backend
3. Dashboard updates to show zone confidence score with confidence %
4. Map screen shows colored risk zones with spread probabilities
5. Full patent pipeline is demonstrable end to end

---

### 5.1 — Update SyncQueueScreen.kt to show zone data after sync

Inside the snap card Column, replace or update the score display section
to show zone data when available:

```kotlin
if (snap.isSynced && snap.zoneScore > 0f) {
    Text(
        text  = "Zone ${snap.zoneId} · Score: ${"%.3f".format(snap.zoneScore)} · " +
                "Confidence: ${"%.0f".format(snap.zoneConfidence * 100)}%",
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF2E7D32),
        fontWeight = FontWeight.Bold
    )
} else {
    val label = when {
        snap.riskScore >= 0.7f -> "HIGH RISK"
        snap.riskScore >= 0.4f -> "MEDIUM RISK"
        else                   -> "LOW RISK"
    }
    Text(
        text  = "$label · Device score: ${"%.2f".format(snap.riskScore)} · Awaiting sync",
        style = MaterialTheme.typography.bodySmall,
        color = Color.Gray
    )
}
```

---

### 5.2 — Update Settings version string

In `SettingsScreen.kt` change the version text:

```kotlin
Text("v2.0.0 — AI Scoring + Trust Formula", style = MaterialTheme.typography.bodyMedium)
```

---

### 5.3 — Verify WorkManager is initialized

Check `ForestSnapApplication.kt`. If it is empty or only calls super, no changes needed.
WorkManager initializes automatically with default config.
Do not add manual WorkManager initialization unless there is a crash.

---

## Stage 5 Complete When

- Dashboard shows device score immediately after photo (before sync)
- Dashboard updates to zone confidence score and zone ID after sync
- Sync queue shows green zone data for synced items, grey pending status for unsynced
- Settings shows updated version string
- Full end-to-end demo works cleanly: photo → offline score → sync → zone map → spread

---

---

# Files Changed Per Stage — Quick Reference

| Stage | Files Modified                                                                  | Files Created          |
|-------|---------------------------------------------------------------------------------|------------------------|
| 1     | SyncSnapEntity, ForestDatabase, SyncSnapDao, CameraScreen, DashboardViewModel   | MLScorer.kt            |
| 2     | —                                                                               | backend/app.py, backend/requirements.txt, backend/run_with_ngrok.py |
| 3     | SyncSnapEntity, ForestDatabase, SyncSnapDao, CloudSyncWorker, DashboardViewModel | Constants.kt          |
| 4     | MapScreen.kt                                                                    | MapRepository.kt       |
| 5     | SyncQueueScreen, SettingsScreen                                                 | —                      |

---

# Hard Rules for the Agent

1. Never modify NavGraph.kt — navigation is correct as-is
2. Never modify ForestSnapApplication.kt unless WorkManager crashes
3. Never modify the Room DAO queries that already exist — only ADD new ones
4. Always bump the Room database version when SyncSnapEntity fields change
5. Always keep fallbackToDestructiveMigration() during development
6. Never remove any existing permissions from AndroidManifest.xml
7. BACKEND_URL in Constants.kt = `http://10.0.2.2:5000` for emulator, ngrok URL for physical device
8. Do not add new screens or new navigation routes
9. Do not change the package name `com.example.forestsnap`
10. Each stage must produce a clean build before the next stage starts
11. The backend folder lives at the project root alongside ForestSnap/ — not inside the Android module
