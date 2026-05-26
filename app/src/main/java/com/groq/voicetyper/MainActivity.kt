package com.groq.voicetyper

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import com.groq.voicetyper.offline.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, "Microphone permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Microphone permission is required for voice typing", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFDDB7FF),
                    background = Color(0xFF0C0C11),
                    surface = Color(0xFF131319),
                    onPrimary = Color(0xFF0E0E14)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SetupScreen(
                        onRequestPermission = {
                            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    )
                }
            }
        }
    }
}

// Reusable Modifier for Frosted Glass Cards
fun Modifier.glassCard(shape: RoundedCornerShape = RoundedCornerShape(24.dp)): Modifier = this
    .background(
        color = Color(0x0CFFFFFF), // 5% opacity white translucent overlay
        shape = shape
    )
    .border(
        width = 1.dp,
        brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.12f),
                Color.White.copy(alpha = 0.02f)
            )
        ),
        shape = shape
    )

// Reusable Custom Canvas Checkmark
@Composable
fun CanvasCheckmark(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.25f, size.height * 0.5f)
            lineTo(size.width * 0.45f, size.height * 0.72f)
            lineTo(size.width * 0.78f, size.height * 0.3f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = 2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

// Glowing Pulsing Orb Composable
@Composable
fun FluenceOrb(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    // Wave line height animations for a live equalizer effect
    val waveTransition = rememberInfiniteTransition(label = "wave_lines")
    val line1Height by waveTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "line1"
    )
    val line2Height by waveTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "line2"
    )
    val line3Height by waveTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "line3"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Outer Glowing Radial Aura (Amethyst glow overlaying AMOLED black)
        Canvas(modifier = Modifier.size(96.dp)) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFA855F7).copy(alpha = 0.45f * pulseAlpha),
                        Color(0xFFA855F7).copy(alpha = 0.05f * pulseAlpha),
                        Color.Transparent
                    )
                ),
                radius = size.width / 2 * pulseScale
            )
        }

        // Inner Gradient Orb Container (Frosted Amethyst Glass effect)
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF7C3AED).copy(alpha = 0.4f), // 40% Amethyst
                            Color(0xFFC084FC).copy(alpha = 0.15f) // 15% Soft Lavender
                        )
                    ),
                    shape = CircleShape
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.25f),
                            Color(0xFFA855F7).copy(alpha = 0.05f)
                        )
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            // Live Technical Soundwave Line Indicator
            Canvas(modifier = Modifier.size(20.dp)) {
                val lineStroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                
                // Line 1
                val h1Start = size.height * (0.5f - line1Height / 2)
                val h1End = size.height * (0.5f + line1Height / 2)
                drawLine(
                    color = Color.White,
                    start = Offset(size.width * 0.25f, h1Start),
                    end = Offset(size.width * 0.25f, h1End),
                    strokeWidth = lineStroke.width,
                    cap = lineStroke.cap
                )
                
                // Line 2
                val h2Start = size.height * (0.5f - line2Height / 2)
                val h2End = size.height * (0.5f + line2Height / 2)
                drawLine(
                    color = Color.White,
                    start = Offset(size.width * 0.5f, h2Start),
                    end = Offset(size.width * 0.5f, h2End),
                    strokeWidth = lineStroke.width,
                    cap = lineStroke.cap
                )
                
                // Line 3
                val h3Start = size.height * (0.5f - line3Height / 2)
                val h3End = size.height * (0.5f + line3Height / 2)
                drawLine(
                    color = Color.White,
                    start = Offset(size.width * 0.75f, h3Start),
                    end = Offset(size.width * 0.75f, h3End),
                    strokeWidth = lineStroke.width,
                    cap = lineStroke.cap
                )
            }
        }
    }
}

// StepIcon Composable drawing the specific custom icons from Stitch
@Composable
fun StepIcon(stepNumber: Int, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        when (stepNumber) {
            1 -> { // 4-point star / API Sparkle
                val path = Path().apply {
                    moveTo(w * 0.5f, h * 0.15f)
                    quadraticBezierTo(w * 0.5f, h * 0.5f, w * 0.85f, h * 0.5f)
                    quadraticBezierTo(w * 0.5f, h * 0.5f, w * 0.5f, h * 0.85f)
                    quadraticBezierTo(w * 0.5f, h * 0.5f, w * 0.15f, h * 0.5f)
                    quadraticBezierTo(w * 0.5f, h * 0.5f, w * 0.5f, h * 0.15f)
                    close()
                }
                drawPath(path = path, color = color)
            }
            2 -> { // Microphone
                val micPath = Path().apply {
                    moveTo(w * 0.38f, h * 0.25f)
                    lineTo(w * 0.62f, h * 0.25f)
                    arcTo(Rect(w * 0.38f, h * 0.15f, w * 0.62f, h * 0.45f), 180f, 180f, false)
                    lineTo(w * 0.62f, h * 0.55f)
                    arcTo(Rect(w * 0.38f, h * 0.45f, w * 0.62f, h * 0.65f), 0f, 180f, false)
                    close()
                }
                drawPath(path = micPath, color = color)
                val standPath = Path().apply {
                    moveTo(w * 0.28f, h * 0.48f)
                    arcTo(Rect(w * 0.28f, h * 0.38f, w * 0.72f, h * 0.78f), 180f, -180f, true)
                    moveTo(w * 0.5f, h * 0.78f)
                    lineTo(w * 0.5f, h * 0.9f)
                    moveTo(w * 0.38f, h * 0.9f)
                    lineTo(w * 0.62f, h * 0.9f)
                }
                drawPath(path = standPath, color = color, style = Stroke(width = 1.5.dp.toPx()))
            }
            3 -> { // Enable Service / Settings (custom slider / toggle switch icon)
                val trackPath = Path().apply {
                    moveTo(w * 0.2f, h * 0.4f)
                    lineTo(w * 0.8f, h * 0.4f)
                    arcTo(Rect(w * 0.65f, h * 0.2f, w * 0.85f, h * 0.6f), 270f, 180f, false)
                    lineTo(w * 0.2f, h * 0.6f)
                    arcTo(Rect(w * 0.15f, h * 0.2f, w * 0.35f, h * 0.6f), 90f, 180f, false)
                    close()
                }
                drawPath(path = trackPath, color = color.copy(alpha = 0.5f), style = Stroke(width = 1.5.dp.toPx()))
                drawCircle(color = color, radius = w * 0.15f, center = Offset(w * 0.7f, h * 0.4f))
            }
            4 -> { // Keyboard
                val path = Path().apply {
                    moveTo(w * 0.1f, h * 0.25f)
                    lineTo(w * 0.9f, h * 0.25f)
                    lineTo(w * 0.9f, h * 0.75f)
                    lineTo(w * 0.1f, h * 0.75f)
                    close()
                    moveTo(w * 0.35f, h * 0.62f)
                    lineTo(w * 0.65f, h * 0.62f)
                    moveTo(w * 0.22f, h * 0.38f); lineTo(w * 0.3f, h * 0.38f)
                    moveTo(w * 0.38f, h * 0.38f); lineTo(w * 0.46f, h * 0.38f)
                    moveTo(w * 0.54f, h * 0.38f); lineTo(w * 0.62f, h * 0.38f)
                    moveTo(w * 0.7f, h * 0.38f); lineTo(w * 0.78f, h * 0.38f)
                    moveTo(w * 0.26f, h * 0.5f); lineTo(w * 0.34f, h * 0.5f)
                    moveTo(w * 0.42f, h * 0.5f); lineTo(w * 0.5f, h * 0.5f)
                    moveTo(w * 0.58f, h * 0.5f); lineTo(w * 0.66f, h * 0.5f)
                    moveTo(w * 0.74f, h * 0.5f); lineTo(w * 0.82f, h * 0.5f)
                }
                drawPath(path = path, color = color, style = Stroke(width = 1.5.dp.toPx()))
            }
            5 -> { // Overlay / Display Over Other Apps (Overlap)
                val path1 = Path().apply {
                    moveTo(w * 0.15f, h * 0.35f)
                    lineTo(w * 0.65f, h * 0.35f)
                    lineTo(w * 0.65f, h * 0.75f)
                    lineTo(w * 0.15f, h * 0.75f)
                    close()
                }
                drawPath(path = path1, color = color.copy(alpha = 0.6f), style = Stroke(width = 1.5.dp.toPx()))
                val path2 = Path().apply {
                    moveTo(w * 0.35f, h * 0.15f)
                    lineTo(w * 0.85f, h * 0.15f)
                    lineTo(w * 0.85f, h * 0.55f)
                    lineTo(w * 0.35f, h * 0.55f)
                    close()
                }
                drawPath(path = path2, color = color, style = Stroke(width = 1.5.dp.toPx()))
            }
            6 -> { // Accessibility
                drawCircle(color = color, radius = w * 0.1f, center = Offset(w * 0.5f, h * 0.25f))
                val bodyPath = Path().apply {
                    moveTo(w * 0.5f, h * 0.35f)
                    lineTo(w * 0.5f, h * 0.65f)
                    moveTo(w * 0.2f, h * 0.45f)
                    lineTo(w * 0.8f, h * 0.45f)
                    moveTo(w * 0.5f, h * 0.65f)
                    lineTo(w * 0.32f, h * 0.85f)
                    moveTo(w * 0.5f, h * 0.65f)
                    lineTo(w * 0.68f, h * 0.85f)
                }
                drawPath(path = bodyPath, color = color, style = Stroke(width = 1.5.dp.toPx()))
            }
            7 -> { // Battery Optimization
                val batteryOutline = Path().apply {
                    moveTo(w * 0.28f, h * 0.22f)
                    lineTo(w * 0.72f, h * 0.22f)
                    lineTo(w * 0.72f, h * 0.88f)
                    lineTo(w * 0.28f, h * 0.88f)
                    close()
                    moveTo(w * 0.42f, h * 0.15f)
                    lineTo(w * 0.58f, h * 0.15f)
                    lineTo(w * 0.58f, h * 0.22f)
                    lineTo(w * 0.42f, h * 0.22f)
                    close()
                }
                drawPath(path = batteryOutline, color = color, style = Stroke(width = 1.5.dp.toPx()))
                val boltPath = Path().apply {
                    moveTo(w * 0.52f, h * 0.35f)
                    lineTo(w * 0.38f, h * 0.55f)
                    lineTo(w * 0.48f, h * 0.55f)
                    lineTo(w * 0.44f, h * 0.75f)
                    lineTo(w * 0.58f, h * 0.50f)
                    lineTo(w * 0.48f, h * 0.50f)
                    close()
                }
                drawPath(path = boltPath, color = color)
            }
        }
    }
}

// Single Timeline Onboarding Step Composable
@Composable
fun OnboardingStepCard(
    stepNumber: Int,
    title: String,
    description: String,
    isCompleted: Boolean,
    isActive: Boolean,
    isLastStep: Boolean,
    activeLineBrush: Brush,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {}
) {
    Row(
        modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Left Column: Custom Checkmark/Step Circle and Vertical Line
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(32.dp)
        ) {
            Box(
                modifier = Modifier.size(32.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isActive && !isCompleted) {
                    val infiniteTransition = rememberInfiniteTransition(label = "step_glow")
                    val glowScale by infiniteTransition.animateFloat(
                        initialValue = 1.0f,
                        targetValue = 1.4f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(2000, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "scale"
                    )
                    Canvas(modifier = Modifier.size(48.dp).graphicsLayer { scaleX = glowScale; scaleY = glowScale }) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(Color(0xFFDDB7FF).copy(alpha = 0.25f), Color.Transparent)
                            ),
                            radius = size.width / 2
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            color = when {
                                isActive || isCompleted -> Color(0xFFDDB7FF)
                                else -> Color(0xFF131319)
                            },
                            shape = CircleShape
                        )
                        .run {
                            if (!isActive && !isCompleted) {
                                this.border(
                                    width = 1.5.dp,
                                    color = Color(0xFF4D4354),
                                    shape = CircleShape
                                )
                            } else this
                        },
                    contentAlignment = Alignment.Center
                ) {
                    StepIcon(
                        stepNumber = stepNumber,
                        color = if (isActive || isCompleted) Color(0xFF490080) else Color(0xFF988D9F),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            if (!isLastStep) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .weight(1f)
                        .background(
                            brush = if (isCompleted || isActive) activeLineBrush else SolidColor(Color(0xFF2A2930))
                        )
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Right Column: Frosted Glass Panel Container
        Box(
            modifier = Modifier
                .weight(1f)
                .glassCard()
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                if (isActive && !isCompleted) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(4.dp)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color(0xFFDDB7FF), Color(0xFFB76DFF))
                                ),
                                shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
                            )
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(
                            start = if (isActive && !isCompleted) 16.dp else 20.dp,
                            top = 20.dp,
                            end = 20.dp,
                            bottom = 20.dp
                        )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "STEP $stepNumber",
                                color = if (isActive) Color(0xFFDDB7FF) else Color(0xFFCFC2D6).copy(alpha = 0.4f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = title,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        if (isActive && !isCompleted) {
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFFB76DFF).copy(alpha = 0.15f), RoundedCornerShape(100.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "Active",
                                    color = Color(0xFFDDB7FF),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Text(
                        text = description,
                        color = Color(0xFFCFC2D6).copy(alpha = 0.8f),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )

                    content()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onRequestPermission: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val coroutineScope = rememberCoroutineScope()

    // Setup Status States
    var hasMicPermission by remember { mutableStateOf(false) }
    var isKeyboardEnabled by remember { mutableStateOf(false) }
    var isKeyboardSelected by remember { mutableStateOf(false) }

    // API Key inputs
    var apiKeyInput by remember { mutableStateOf("") }
    var isKeySaved by remember { mutableStateOf(false) }
    var isPasswordVisible by remember { mutableStateOf(false) }

    // Beta Features Settings
    var isFloatingBubbleEnabled by remember { mutableStateOf(false) }
    var hasOverlayPermission by remember { mutableStateOf(false) }
    var isAccessibilityEnabled by remember { mutableStateOf(false) }
    var isBatteryOptimizationsIgnored by remember { mutableStateOf(false) }

    // Offline Mode State variables
    var isOfflineModeEnabled by remember { mutableStateOf(false) }
    var downloadState by remember { mutableStateOf(ModelAssetManager.DownloadState.IDLE) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadPercent by remember { mutableStateOf(0) }
    var isModelDownloaded by remember { mutableStateOf(false) }
    var modelSizeMB by remember { mutableStateOf(0L) }

    // Entrance Animation Control
    val splashProgressAnim = remember { Animatable(0f) }
    var isSplashActive by remember { mutableStateOf(true) }

    // Infinite transitions for background glows and button shimmers
    val infiniteTransition = rememberInfiniteTransition(label = "fluence_effects")
    
    val backgroundScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(10000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bg_scale"
    )

    val buttonShift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "btn_shimmer"
    )

    // Animated Shifting Button Gradient Brush
    val shiftingButtonBrush = Brush.linearGradient(
        colors = listOf(Color(0xFFB76DFF), Color(0xFF00DCE6), Color(0xFFB76DFF)),
        start = Offset(buttonShift, 0f),
        end = Offset(buttonShift + 300f, 300f),
        tileMode = TileMode.Repeated
    )

    // Active segments timeline vertical line brush
    val activeLineBrush = Brush.verticalGradient(
        colors = listOf(Color(0xFFDDB7FF), Color(0xFF34343B))
    )

    // Frosted Grain Noise Texture Brush
    val noiseBrush = remember {
        val width = 128
        val height = 128
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val random = java.util.Random()
        for (i in pixels.indices) {
            val noise = random.nextInt(256)
            pixels[i] = android.graphics.Color.argb(
                13, // 5% opacity (~0.05 * 255)
                noise,
                noise,
                noise
            )
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val imageBitmap = bitmap.asImageBitmap()
        ShaderBrush(ImageShader(image = imageBitmap, tileModeX = TileMode.Repeated, tileModeY = TileMode.Repeated))
    }

    // Load initial states and periodically update status parameters
    LaunchedEffect(Unit) {
        val initialInfo = withContext(Dispatchers.IO) {
            val key = SecurityUtils.getApiKey(context) ?: ""
            val bubbleEnabled = context.getSharedPreferences("fluence_prefs", Context.MODE_PRIVATE)
                .getBoolean("floating_bubble_enabled", false)
            val offlineEnabled = OfflinePreferences.isOfflineModeEnabled(context)
            val modelDownloaded = ModelAssetManager.isModelReadySync(context)
            val sizeBytes = ModelAssetManager.getModelSizeOnDisk(context)
            InitialInfo(key, bubbleEnabled, offlineEnabled, modelDownloaded, sizeBytes)
        }

        apiKeyInput = initialInfo.key
        isKeySaved = apiKeyInput.isNotBlank()
        isFloatingBubbleEnabled = initialInfo.bubbleEnabled
        isOfflineModeEnabled = initialInfo.offlineEnabled
        isModelDownloaded = initialInfo.modelDownloaded
        modelSizeMB = initialInfo.sizeBytes / (1024L * 1024L)

        // Monitor download progress
        launch {
            ModelAssetManager.progress.collect { prog ->
                downloadState = prog.state
                if (prog.totalBytes > 0) {
                    downloadProgress = prog.bytesDownloaded.toFloat() / prog.totalBytes.toFloat()
                    downloadPercent = (downloadProgress * 100).toInt()
                } else {
                    downloadProgress = 0f
                    downloadPercent = 0
                }
                
                if (prog.state == ModelAssetManager.DownloadState.COMPLETED) {
                    isModelDownloaded = true
                    modelSizeMB = ModelAssetManager.getModelSizeOnDisk(context) / (1024L * 1024L)
                } else if (prog.state == ModelAssetManager.DownloadState.IDLE) {
                    launch {
                        isModelDownloaded = ModelAssetManager.isModelReady(context)
                        modelSizeMB = ModelAssetManager.getModelSizeOnDisk(context) / (1024L * 1024L)
                    }
                }
            }
        }

        // Hold splash screen briefly, then smoothly slide logo up
        delay(1200)
        splashProgressAnim.animateTo(
            targetValue = 1f,
            animationSpec = tween(1000, easing = FastOutSlowInEasing)
        )
        isSplashActive = false
    }

    // Status sync loop running in background (Optimized to offload to Dispatchers.IO)
    LaunchedEffect(Unit) {
        while (true) {
            val results = withContext(Dispatchers.IO) {
                val micPerm = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                val imeManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                val enabledMethods = imeManager.enabledInputMethodList
                val pkgName = context.packageName

                val keyboardEnabled = enabledMethods.any { it.packageName == pkgName }

                val selectedImeId = Settings.Secure.getString(
                    context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD
                ) ?: ""
                val keyboardSelected = selectedImeId.contains(pkgName)

                val overlayPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Settings.canDrawOverlays(context)
                } else {
                    true
                }

                val accessibilityEnabled = isAccessibilityServiceEnabled(context, FluenceAccessibilityService::class.java)

                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val batteryOptimizationsIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    powerManager.isIgnoringBatteryOptimizations(pkgName)
                } else {
                    true
                }

                StatusResults(
                    hasMicPermission = micPerm,
                    isKeyboardEnabled = keyboardEnabled,
                    isKeyboardSelected = keyboardSelected,
                    hasOverlayPermission = overlayPerm,
                    isAccessibilityEnabled = accessibilityEnabled,
                    isBatteryOptimizationsIgnored = batteryOptimizationsIgnored
                )
            }

            hasMicPermission = results.hasMicPermission
            isKeyboardEnabled = results.isKeyboardEnabled
            isKeyboardSelected = results.isKeyboardSelected
            hasOverlayPermission = results.hasOverlayPermission
            isAccessibilityEnabled = results.isAccessibilityEnabled
            isBatteryOptimizationsIgnored = results.isBatteryOptimizationsIgnored

            delay(1500)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0C0C11))) {
        // 1. Glowing Ambient Orbs Layer
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Violet Orb (Top Right)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFA855F7).copy(alpha = 0.15f), Color.Transparent),
                    center = Offset(size.width * 1.0f, size.height * 0.0f),
                    radius = size.width * 0.8f * backgroundScale
                ),
                center = Offset(size.width * 1.0f, size.height * 0.0f),
                radius = size.width * 0.8f * backgroundScale
            )

            // Cyan Orb (Bottom Left)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF00F2FE).copy(alpha = 0.12f), Color.Transparent),
                    center = Offset(size.width * 0.0f, size.height * 1.0f),
                    radius = size.width * 0.8f * (2f - backgroundScale)
                ),
                center = Offset(size.width * 0.0f, size.height * 1.0f),
                radius = size.width * 0.8f * (2f - backgroundScale)
            )
        }

        // 1.5. Frosted Grain Texture Overlay Layer
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(noiseBrush)
        )

        // 2. Interactive Setup / Splash Scrollable Area
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header transition spacer (pushes logo down when splashProgress is 0)
            Spacer(modifier = Modifier.height(((1f - splashProgressAnim.value) * (screenHeight.value * 0.32f)).dp))
            
            // Header spacing buffer once splash has finished
            Spacer(modifier = Modifier.height((64 * splashProgressAnim.value).dp))

            // Splash Logo & Name (visible when splashProgressAnim < 0.95f)
            if (splashProgressAnim.value < 0.95f) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.graphicsLayer {
                        alpha = 1f - splashProgressAnim.value
                    }
                ) {
                    FluenceOrb(modifier = Modifier.padding(bottom = 12.dp))
                    
                    Text(
                        text = "fluence",
                        fontSize = (24 + (12 * (1f - splashProgressAnim.value))).sp,
                        fontWeight = FontWeight.Bold,
                        style = LocalTextStyle.current.copy(
                            brush = Brush.linearGradient(
                                colors = listOf(Color(0xFFB76DFF), Color(0xFF00DCE6))
                            )
                        ),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // Setup Wizard Title (fades in when onboarding is active)
                Text(
                    text = "Setup Wizard",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(bottom = 2.dp)
                        .graphicsLayer {
                            alpha = splashProgressAnim.value
                        }
                )
            }

            // Tagline / Subtitle layout
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (splashProgressAnim.value < 0.95f) {
                    Text(
                        text = "Crystallize your cognition at the speed of thought.",
                        color = Color(0xFFCFC2D6).copy(alpha = 1f - splashProgressAnim.value),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                } else {
                    Text(
                        text = "Crystalize your cognition at the speed of thought",
                        color = Color(0xFF988D9F),
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.graphicsLayer {
                            alpha = splashProgressAnim.value
                        }
                    )
                }
            }

            // Onboarding Setup Cards Column (displays as logo floats up)
            if (splashProgressAnim.value > 0.05f) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 32.dp)
                        .graphicsLayer {
                            alpha = splashProgressAnim.value
                            translationY = (1f - splashProgressAnim.value) * 200f
                        }
                ) {
                    // Calculated Step States
                    val step1Completed = isKeySaved
                    val step2Completed = hasMicPermission
                    val step3Completed = isKeyboardEnabled
                    val step4Completed = isKeyboardSelected

                    val step1Active = !step1Completed
                    val step2Active = step1Completed && !step2Completed
                    val step3Active = step2Completed && !step3Completed
                    val step4Active = step3Completed && !step4Completed

                    Column(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Step 1: Groq API Key Setup
                        OnboardingStepCard(
                            stepNumber = 1,
                            title = "API Configuration",
                            description = "Requires a Groq API Key to perform local-to-cloud transcription securely.",
                            isCompleted = step1Completed,
                            isActive = step1Active,
                            isLastStep = false,
                            activeLineBrush = activeLineBrush
                        ) {
                            OutlinedTextField(
                                value = apiKeyInput,
                                onValueChange = { apiKeyInput = it },
                                label = { Text("Groq API Key (gsk_...)") },
                                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    TextButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                        Text(if (isPasswordVisible) "Hide" else "Show", color = Color(0xFFDDB7FF))
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFFDDB7FF),
                                    unfocusedBorderColor = Color(0x33FFFFFF),
                                    focusedLabelColor = Color(0xFFDDB7FF)
                                )
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isKeySaved) "✓ Key saved securely" else "Key not saved",
                                    color = if (isKeySaved) Color(0xFF00F2FE) else Color(0xFFFF5252),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )

                                Button(
                                    onClick = {
                                        if (apiKeyInput.isNotBlank()) {
                                            SecurityUtils.saveApiKey(context, apiKeyInput)
                                            isKeySaved = true
                                            Toast.makeText(context, "API Key Saved", Toast.LENGTH_SHORT).show()
                                        } else {
                                            SecurityUtils.clearApiKey(context)
                                            isKeySaved = false
                                            Toast.makeText(context, "API Key Cleared", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.Transparent
                                    ),
                                    contentPadding = PaddingValues(),
                                    modifier = Modifier.height(38.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .background(shiftingButtonBrush, RoundedCornerShape(100.dp))
                                            .padding(horizontal = 20.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("Save", color = Color(0xFF0E0E14), fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        // Step 2: Microphone Access
                        OnboardingStepCard(
                            stepNumber = 2,
                            title = "Microphone Access",
                            description = "Grant audio recording access to enable speech-to-text dictation.",
                            isCompleted = step2Completed,
                            isActive = step2Active,
                            isLastStep = false,
                            activeLineBrush = activeLineBrush
                        ) {
                            Button(
                                onClick = onRequestPermission,
                                enabled = !step2Completed,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (step2Completed) Color(0x1AFFFFFF) else Color(0xFFB76DFF),
                                    disabledContainerColor = Color(0x1AFFFFFF)
                                ),
                                shape = RoundedCornerShape(100.dp)
                            ) {
                                Text(
                                    text = if (step2Completed) "Granted" else "Grant Permission",
                                    color = if (step2Completed) Color(0xFF8E8E9A) else Color(0xFF0E0E14),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Step 3: Enable Keyboard Service
                        OnboardingStepCard(
                            stepNumber = 3,
                            title = "Enable Service",
                            description = "Activate the Fluence keyboard under your system input settings.",
                            isCompleted = step3Completed,
                            isActive = step3Active,
                            isLastStep = false,
                            activeLineBrush = activeLineBrush
                        ) {
                            Button(
                                onClick = {
                                    val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                                    context.startActivity(intent)
                                },
                                enabled = !step3Completed,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (step3Completed) Color(0x1AFFFFFF) else Color(0xFFB76DFF),
                                    disabledContainerColor = Color(0x1AFFFFFF)
                                ),
                                shape = RoundedCornerShape(100.dp)
                            ) {
                                Text(
                                    text = if (step3Completed) "Enabled" else "Enable Keyboard",
                                    color = if (step3Completed) Color(0xFF8E8E9A) else Color(0xFF0E0E14),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Step 4: Switch Default Keyboard
                        OnboardingStepCard(
                            stepNumber = 4,
                            title = "Switch Default",
                            description = "Set Fluence as your active keyboard method to type in any app.",
                            isCompleted = step4Completed,
                            isActive = step4Active,
                            isLastStep = !isFloatingBubbleEnabled,
                            activeLineBrush = activeLineBrush
                        ) {
                            Button(
                                onClick = {
                                    val imeManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                    imeManager.showInputMethodPicker()
                                },
                                enabled = !step4Completed,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (step4Completed) Color(0x1AFFFFFF) else Color(0xFFB76DFF),
                                    disabledContainerColor = Color(0x1AFFFFFF)
                                ),
                                shape = RoundedCornerShape(100.dp)
                            ) {
                                Text(
                                    text = if (step4Completed) "Active" else "Switch Keyboard",
                                    color = if (step4Completed) Color(0xFF8E8E9A) else Color(0xFF0E0E14),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Conditional Timeline Steps for Floating Bubble Mode (Beta)
                        if (isFloatingBubbleEnabled) {
                            val step5Completed = hasOverlayPermission
                            val step6Completed = isAccessibilityEnabled
                            val step7Completed = isBatteryOptimizationsIgnored

                            val step5Active = !step5Completed
                            val step6Active = step5Completed && !step6Completed
                            val step7Active = step5Completed && step6Completed && !step7Completed

                            // Step 5: Overlay Permission (Display over other apps)
                            OnboardingStepCard(
                                stepNumber = 5,
                                title = "Display Over Other Apps",
                                description = "Allows the Fluence speech bubble to hover on top of your keyboard.",
                                isCompleted = step5Completed,
                                isActive = step5Active,
                                isLastStep = false,
                                activeLineBrush = activeLineBrush
                            ) {
                                Button(
                                    onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                            val intent = Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                            context.startActivity(intent)
                                        }
                                    },
                                    enabled = !step5Completed,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (step5Completed) Color(0x1AFFFFFF) else Color(0xFFB76DFF),
                                        disabledContainerColor = Color(0x1AFFFFFF)
                                    ),
                                    shape = RoundedCornerShape(100.dp)
                                ) {
                                    Text(
                                        text = if (step5Completed) "Overlay Allowed" else "Grant Overlay",
                                        color = if (step5Completed) Color(0xFF8E8E9A) else Color(0xFF0E0E14),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // Step 6: Accessibility Service Binding
                            OnboardingStepCard(
                                stepNumber = 6,
                                title = "Accessibility Service",
                                description = "Enables Fluence to read input fields and paste text automatically.",
                                isCompleted = step6Completed,
                                isActive = step6Active,
                                isLastStep = false,
                                activeLineBrush = activeLineBrush
                            ) {
                                Button(
                                    onClick = {
                                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        context.startActivity(intent)
                                    },
                                    enabled = !step6Completed,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (step6Completed) Color(0x1AFFFFFF) else Color(0xFFB76DFF),
                                        disabledContainerColor = Color(0x1AFFFFFF)
                                    ),
                                    shape = RoundedCornerShape(100.dp)
                                ) {
                                    Text(
                                        text = if (step6Completed) "Service Active" else "Enable Accessibility",
                                        color = if (step6Completed) Color(0xFF8E8E9A) else Color(0xFF0E0E14),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // Step 7: Battery Optimization Exception
                            OnboardingStepCard(
                                stepNumber = 7,
                                title = "Battery Optimization",
                                description = "Exempts Fluence from background constraints to prevent service terminations.",
                                isCompleted = step7Completed,
                                isActive = step7Active,
                                isLastStep = true,
                                activeLineBrush = activeLineBrush
                            ) {
                                Button(
                                    onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                            }
                                            try {
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                                context.startActivity(fallbackIntent)
                                            }
                                        }
                                    },
                                    enabled = !step7Completed,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (step7Completed) Color(0x1AFFFFFF) else Color(0xFFB76DFF),
                                        disabledContainerColor = Color(0x1AFFFFFF)
                                    ),
                                    shape = RoundedCornerShape(100.dp)
                                ) {
                                    Text(
                                        text = if (step7Completed) "Unrestricted" else "Grant Exception",
                                        color = if (step7Completed) Color(0xFF8E8E9A) else Color(0xFF0E0E14),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Floating Bubble Mode (Beta) Settings Toggle Pane
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxWidth().glassCard().padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Floating Bubble Mode (Beta)",
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Dictate via a floating on-screen bubble without switching keyboards.",
                                        color = Color(0xFFCFC2D6).copy(alpha = 0.8f),
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }

                                Switch(
                                    checked = isFloatingBubbleEnabled,
                                    onCheckedChange = { isChecked ->
                                        isFloatingBubbleEnabled = isChecked
                                        context.getSharedPreferences("fluence_prefs", Context.MODE_PRIVATE)
                                            .edit()
                                            .putBoolean("floating_bubble_enabled", isChecked)
                                            .apply()
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFFB76DFF),
                                        uncheckedThumbColor = Color(0xFF8E8E9A),
                                        uncheckedTrackColor = Color(0xFF1E1E24)
                                    )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Offline Mode settings card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxWidth().glassCard().padding(20.dp)) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Offline Transcription",
                                            color = Color.White,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Dictate without internet using on-device AI. English only.",
                                            color = Color(0xFFCFC2D6).copy(alpha = 0.8f),
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                    Switch(
                                        checked = isOfflineModeEnabled,
                                        onCheckedChange = { isChecked ->
                                            if (isChecked) {
                                                if (isModelDownloaded) {
                                                    isOfflineModeEnabled = true
                                                    OfflinePreferences.setOfflineModeEnabled(context, true)
                                                } else {
                                                    coroutineScope.launch {
                                                        val result = ModelAssetManager.downloadModel(context)
                                                        if (result.isSuccess) {
                                                            isOfflineModeEnabled = true
                                                            OfflinePreferences.setOfflineModeEnabled(context, true)
                                                        } else {
                                                            isOfflineModeEnabled = false
                                                            OfflinePreferences.setOfflineModeEnabled(context, false)
                                                            val err = result.exceptionOrNull()?.localizedMessage ?: "Unknown error"
                                                            if (downloadState != ModelAssetManager.DownloadState.CANCELLED) {
                                                                Toast.makeText(context, "Download failed: $err", Toast.LENGTH_LONG).show()
                                                            }
                                                        }
                                                    }
                                                }
                                            } else {
                                                isOfflineModeEnabled = false
                                                OfflinePreferences.setOfflineModeEnabled(context, false)
                                            }
                                        },
                                        enabled = downloadState != ModelAssetManager.DownloadState.DOWNLOADING && downloadState != ModelAssetManager.DownloadState.VERIFYING,
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = Color(0xFF00F5D4),
                                            uncheckedThumbColor = Color(0xFF8E8E9A),
                                            uncheckedTrackColor = Color(0xFF1E1E24)
                                        )
                                    )
                                }

                                // Download progress UI
                                AnimatedVisibility(visible = downloadState == ModelAssetManager.DownloadState.DOWNLOADING || downloadState == ModelAssetManager.DownloadState.VERIFYING) {
                                    Column(modifier = Modifier.padding(top = 12.dp)) {
                                        LinearProgressIndicator(
                                            progress = downloadProgress,
                                            modifier = Modifier.fillMaxWidth().height(4.dp),
                                            color = Color(0xFF00F5D4),
                                            trackColor = Color(0xFF1E1E24)
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            val statusLabel = if (downloadState == ModelAssetManager.DownloadState.VERIFYING) "Verifying model..." else "Downloading model..."
                                            Text(statusLabel, color = Color(0xFFCFC2D6), fontSize = 12.sp)
                                            Text("${downloadPercent}%", color = Color(0xFF00F5D4), fontSize = 12.sp)
                                        }
                                        TextButton(
                                            onClick = { ModelAssetManager.cancelDownload() },
                                            modifier = Modifier.padding(top = 4.dp)
                                        ) {
                                            Text("Cancel", color = Color(0xFFFF5252), fontSize = 12.sp)
                                        }
                                    }
                                }

                                // Model ready status UI
                                AnimatedVisibility(visible = isModelDownloaded && downloadState != ModelAssetManager.DownloadState.DOWNLOADING && downloadState != ModelAssetManager.DownloadState.VERIFYING) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "✓ Model ready (${modelSizeMB}MB)",
                                            color = Color(0xFF00F2FE),
                                            fontSize = 12.sp
                                        )
                                        TextButton(onClick = {
                                            coroutineScope.launch {
                                                ModelAssetManager.deleteModel(context)
                                                isModelDownloaded = false
                                                isOfflineModeEnabled = false
                                                OfflinePreferences.setOfflineModeEnabled(context, false)
                                            }
                                        }) {
                                            Text("Delete Model", color = Color(0xFFFF5252), fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Practice Testing Area
                    if (isKeyboardEnabled) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Text(
                                text = "PRACTICE AREA",
                                color = Color(0xFF00DCE6),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp
                            )
                        }

                        var testText by remember { mutableStateOf("") }
                        OutlinedTextField(
                            value = testText,
                            onValueChange = { testText = it },
                            placeholder = {
                                Text(
                                    "Tap here to test typing with your keyboard...",
                                    color = Color(0xFFCFC2D6).copy(alpha = 0.5f)
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 32.dp)
                                .glassCard(RoundedCornerShape(16.dp)),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFDDB7FF),
                                unfocusedBorderColor = Color.Transparent,
                                errorBorderColor = Color.Transparent,
                                focusedLabelColor = Color(0xFFDDB7FF),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                    }
                }
            }
        }

        // 3. Top Sticky Header Bar (fades in once splash transitions away)
        AnimatedVisibility(
            visible = splashProgressAnim.value > 0.05f,
            enter = fadeIn(animationSpec = tween(500)),
            exit = fadeOut(animationSpec = tween(500)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(Color(0xE60C0C11)) // Blends seamlessly with deep slate background, removing visual border line
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Pulsing Dotted Circle (Aura style matching Stitch blur_on)
                    val headerPulseTransition = rememberInfiniteTransition(label = "header_dot_pulse")
                    val headerDotAlpha by headerPulseTransition.animateFloat(
                        initialValue = 0.4f,
                        targetValue = 1.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1200, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "dot_alpha"
                    )
                    Canvas(
                        modifier = Modifier
                            .size(28.dp)
                            .graphicsLayer { alpha = headerDotAlpha }
                    ) {
                        val center = Offset(size.width / 2, size.height / 2)
                        val outerRadius = 10.dp.toPx()
                        val innerRadius = 5.dp.toPx()
                        val dotRadius = 1.5.dp.toPx()

                        // 12 outer dots arranged in a circle
                        for (i in 0 until 12) {
                            val angle = i * (2 * Math.PI / 12)
                            val x = center.x + outerRadius * kotlin.math.cos(angle).toFloat()
                            val y = center.y + outerRadius * kotlin.math.sin(angle).toFloat()
                            drawCircle(
                                color = Color(0xFFDDB7FF),
                                radius = dotRadius,
                                center = Offset(x, y)
                            )
                        }

                        // 6 inner dots arranged in a circle
                        for (i in 0 until 6) {
                            val angle = i * (2 * Math.PI / 6) + (Math.PI / 6).toFloat()
                            val x = center.x + innerRadius * kotlin.math.cos(angle).toFloat()
                            val y = center.y + innerRadius * kotlin.math.sin(angle).toFloat()
                            drawCircle(
                                color = Color(0xFFDDB7FF),
                                radius = dotRadius,
                                center = Offset(x, y)
                            )
                        }

                        // 1 center dot
                        drawCircle(
                            color = Color(0xFFDDB7FF),
                            radius = dotRadius,
                            center = center
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "fluence",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        style = LocalTextStyle.current.copy(
                            brush = Brush.linearGradient(
                                colors = listOf(Color(0xFFDDB7FF), Color(0xFF00DCE6))
                            )
                        )
                    )
                }
            }
        }
    }
}

// Robust Helper checking if custom accessibility service is active
fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<out android.accessibilityservice.AccessibilityService>): Boolean {
    val expectedComponentName = android.content.ComponentName(context, serviceClass)
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServices)
    while (colonSplitter.hasNext()) {
        val componentNameString = colonSplitter.next()
        val enabledService = android.content.ComponentName.unflattenFromString(componentNameString)
        if (enabledService != null && enabledService == expectedComponentName) {
            return true
        }
    }
    return false
}

private data class StatusResults(
    val hasMicPermission: Boolean,
    val isKeyboardEnabled: Boolean,
    val isKeyboardSelected: Boolean,
    val hasOverlayPermission: Boolean,
    val isAccessibilityEnabled: Boolean,
    val isBatteryOptimizationsIgnored: Boolean
)

private data class InitialInfo(
    val key: String,
    val bubbleEnabled: Boolean,
    val offlineEnabled: Boolean,
    val modelDownloaded: Boolean,
    val sizeBytes: Long
)
