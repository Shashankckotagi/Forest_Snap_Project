package com.example.forestsnap.features.campsite

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.forestsnap.features.dashboard.MLScorer
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executor
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun CampsiteScanScreen(
    navController: NavController,
    viewModel: CampsiteScanViewModel = viewModel()
) {
    val context        = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    // --- Permissions ---
    val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
    var hasPermissions by remember {
        mutableStateOf(requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms -> hasPermissions = perms.values.all { it } }

    LaunchedEffect(Unit) {
        if (!hasPermissions) permLauncher.launch(requiredPermissions)
    }

    // --- Compass bearing via SensorManager ---
    var bearing by remember { mutableStateOf(0f) }
    val accelValues = remember { FloatArray(3) }
    val magValues   = remember { FloatArray(3) }

    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accelSensor   = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magSensor     = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER   -> System.arraycopy(event.values, 0, accelValues, 0, 3)
                    Sensor.TYPE_MAGNETIC_FIELD  -> System.arraycopy(event.values, 0, magValues,  0, 3)
                }
                val rotMatrix   = FloatArray(9)
                val inclination = FloatArray(9)
                if (SensorManager.getRotationMatrix(rotMatrix, inclination, accelValues, magValues)) {
                    val orientation = FloatArray(3)
                    SensorManager.getOrientation(rotMatrix, orientation)
                    val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
                    bearing = (azimuth + 360f) % 360f
                }
            }
        }

        sensorManager.registerListener(listener, accelSensor,  SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(listener, magSensor,    SensorManager.SENSOR_DELAY_UI)
        onDispose { sensorManager.unregisterListener(listener) }
    }

    // --- Derived direction from bearing ---
    val currentDirection = bearingToDirection(bearing)

    // --- ViewModel state ---
    val sectorScores  by viewModel.sectorScores.collectAsState()
    val isComplete    by viewModel.isComplete.collectAsState()
    val verdict       by viewModel.verdict.collectAsState()
    val zoneId        by viewModel.currentZoneId.collectAsState()
    val currentLatLon by viewModel.currentLatLon.collectAsState()
    val isSubmitting  by viewModel.isSubmitting.collectAsState()
    val submitSuccess by viewModel.submitSuccess.collectAsState()

    // --- Camera state ---
    var showCamera  by remember { mutableStateOf(false) }
    val imageCapture = remember { ImageCapture.Builder().build() }
    val executor     = ContextCompat.getMainExecutor(context)

    if (!hasPermissions) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Camera & Location permissions required", textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { permLauncher.launch(requiredPermissions) }) { Text("Grant Permissions") }
            }
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        // ─── CAMERA MODE ────────────────────────────────────
        if (showCamera) {
            // Full-screen camera preview
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        val provider = future.get()
                        val preview  = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        try {
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageCapture
                            )
                        } catch (e: Exception) { e.printStackTrace() }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            // Direction label overlay
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color(0xCC1B5E20))
                    .padding(12.dp)
            ) {
                Text(
                    text      = "📷  Capturing: ${directionLabel(currentDirection)}",
                    color     = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize  = 18.sp,
                    modifier  = Modifier.align(Alignment.Center)
                )
            }

            // Capture button
            FloatingActionButton(
                onClick = {
                    val file = File(context.cacheDir, "campsite_${currentDirection}_${System.currentTimeMillis()}.jpg")
                    val opts = ImageCapture.OutputFileOptions.Builder(file).build()
                    imageCapture.takePicture(opts, executor, object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                            val score  = if (bitmap != null) MLScorer(context).scoreImage(bitmap) else 0f
                            viewModel.addSectorScore(currentDirection, file.absolutePath, score)
                            showCamera = false
                        }
                        override fun onError(exc: ImageCaptureException) {
                            exc.printStackTrace()
                            showCamera = false
                        }
                    })
                },
                modifier           = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 40.dp)
                    .size(72.dp),
                containerColor     = Color(0xFF2E7D32)
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = "Capture", tint = Color.White, modifier = Modifier.size(32.dp))
            }

            // Cancel button
            TextButton(
                onClick  = { showCamera = false },
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)
            ) { Text("Cancel", color = Color.White) }

        // ─── COMPASS MODE ────────────────────────────────────
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                // Header
                Text(
                    text       = "🌲 Campsite Scan",
                    style      = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color      = Color(0xFF1B5E20)
                )
                Text(
                    text  = "Zone: $zoneId  |  $currentLatLon",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )

                Spacer(Modifier.height(24.dp))

                // Compass Canvas
                Box(contentAlignment = Alignment.Center) {
                    CampsiteCompass(
                        sectorScores = sectorScores,
                        bearing      = bearing,
                        modifier     = Modifier.size(280.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Direction labels row
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    listOf("N", "E", "S", "W").forEach { dir ->
                        SectorChip(dir = dir, score = sectorScores[dir], isActive = currentDirection == dir)
                    }
                }

                Spacer(Modifier.height(24.dp))

                // Current direction info card
                val dirFilled = sectorScores[currentDirection] != null
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors   = CardDefaults.cardColors(
                        containerColor = if (dirFilled) Color(0xFFE8F5E9) else Color(0xFFE3F2FD)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text     = if (dirFilled) "✅" else "📍",
                            fontSize = 28.sp
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text       = "Facing: ${directionLabel(currentDirection)}  (${bearing.toInt()}°)",
                                fontWeight = FontWeight.Bold,
                                style      = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text  = if (dirFilled) "This direction is captured" else "Ready to capture",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Take Photo button
                Button(
                    onClick  = { showCamera = true },
                    enabled  = !dirFilled && !isComplete,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                    shape    = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(
                        if (dirFilled) "${directionLabel(currentDirection)} ✅ Captured"
                        else          "📷  Capture ${directionLabel(currentDirection)}"
                    )
                }

                Spacer(Modifier.height(24.dp))

                // Verdict card — shown when all 4 sectors done
                AnimatedVisibility(
                    visible = isComplete,
                    enter   = fadeIn() + slideInVertically(initialOffsetY = { it })
                ) {
                    Column {
                        VerdictCard(verdict = verdict)
                        Spacer(Modifier.height(12.dp))
                        if (submitSuccess) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors   = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Submitted to Firebase ✓", fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                                }
                            }
                        } else {
                            Button(
                                onClick  = { viewModel.submitCampsiteScan() },
                                enabled  = !isSubmitting,
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                                shape    = RoundedCornerShape(12.dp)
                            ) {
                                if (isSubmitting) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                                } else {
                                    Text("Submit to Firebase")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Compass Canvas ──────────────────────────────────────────────────────────

@Composable
fun CampsiteCompass(
    sectorScores: Map<String, Float?>,
    bearing: Float,
    modifier: Modifier = Modifier
) {
    val directions = listOf(
        "N" to -135f,  // canvas startAngle so North is at top
        "E" to -45f,
        "S" to 45f,
        "W" to 135f
    )

    val sectorColors = directions.map { (dir, _) ->
        val score = sectorScores[dir]
        when {
            score == null  -> Color(0xFFE0E0E0)      // empty — grey
            score >= 0.7f  -> Color(0xFFE53935)      // high — red
            score >= 0.4f  -> Color(0xFFFB8C00)      // medium — orange
            else           -> Color(0xFF43A047)      // low — green
        }
    }

    Canvas(modifier = modifier) {
        val cx     = size.width / 2f
        val cy     = size.height / 2f
        val radius = (size.width / 2f) - 12f

        // Draw sectors
        directions.forEachIndexed { i, (_, startAngle) ->
            drawArc(
                color      = sectorColors[i],
                startAngle = startAngle,
                sweepAngle = 90f,
                useCenter  = true,
                topLeft    = Offset(cx - radius, cy - radius),
                size       = Size(radius * 2, radius * 2)
            )
            // Sector border
            drawArc(
                color      = Color.White,
                startAngle = startAngle,
                sweepAngle = 90f,
                useCenter  = true,
                topLeft    = Offset(cx - radius, cy - radius),
                size       = Size(radius * 2, radius * 2),
                style      = Stroke(width = 4f)
            )
        }

        // Outer ring
        drawCircle(color = Color(0xFF37474F), radius = radius, center = Offset(cx, cy), style = Stroke(width = 6f))

        // Compass needle (bearing)
        val needleLen  = radius * 0.6f
        val angleRad   = Math.toRadians((bearing - 90.0)).toFloat()
        val needleTip  = Offset(cx + needleLen * cos(angleRad), cy + needleLen * sin(angleRad))
        val tailRad    = Math.toRadians((bearing + 90.0)).toFloat()
        val tailTip    = Offset(cx + (needleLen * 0.3f) * cos(tailRad), cy + (needleLen * 0.3f) * sin(tailRad))

        drawLine(Color(0xFFE53935), Offset(cx, cy), needleTip,  strokeWidth = 6f, cap = StrokeCap.Round)
        drawLine(Color(0xFF37474F), Offset(cx, cy), tailTip,    strokeWidth = 6f, cap = StrokeCap.Round)
        drawCircle(Color.White, radius = 10f, center = Offset(cx, cy))
        drawCircle(Color(0xFF37474F), radius = 10f, center = Offset(cx, cy), style = Stroke(width = 3f))
    }
}

// ─── Sector Chip ─────────────────────────────────────────────────────────────

@Composable
fun SectorChip(dir: String, score: Float?, isActive: Boolean) {
    val label = directionLabel(dir)
    val bgColor = when {
        score == null  -> if (isActive) Color(0xFFBBDEFB) else Color(0xFFF5F5F5)
        score >= 0.7f  -> Color(0xFFFFEBEE)
        score >= 0.4f  -> Color(0xFFFFF3E0)
        else           -> Color(0xFFE8F5E9)
    }
    val textColor = when {
        score == null  -> if (isActive) Color(0xFF1565C0) else Color.Gray
        score >= 0.7f  -> Color(0xFFB71C1C)
        score >= 0.4f  -> Color(0xFFE65100)
        else           -> Color(0xFF1B5E20)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text       = dir,
            fontWeight = FontWeight.ExtraBold,
            fontSize   = 18.sp,
            color      = textColor
        )
        Text(
            text  = if (score != null) "%.2f".format(score) else "—",
            style = MaterialTheme.typography.labelSmall,
            color = textColor
        )
    }
}

// ─── Verdict Card ────────────────────────────────────────────────────────────

@Composable
fun VerdictCard(verdict: String) {
    val (bg, accent, emoji) = when {
        verdict.startsWith("NOT SAFE")  -> Triple(Color(0xFFFFEBEE), Color(0xFFB71C1C), "🔥")
        verdict.startsWith("CAUTION")   -> Triple(Color(0xFFFFF8E1), Color(0xFFE65100), "⚠️")
        else                            -> Triple(Color(0xFFE8F5E9), Color(0xFF1B5E20), "✅")
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = bg),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(
            modifier            = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(emoji, fontSize = 40.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                text       = "Campsite Safety Verdict",
                style      = MaterialTheme.typography.titleMedium,
                color      = Color.Gray
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text       = verdict,
                style      = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color      = accent,
                textAlign  = TextAlign.Center
            )
        }
    }
}
