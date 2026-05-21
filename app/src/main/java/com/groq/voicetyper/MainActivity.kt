package com.groq.voicetyper

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

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
                    primary = Color(0xFF6200EE),
                    background = Color(0xFF0F0F12),
                    surface = Color(0xFF1E1E24),
                    onPrimary = Color.White
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onRequestPermission: () -> Unit) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    
    // States for checking status
    var hasMicPermission by remember { mutableStateOf(false) }
    var isKeyboardEnabled by remember { mutableStateOf(false) }
    var isKeyboardSelected by remember { mutableStateOf(false) }
    
    // Load and store API Key state
    var apiKeyInput by remember { mutableStateOf("") }
    var isKeySaved by remember { mutableStateOf(false) }
    var isPasswordVisible by remember { mutableStateOf(false) }

    // Periodically refresh/check statuses when activity gains focus
    LaunchedEffect(Unit) {
        apiKeyInput = SecurityUtils.getApiKey(context) ?: ""
        isKeySaved = apiKeyInput.isNotBlank()
        
        while (true) {
            hasMicPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            val imeManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val enabledMethods = imeManager.enabledInputMethodList
            val pkgName = context.packageName
            
            isKeyboardEnabled = enabledMethods.any { it.packageName == pkgName }
            
            val selectedImeId = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD
            ) ?: ""
            isKeyboardSelected = selectedImeId.contains(pkgName)
            
            delay(1500)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Title with Premium Gradient
        Text(
            text = "Groq Voice Typer",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            style = LocalTextStyle.current.copy(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF00F2FE), Color(0xFF4FACFE), Color(0xFFF355DA))
                )
            ),
            textAlign = TextAlign.Center
        )
        
        Text(
            text = "High speed voice typing directly in your apps",
            color = Color(0xFF8E8E9A),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )

        // API Key Input Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "1. Configure Groq API Key",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Requires a Groq API Key to perform local-to-cloud transcription securely.",
                    color = Color(0xFF8E8E9A),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { Text("Groq API Key (gsk_...)") },
                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                            Text(if (isPasswordVisible) "Hide" else "Show", color = Color(0xFF4FACFE))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6200EE),
                        unfocusedBorderColor = Color(0xFF3A3A40),
                        focusedLabelColor = Color(0xFF4FACFE)
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isKeySaved) {
                        Text(
                            text = "✓ Key saved securely",
                            color = Color(0xFF00E676),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    } else {
                        Text(
                            text = "Key not saved",
                            color = Color(0xFFFF5252),
                            fontSize = 13.sp
                        )
                    }

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
                            containerColor = Color(0xFF6200EE)
                        )
                    ) {
                        Text("Save")
                    }
                }
            }
        }

        // Microphone Permission Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "2. Microphone Access",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (hasMicPermission) "Permission is active" else "Needed to record your voice",
                        color = if (hasMicPermission) Color(0xFF00E676) else Color(0xFF8E8E9A),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Button(
                    onClick = onRequestPermission,
                    enabled = !hasMicPermission,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (hasMicPermission) Color(0xFF26262B) else Color(0xFF6200EE)
                    )
                ) {
                    Text(if (hasMicPermission) "Granted" else "Grant")
                }
            }
        }

        // Enable Keyboard Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "3. Enable Service",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isKeyboardEnabled) "Keyboard service is enabled" else "Turn on keyboard in settings",
                        color = if (isKeyboardEnabled) Color(0xFF00E676) else Color(0xFF8E8E9A),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                        context.startActivity(intent)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isKeyboardEnabled) Color(0xFF26262B) else Color(0xFF6200EE)
                    )
                ) {
                    Text(if (isKeyboardEnabled) "Enabled" else "Enable")
                }
            }
        }

        // Select Keyboard Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "4. Switch Default Keyboard",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isKeyboardSelected) "Default keyboard active" else "Make Groq keyboard default",
                        color = if (isKeyboardSelected) Color(0xFF00E676) else Color(0xFF8E8E9A),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Button(
                    onClick = {
                        val imeManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imeManager.showInputMethodPicker()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isKeyboardSelected) Color(0xFF26262B) else Color(0xFF6200EE)
                    )
                ) {
                    Text(if (isKeyboardSelected) "Active" else "Switch")
                }
            }
        }

        // Practice Text Field (Test Zone)
        if (isKeyboardEnabled) {
            Text(
                text = "Practice Area",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Start)
            )
            
            var testText by remember { mutableStateOf("") }
            OutlinedTextField(
                value = testText,
                onValueChange = { testText = it },
                placeholder = { Text("Tap here to test typing with your keyboard...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 32.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF4FACFE),
                    unfocusedBorderColor = Color(0xFF26262B)
                )
            )
        }
    }
}
