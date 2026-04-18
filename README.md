**🌲 ForestSnap**

Forest Fire Detection, Prevention & Emergency Response System

Version 2.0 | Android Application + Web Dashboard

Package: com.example.forestsnap | Min SDK: 26 | Target SDK: 34

Target Region: Bannerghatta National Park, Bangalore, Karnataka, India

| Patent Claim 1On-Device Offline AI Scoring | Patent Claim 2GPS-Weighted Trust Formula | Patent Claim 3Graph-Based Fire Spread Model |
| --- | --- | --- |

# 1\. Project Overview

ForestSnap is an **AI-powered Android application** designed for forest fire detection, risk prevention, and emergency escape guidance. It targets forest rangers, hikers, and wildlife officials operating inside forests with **no internet connectivity** — such as Bannerghatta National Park, Bandipur Tiger Reserve, and other dense forest areas in Karnataka, India.

The system operates as a three-stage pipeline: **Detection** (capturing and scoring photos offline using on-device AI), **Prevention** (storing GPS-tagged risk data in Firebase and displaying region-level campfire advisories), and **Emergency Response** (computing fire spread direction across 8 neighboring zones and generating an escape route for users on the ground).

What makes ForestSnap fundamentally different from existing wildfire detection systems is that it operates **before a fire starts** — mapping sub-canopy fuel load at ground level, which no satellite-based system can see. Existing systems like VIIRS and MODIS detect fires only after they are large enough to produce detectable heat signatures, typically triggering alerts 6–12 hours after a fire begins. ForestSnap generates zone-level risk scores **hours or days before ignition** based on actual fuel density captured by people on the ground.

## 1.1 Mission Statement

To give forest rangers and emergency responders a real-time, ground-truth intelligence layer that lets them identify high-risk zones, prevent campfires in dangerous areas, and respond to active fires with a mathematically derived escape route — all without depending on internet connectivity at the point of data collection.

## 1.2 Target Users

| User Type | Primary Use Case | Key Feature Used |
| --- | --- | --- |
| Forest Rangers | Daily patrol — photograph undergrowth and log risk scores offline | Camera + Offline AI Scoring |
| Hikers / Trekkers | Check campsite safety before lighting a fire | 360° Campsite Scan |
| Wildlife Officials | Monitor zone risk across Bannerghatta on a live map | Dashboard Map + Zone Circles |
| Emergency Responders | Report active fire and get predicted spread direction | Fire Report + Escape Route |
| Park Management | View all recorded zones and fire history on web dashboard | Web Dashboard (Stage 5) |

# 2\. The Problem Being Solved

Forest fires in India cause devastating ecological and economic damage every year. Karnataka alone loses thousands of hectares of forest annually to fires, many of which could have been prevented or contained if detected earlier. The fundamental problem with current systems is that they are all reactive — they tell you a fire is happening, not where one is about to start.

## 2.1 Limitations of Existing Systems

| System | Detection Method | Key Limitation |
| --- | --- | --- |
| VIIRS / MODIS Satellites | Thermal infrared sensors from orbit | Only detects fires after large heat signature — 6–12 hour delay minimum |
| Forest Dept Watchtowers | Visual observation by stationed guard | Single point of observation, human error, no quantitative data |
| Aerial Surveys | Helicopter or drone thermal cameras | Expensive, infrequent, not available in remote areas |
| Weather APIs | Wind speed, humidity, temperature data | No ground-truth fuel load data — predicts risk but not location |
| Existing Mobile Apps | Photo sharing, GPS tagging | No AI scoring, no trust weighting, no spread prediction |

## 2.2 The Core Gap

No existing system captures **sub-canopy fuel load density** — the actual quantity of dry leaves, dead wood, and flammable undergrowth on the forest floor — which is the primary determinant of fire ignition probability. This data simply cannot be gathered from orbit or from a single watchtower. It requires human observation at ground level, and no system has yet combined that observation with **offline AI scoring, multi-contributor trust aggregation, and graph-based spread prediction** in a single pipeline.

# 3\. Novelty & Patent Claims

ForestSnap's novelty lies not in any single component, but in the **specific combination of four technical subsystems** that have never been combined in this way in any published research paper, patent filing, or deployed product as of the time of writing.

## 3.1 What Exists Separately (Prior Art)

| Component | Prior Art Exists? | Where It Exists |
| --- | --- | --- |
| Mobile AI for fire risk classification | Yes | Research papers, TFLite demos |
| Crowdsourced wildfire data collection | Yes | Academic papers (2019) |
| Edge AI offline inference on Android | Yes | General TFLite applications |
| GPS-weighted trust scoring | Yes | Ride-share and IoT domains |
| Fire spread graph models | Yes | Research / simulation software |
| 360° photo scanning via compass | No | Not found in any existing system |
| Full pipeline: offline score → trust → spread | No | This combination is novel |

## 3.2 Patent Claim 1 — On-Device Offline AI Scoring

**Claim:** A system wherein a mobile device captures a photograph of forest undergrowth and, using a locally stored TensorFlow Lite model (MobileNetV3 architecture), computes a fire risk score between 0.0 and 1.0 at the moment of capture, without requiring any network connectivity, and stores this score alongside the GPS coordinates and compass bearing in a local Room database.

**Technical Implementation:** MLScorer.kt — scoreImage(bitmap: Bitmap): Float

*   Model: model\_unquant.tflite trained on 3 classes — High\_Risk (index 0), Medium\_Risk (index 1), Low\_Risk (index 2)
*   Input: 224×224 pixel bitmap, normalized to \[0,1\] float range per channel
*   Score formula: (prob\[0\] × 0.9) + (prob\[1\] × 0.5) + (prob\[2\] × 0.2)
*   Runs in ~2 seconds on a mid-range Android device with no battery drain concern
*   Persisted to Room DB field riskScore: Float before any network attempt

## 3.3 Patent Claim 2 — GPS-Accuracy-Weighted Multi-Contributor Trust Formula

**Claim:** A system wherein multiple contributors independently submit risk scores for the same geographic zone, and the system computes a confidence-weighted zone score using each contributor's GPS accuracy (in metres) and submission recency as trust weights, such that high-accuracy, recent readings contribute proportionally more to the final zone risk score than low-accuracy or stale readings.

**Technical Implementation:** FirebaseRepository.kt — computeTrustWeight() + submitSnap()

**Trust Formula:**

gpsWeight = 1.0 / (1.0 + gpsAccuracy / 10.0)

timeDecay = e^(-0.1 × ageInHours)

weight = gpsWeight × timeDecay

zoneScore = Σ(riskScore × weight) / Σ(weight)

confidence = contributors / (contributors + 1.0)

*   GPS accuracy of ±3m yields weight ≈ 0.77, while ±20m yields weight ≈ 0.33
*   A reading 72 hours old has timeDecay ≈ 0.0007 — effectively excluded
*   Zone ID formula: zoneRow = int((lat − 12.0) × 100), zoneCol = int((lon − 76.0) × 100) → creates ~1km grid cells
*   Stored in Firestore under zones/{zoneId} with fields: score, confidence, contributors, lastUpdated
*   If zone already exists in Firestore, app displays 'Already Recorded' badge — no duplicate

## 3.4 Patent Claim 3 — Zone Graph Fire Spread Propagation Model

**Claim:** A system wherein, upon detection of an active fire in a geographic zone, the system reads the trust-weighted risk scores of all 8 neighboring zones from the database and computes a spread probability for each neighbor using the formula: spreadProbability = neighborZoneScore × 0.85, identifying the highest-probability spread direction and generating an escape route recommendation in the opposite compass direction.

**Technical Implementation:** MapRepository.kt + DashboardViewModel.kt — reportActiveFire() + computeEscapeDirection()

*   8-directional neighbor lookup: current zone ± 1 in both row and column dimensions (includes diagonals)
*   Neighbor zones with no Firestore record are assigned default spread probability of 0.1
*   Highest-probability neighbor = predicted fire spread direction
*   Escape direction = opposite compass direction from highest-probability neighbor
*   Active fire zones marked activeFire: true in Firestore — triggers real-time emergency alert to all connected devices via Firestore snapshot listener
*   Emergency escape drawer appears on all devices viewing the app when any zone reports activeFire = true

## 3.5 Patent Claim 4 — 360° Directional Campsite Safety Assessment

**Claim:** A system wherein a user takes photographs in four compass directions (North, East, South, West) determined by the device magnetometer and accelerometer, each photograph is independently scored by an on-device AI model, and the campsite safety verdict is determined by the worst-case directional score — ensuring that even a single high-risk direction results in a 'NOT SAFE' verdict, preventing campfire ignition in partially-dangerous locations.

**Technical Implementation:** CampsiteScanScreen.kt + CampsiteScanViewModel.kt

*   Bearing-to-direction: 315°–45° = NORTH, 45°–135° = EAST, 135°–225° = SOUTH, 225°–315° = WEST
*   Compass read from SensorManager using TYPE\_ACCELEROMETER + TYPE\_MAGNETIC\_FIELD fusion
*   Verdict logic: worstScore ≥ 0.7 → NOT SAFE / BANNED, ≥ 0.4 → CAUTION / DISCOURAGED, else → SAFE / PERMITTED
*   All 4 directional scores + verdict stored to Firestore under zones/{zoneId}/campsiteScan
*   A sector already captured cannot be overwritten in the same session

# 4\. System Architecture

ForestSnap is built as a native Android application with a Firebase backend and a companion web dashboard. The architecture is deliberately layered so that the core data collection pipeline works entirely offline, while the intelligence aggregation and alerting pipeline activates when connectivity is available.

## 4.1 Full Pipeline — End to End

| Stage | Location | What Happens | Network Required? |
| --- | --- | --- | --- |
| Photo Capture | Android Device | User takes photo via CameraX | No |
| AI Scoring | Android Device | MLScorer runs TFLite model — outputs risk score 0.0–1.0 | No |
| GPS + Bearing | Android Device | FusedLocationClient reads lat/lon/accuracy; SensorManager reads compass bearing | No |
| Local Storage | Room Database | SyncSnapEntity written with all fields — photo path, risk score, GPS, bearing, timestamp | No |
| Network Detected | CloudSyncWorker | WorkManager detects connectivity, triggers sync | Yes |
| Zone ID Compute | CloudSyncWorker | zoneId = row_col from GPS formula | Yes |
| Firebase Check | FirebaseRepository | Checks if zones/{zoneId} exists — returns 'Already Recorded' or proceeds | Yes |
| Trust Formula | FirebaseRepository | Computes GPS+time weighted zone score across all contributors | Yes |
| Firestore Write | Firebase Cloud | Zone document updated with score, confidence, contributors | Yes |
| Map Display | DashboardScreen | All zone documents read from Firestore, rendered as colored circles on Google Map | Yes |
| Spread Prediction | MapRepository | 8-neighbor zones read, spreadProb computed per neighbor | Yes |
| Escape Route | DashboardViewModel | Opposite direction of highest-spread neighbor shown as escape direction | Yes |

## 4.2 Technology Stack

| Layer | Technology | Version / Details |
| --- | --- | --- |
| Android UI | Jetpack Compose + Material 3 | Compose BOM 2023.10.00 |
| Navigation | Navigation Compose | 2.7.4 — NavHost with named routes |
| Local Database | Room + KSP | 2.6.1 — SyncSnapEntity, version 5 |
| On-Device AI | TensorFlow Lite | 2.13.0 — model_unquant.tflite |
| Cloud Database | Firebase Firestore | BOM 33.1.0 — collections: zones, submissions, fireReports |
| GPS / Location | Google Play Services Location | 21.2.0 — FusedLocationProviderClient |
| Compass / IMU | Android SensorManager | Built-in — TYPE_ACCELEROMETER + TYPE_MAGNETIC_FIELD |
| Map Display | Maps Compose + Play Maps | 4.1.1 + 18.2.0 — Google Maps SDK for Android |
| Background Sync | WorkManager | 2.9.0 — CloudSyncWorker |
| Camera | CameraX | 1.3.1 — Preview + ImageCapture |
| HTTP Client | OkHttp | 4.12.0 — for weather API calls |
| Weather | OpenWeatherMap API | Free tier — current conditions by lat/lon |
| Coroutines | Kotlin Coroutines | 1.7.3 — all async operations |
| Dependency Injection | Manual (ViewModel + object) | No DI framework — kept simple for college project scope |

# 5\. Project Structure

The project follows a feature-based package structure. Each screen has its own folder containing the Composable screen file and its ViewModel. Shared infrastructure (database, sync, navigation) lives in dedicated packages.

**Android App — ForestSnap/app/src/main/java/com/example/forestsnap/**

*   core/navigation/ — NavGraph.kt, MainScreen, Screen sealed class, drawer + bottom nav
*   core/theme/ — Theme.kt, Type.kt — Material 3 green forest theme
*   core/utils/ — Constants.kt (BACKEND\_URL), PreferenceManager.kt
*   core/components/ — EarthLoader.kt — animated loading indicator
*   data/local/ — ForestDatabase.kt (v5), SyncSnapEntity.kt, SyncSnapDao.kt
*   data/remote/ — WeatherService.kt — OkHttp call to OpenWeatherMap
*   data/repository/ — SyncSnapRepository.kt
*   data/sync/ — CloudSyncWorker.kt (WorkManager), FirebaseRepository.kt (Firestore + trust formula)
*   features/dashboard/ — DashboardScreen.kt, DashboardViewModel.kt, CameraScreen.kt, MLScorer.kt
*   features/campsite/ — CampsiteScanScreen.kt, CampsiteScanViewModel.kt
*   features/map/ — MapScreen.kt, MapRepository.kt
*   features/syncqueue/ — SyncQueueScreen.kt, SyncQueueViewModel.kt
*   features/settings/ — SettingsScreen.kt

**Assets**

*   app/src/main/assets/model\_unquant.tflite — 2MB trained TFLite model
*   app/src/main/assets/labels.txt — 3 classes: High\_Risk, Medium\_Risk, Low\_Risk

**Firebase Configuration (Required — Not in Repo)**

*   app/google-services.json — Download from Firebase Console → Project Settings → Your Apps
*   Firestore collections: zones (zone risk documents), submissions (individual snap records), fireReports (active fire reports)

# 6\. Database Schema

## 6.1 Room Database — SyncSnapEntity (Local, v5)

| Field | Type | Description |
| --- | --- | --- |
| id | Int (PK, auto) | Auto-generated primary key |
| photoPath | String | Absolute path to captured photo on device storage |
| latitude | Double | GPS latitude at moment of capture |
| longitude | Double | GPS longitude at moment of capture |
| timestamp | Long | Unix epoch milliseconds at capture |
| isSynced | Boolean | False until CloudSyncWorker successfully syncs to Firebase |
| riskScore | Float | On-device AI score 0.0–1.0 (Patent Claim 1) |
| gpsAccuracy | Float | GPS accuracy in metres — lower is better (used in trust formula) |
| bearing | Float | Compass bearing 0–360° at moment of capture |
| zoneId | String | Computed as row_col after sync |
| zoneScore | Float | Trust-weighted zone score returned from Firebase after sync |
| zoneConfidence | Float | Zone confidence percentage returned from Firebase |
| submissionStatus | String | 'New Submission' or 'Already Recorded' after sync |
| campsiteVerdict | String | 'NOT SAFE', 'CAUTION', or 'SAFE TO CAMP' if part of campsite scan |

## 6.2 Firestore — zones/{zoneId}

| Field | Type | Description |
| --- | --- | --- |
| zoneId | String | Row_col grid identifier — e.g. '88_150' |
| score | Float | Trust-weighted aggregate risk score across all contributors |
| confidence | Float | Confidence level: contributors / (contributors + 1) |
| contributors | Int | Number of unique submissions for this zone |
| bearing | Float | Bearing of the most recent submission |
| lastUpdated | Long | Unix timestamp of most recent update |
| activeFire | Boolean | True if a fire has been actively reported in this zone |
| fireReportedAt | Long | Timestamp when activeFire was set to true |
| campsiteScan.N/E/S/W | Float | Directional AI scores from 360° campsite scan |
| campsiteScan.verdict | String | Campsite safety verdict from 360° scan |
| campsiteScan.timestamp | Long | When the campsite scan was submitted |

# 7\. Features & Screens

## 7.1 Home Dashboard (DashboardScreen)

The home screen is a full-screen Google Map displaying all Firestore zone documents as colored circles over Bannerghatta National Park. It is the primary interface for rangers and responders.

*   **Top Info Bar:** Shows user name ('Forest Ranger'), current latitude/longitude, weather (temperature + condition from OpenWeatherMap), and online/offline status indicator
*   **Zone Circles:** Red (risk ≥ 0.7 or campfire banned), Orange (risk ≥ 0.4), Green (risk < 0.4) — each circle is ~600m radius centered on the zone's GPS coordinates
*   **Bannerghatta Polygon:** Green boundary polygon tracing the approximate extent of Bannerghatta National Park
*   **Zone Markers:** Tappable markers open a bottom sheet showing full zone details: risk score, confidence, contributors, campfire status, and campsite scan verdict if available
*   **FAB Buttons:** Camera (green) — opens CameraScreen; 360° Scan (blue) — opens CampsiteScanScreen; Report Fire (red) — triggers fire reporting flow
*   **Zone Count Badge:** Bottom-left shows total zones recorded and count of high-risk zones
*   **Emergency Escape Drawer:** Appears automatically when activeFire = true in any zone — shows evacuation direction in large red card

## 7.2 Camera Screen (CameraScreen)

Full-screen CameraX preview. On capture, MLScorer runs immediately on the captured bitmap, producing a risk score that is stored in Room DB before any network attempt. The score is displayed to the user after capture. Photos taken here appear in the Sync Queue awaiting connectivity.

## 7.3 360° Campsite Scan (CampsiteScanScreen)

The most novel user-facing feature. The screen shows a circular compass divided into 4 sectors (N, E, S, W). Each sector is colored grey (not yet captured), green (low risk), orange (medium), or red (high risk) based on the AI score of the photo taken in that direction.

*   The compass needle moves in real time based on phone orientation via SensorManager
*   User takes one photo per direction — sector fills with color once captured
*   Once all 4 sectors are captured, the worst-case score determines the campsite verdict
*   Verdict shown with animation: red card for NOT SAFE, amber for CAUTION, green for SAFE TO CAMP
*   'Submit to Firebase' button sends all 4 scores and verdict to Firestore under the current zone document
*   The zone's map circle will reflect the campsite scan verdict — even if the individual photo scores are low, a NOT SAFE verdict forces the zone to display red on the map

## 7.4 Risk Map (MapScreen)

A secondary list-based view of all Firestore zones sorted by risk score. Each zone card shows: zone ID, risk label, score, confidence, contributor count. Tapping a card expands it to show fire spread probabilities to all 8 neighboring zones, sorted from highest to lowest spread risk.

## 7.5 Sync Queue (SyncQueueScreen)

Shows all photos captured on the device, both synced and pending. Each card shows: thumbnail, capture timestamp, device risk score, and — after sync — the zone ID, trust-weighted zone score, confidence, and submission status ('New Submission' or 'Already Recorded'). Allows manual trigger of sync and deletion of synced records.

## 7.6 Settings (SettingsScreen)

User preferences: display name, notification preferences, sync frequency settings. Also shows app version, Firebase connection status, and number of pending unsynced photos.

# 8\. Setup & Installation

## 8.1 Prerequisites

| Requirement | Version / Details | Where to Get |
| --- | --- | --- |
| Android Studio | Ladybug or newer (2024+) | developer.android.com/studio |
| Android SDK | API 26 minimum, API 34 target | SDK Manager in Android Studio |
| Kotlin | 1.9+ | Bundled with Android Studio |
| Java | JDK 11 | Required for build tools |
| Firebase Project | With Firestore enabled (test mode) | console.firebase.google.com |
| Google Maps API Key | Android Maps SDK enabled | console.cloud.google.com |
| OpenWeatherMap API Key | Free tier sufficient | openweathermap.org |

## 8.2 Step-by-Step Setup

1.  Clone or extract the project. Open the ForestSnap/ folder in Android Studio (the folder containing build.gradle.kts — not the outer zip folder).
2.  Create a Firebase project at console.firebase.google.com. Add an Android app with package name com.example.forestsnap. Download google-services.json and place it at ForestSnap/app/google-services.json.
3.  Enable Firestore Database in your Firebase project. Start in Test Mode for development (allows all reads/writes without authentication).
4.  Get a Google Maps API key from console.cloud.google.com. Enable 'Maps SDK for Android'. Add the key to AndroidManifest.xml inside the <application> tag:

<meta-data android:name="com.google.android.geo.API\_KEY" android:value="YOUR\_KEY\_HERE"/>

1.  Get a free OpenWeatherMap API key from openweathermap.org. Add it to Constants.kt:

const val WEATHER\_API\_KEY = "your\_openweathermap\_key"

1.  Wait for Gradle sync to complete (check bottom status bar in Android Studio — should say 'Gradle sync finished' with no errors).
2.  Create an Android Virtual Device (AVD) via Device Manager, or connect a physical Android device with USB debugging enabled.
3.  Press the green Play button to build and run.

## 8.3 Required API Keys Summary

| API | Used In Stage | Where to Configure | Free Tier |
| --- | --- | --- | --- |
| Firebase / Firestore | Stages 1–4 | google-services.json (auto-configured) | Yes — generous limits |
| GPS (Android built-in) | Stages 1–4 | No key needed — AndroidManifest permissions only | Yes — always free |
| Google Maps SDK (Android) | Stage 3–4 | AndroidManifest.xml meta-data tag | 28,000 loads/month free |
| OpenWeatherMap | Stage 3 | Constants.kt — WEATHER_API_KEY | 1,000 calls/day free |
| Google Maps JS API (Web) | Stage 5 | Web dashboard HTML file | 28,000 loads/month free |
| TensorFlow Lite | Stages 1–4 | No key needed — runs on-device | Fully free, offline |

# 9\. Firebase Firestore Structure

Firestore is used as the cloud aggregation layer. All writes happen through FirebaseRepository.kt. The database has three top-level collections:

## 9.1 zones/{zoneId}

The primary collection. One document per ~1km grid cell around Bangalore/Bannerghatta. Zone IDs are computed as row\_col where row = int((lat − 12.0) × 100) and col = int((lon − 76.0) × 100). For example, a location at 12.88°N, 77.52°E maps to zone 88\_152.

**Campfire Status Logic:**

*   Zone score > 0.7 OR campsiteScan.verdict = 'NOT SAFE' → campfireBanned = true → red circle on map
*   Zone score > 0.4 → medium risk → orange circle
*   Zone score ≤ 0.4 AND no NOT SAFE verdict → low risk → green circle

## 9.2 submissions/{autoId}

One document per individual photo submission. Used to recompute trust-weighted zone scores as new contributors add data. Fields: snapId, zoneId, riskScore, gpsAccuracy, bearing, timestamp, trustWeight, latitude, longitude.

## 9.3 fireReports/{autoId}

One document per active fire report. Created when a user presses 'Report Forest Fire' and completes the 5-photo flow. The zone document is also updated with activeFire: true, which triggers the real-time emergency alert on all connected devices via the Firestore snapshot listener in FirebaseRepository.listenToZones().

# 10\. Build Stages (Development Roadmap)

The project was built incrementally in 5 stages. Each stage was a self-contained unit of work that compiled and ran before the next stage began. This section documents what was built in each stage for reference and reproducibility.

| Stage | Goal | Key Files Modified/Created | Status |
| --- | --- | --- | --- |
| Stage 1 | GPS + Location + Firebase Zone Storage | SyncSnapEntity (v2→v3), ForestDatabase, FirebaseRepository, CloudSyncWorker | Complete |
| Stage 2 | 360° Campsite Scan Feature | CampsiteScanScreen.kt, CampsiteScanViewModel.kt, FirebaseRepository.submitCampsiteScan() | Complete |
| Stage 3 | Google Maps + Zone Risk Display | DashboardScreen.kt (Google Map), DashboardViewModel.kt, MapRepository.kt | Complete |
| Stage 4 | Fire Reporting + Escape Route | DashboardScreen.kt (FABs + escape drawer), DashboardViewModel.reportActiveFire() | Complete |
| Stage 5 | Enhanced Web Dashboard | Standalone HTML/JS/CSS web app reading from Firestore | Pending |

## 10.1 Hard Rules (Agent / Developer Must Never Break)

*   Never modify NavGraph.kt unless explicitly adding a new stage-specified screen
*   Never modify ForestSnapApplication.kt
*   Never remove existing DAO queries — only add new ones
*   Always bump Room DB version when adding entity fields — always use fallbackToDestructiveMigration()
*   Never change the package name: com.example.forestsnap
*   Backend folder lives at project root alongside ForestSnap/ — not inside the Android module
*   Every stage must compile and run without errors before starting the next stage
*   Firebase / Firestore is the backend — not Flask, not AWS, not a local server
*   GPS coordinates must always be stored with every record — never omit lat/lon

# 11\. ForestSnap vs Existing Systems

| Feature / Capability | VIIRS/MODIS | Watchtower | Existing Apps | ForestSnap |
| --- | --- | --- | --- | --- |
| Detects risk BEFORE fire starts | No | Limited | No | Yes |
| Sub-canopy fuel load mapping | No | No | No | Yes |
| Works without internet | N/A | N/A | No | Yes |
| Multi-contributor trust weighting | No | No | No | Yes |
| Ground-level AI scoring | No | No | No | Yes |
| Compass-based 360° site scan | No | No | No | Yes |
| Fire spread direction prediction | No | No | No | Yes |
| Escape route recommendation | No | No | No | Yes |
| Real-time emergency alerts | 6–12 hrs delay | Manual radio | No | Yes (<1 min) |
| Campfire permission system | No | No | No | Yes |
| Cost to deploy | Millions (satellite) | High (infrastructure) | Low (app only) | Very low (Firebase free tier) |

# 12\. Patent Filing Guide

ForestSnap is eligible for a **provisional patent application** in India under the Patents Act 1970. A provisional application does not require a working prototype — only a written description of the invention. However, since ForestSnap has working code for all four patent claims, the application is significantly stronger than typical provisional filings.

## 12.1 Filing Details

| Item | Detail |
| --- | --- |
| Form to file | Form 1 (Application for Grant of Patent) + Form 2 (Provisional Specification) |
| Filing authority | Indian Patent Office — ipindia.gov.in → e-Filing |
| Fee (student/startup) | ₹1,750 (vs ₹8,750 for large entities) |
| Protection period | 12 months from provisional filing date |
| Complete specification deadline | 12 months from provisional filing — requires full claims + drawings |
| Recommended attorney cost | ₹15,000–50,000 for complete specification with professional claim drafting |

## 12.2 Critical Warning

**File BEFORE any public presentation, college submission, or GitHub upload.** Once the invention is in the public domain, you have a 12-month grace period in India to file — but filing before any disclosure is always the safest approach. The order must be: File Provisional Patent → Build Demo → Submit Project → Present at Viva.

## 12.3 How to Frame the Claims (Critical for Approval)

The single most important factor in patent grant is how the claims are written. The Indian Patent Office rejects software patents under Section 3(k) if they read as 'mathematical methods' or 'computer programs per se'. The key is to frame every claim as a technical system with concrete hardware components, not as an algorithm.

| Wrong Framing (Likely Rejected) | Correct Framing (Likely Accepted) |
| --- | --- |
| A method of calculating fire risk scores using AI | A system comprising mobile edge hardware performing TFLite inference offline to produce a fuel-load risk score at the moment of photographic capture |
| A formula for weighting GPS data from multiple users | A system wherein GPS-accuracy metadata from mobile hardware sensors is used to compute differential trust weights for aggregating zone confidence scores across multiple contributors |
| A mathematical model for fire spread prediction | A system comprising a geographic zone graph wherein zone confidence scores stored in a cloud database feed a directional spread probability computation across 8 neighboring grid cells |

# 13\. Known Limitations & Future Scope

## 13.1 Current Limitations

| Limitation | Impact | Mitigation Plan |
| --- | --- | --- |
| AI model trained on limited dataset (~200 photos per class) | May produce inaccurate scores in unfamiliar forest types | Collect labeled data from Bannerghatta via forest dept collaboration |
| Zone grid (~1km cells) may be too coarse for precise alerts | Two fires in the same zone could be 999m apart | Reduce grid cell size to ~100m by multiplying GPS by 1000 instead of 100 |
| No user authentication | Any device can submit fire reports or false alarms | Add Firebase Auth with ranger ID verification |
| Escape route is direction only — no actual path | Ranger must navigate the direction without a drawn path | Integrate Google Maps Directions API for turn-by-turn escape routing |
| Web dashboard (Stage 5) not yet built | No browser-based monitoring for park management | Build as standalone HTML/Firebase JS app — est. 1 week |
| Spread formula is simplified (score × 0.85) | Does not account for wind direction or slope | Integrate OpenWeatherMap wind data into spread coefficient |

## 13.2 Future Scope

*   Wind-adjusted spread model — integrate real-time wind direction and speed into the spread probability formula
*   Tree species classification — additional ML classifier to identify flammable vs fire-resistant tree species in photos, adjusting risk score accordingly
*   Historical risk heatmap — time-series visualization of how zone risk scores change over seasons
*   Integration with Forest Department alert system — SMS/push notification gateway to official forest department emergency contacts
*   Offline map tiles — download Bannerghatta map tiles for offline use so rangers can see zone circles even without internet
*   IoT sensor integration — accept risk data from remote temperature/humidity sensors in addition to phone-based photo submissions
*   Multi-language support — Kannada, Hindi, and Tamil interfaces for local forest department staff

# 14\. Contributing

This project was developed as part of a 6th semester mini-project at \[College Name\], Bangalore. Contributions, suggestions, and collaborations are welcome — especially from forestry researchers, ML engineers with experience in environmental datasets, and forest department officials who can provide labeled training data.

## 14.1 How to Contribute

1.  Fork the repository
2.  Create a feature branch: git checkout -b feature/your-feature-name
3.  Make your changes — ensure all 5 stage rules in Section 10.1 are followed
4.  Test end-to-end: take a real photo on device → verify Room DB write → verify Firebase sync → verify map update
5.  Submit a pull request with a clear description of what changed and why

## 14.2 Specific Contributions Needed

*   Labeled forest photo dataset for Bannerghatta / Karnataka forests — contact authors if you have access
*   Stage 5 web dashboard — HTML/CSS/JS developer with Firebase Firestore experience
*   Wind + slope integration into spread formula — environmental data engineer
*   Kannada UI translation — native speaker + Android localization experience

# 15\. Acknowledgements & License

ForestSnap was built using open-source technologies including TensorFlow Lite (Apache 2.0), Jetpack Compose (Apache 2.0), Firebase SDK (Apache 2.0), and Google Maps SDK for Android. The TFLite model was trained using Google Teachable Machine on photographs collected by the project team.

The Zone ID grid formula and the GPS-accuracy trust weighting formula are original contributions of this project and are the subject of provisional patent filing at the Indian Patent Office.

**License:** MIT License. See LICENSE file for full terms. Note: Patent claims described in Section 3 are reserved and may not be reproduced commercially without written permission from the authors.

**Contact:** \[Your Name / Team Name\] | \[College Name\], Bangalore | \[Email Address\]

_ForestSnap — Built with purpose. Filed with intent. Deployed for the forest._
