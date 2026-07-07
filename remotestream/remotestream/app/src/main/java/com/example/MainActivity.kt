package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import com.example.remote.ScreenCaptureService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.theme.*

class MainActivity : ComponentActivity() {

    private val mediaProjectionResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startScreenCaptureService(result.resultCode, result.data!!)
            isServiceActive.value = true
        } else {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            requestMediaProjection()
        } else {
            Toast.makeText(this, "Permissions are required", Toast.LENGTH_SHORT).show()
        }
    }

    private val isServiceActive = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainScreen(
                    isActive = isServiceActive.value,
                    onStartClick = { checkPermissionsAndStart() }
                )
            }
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            requestMediaProjection()
        }
    }

    private fun requestMediaProjection() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionResultLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startScreenCaptureService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = "START"
            putExtra("RESULT_CODE", resultCode)
            putExtra("DATA", data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}

@Composable
fun MainScreen(isActive: Boolean, onStartClick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var isAccessibilityEnabled by remember { mutableStateOf(false) }

    // Update accessibility state periodically or on resume
    DisposableEffect(Unit) {
        isAccessibilityEnabled = isAccessibilityServiceEnabled(context)
        onDispose { }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Background,
        bottomBar = { BottomNavBar() }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            HeaderSection(isActive = isActive)
            MainStatusCard(isActive = isActive, onStartClick = onStartClick)
            HardwareNetworkGrid()
            PowerSaveCard()
            AccessibilityCard(isEnabled = isAccessibilityEnabled) {
                context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
    }
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    return enabledServices.any { it.resolveInfo.serviceInfo.packageName == context.packageName }
}

@Composable
fun AccessibilityCard(isEnabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = CardDarkBg,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(if (isEnabled) Emerald500.copy(alpha = 0.2f) else CardOrangeBg.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = null,
                        tint = if (isEnabled) Emerald500 else CardOrangeIcon,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(text = "Remote Control", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(text = if (isEnabled) "Accessibility Active" else "Tap to enable Accessibility", color = Color.Gray, fontSize = 10.sp)
                }
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Emerald500,
                    uncheckedThumbColor = Color.Gray,
                    uncheckedTrackColor = Color.DarkGray
                )
            )
        }
    }
}

@Composable
fun HeaderSection(isActive: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "RemoteStream",
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isActive) {
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ), label = "pulse_alpha"
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Emerald500.copy(alpha = alpha), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Service Active", color = Emerald500, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                } else {
                    Box(modifier = Modifier.size(8.dp).background(Color.Gray, CircleShape))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Service Inactive", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        IconButton(
            onClick = { },
            modifier = Modifier
                .size(48.dp)
                .background(Color.White, RoundedCornerShape(16.dp))
                .border(1.dp, Color(0xFFF1F5F9), RoundedCornerShape(16.dp))
        ) {
            Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = TextPrimary)
        }
    }
}

@Composable
fun MainStatusCard(isActive: Boolean, onStartClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        color = CardBlueBg,
        onClick = onStartClick
    ) {
        Box(modifier = Modifier.padding(32.dp)) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DesktopWindows,
                        contentDescription = "Desktop",
                        tint = CardBlueIcon,
                        modifier = Modifier.size(40.dp)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (isActive) "Ready for Web Panel" else "Tap to Start Service",
                        color = CardBlueText,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Device: Android (${Build.VERSION.RELEASE})",
                        color = CardBlueText.copy(alpha = 0.7f),
                        fontSize = 14.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.5f), CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "ID: RS-8821-XQ9",
                        color = CardBlueIcon,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
fun HardwareNetworkGrid() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hardware Card
        Surface(
            modifier = Modifier.weight(1f).height(144.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color.White,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF1F5F9))
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(CardGreenBg, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Memory, contentDescription = null, tint = CardGreenIcon, modifier = Modifier.size(20.dp))
                }
                Column {
                    Text(text = "HARDWARE", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
                    Text(text = "GPU Encoding", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
            }
        }

        // Network Card
        Surface(
            modifier = Modifier.weight(1f).height(144.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color.White,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF1F5F9))
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(CardOrangeBg, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Wifi, contentDescription = null, tint = CardOrangeIcon, modifier = Modifier.size(20.dp))
                }
                Column {
                    Text(text = "NETWORK", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
                    Text(text = "WebSocket Live", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
            }
        }
    }
}

@Composable
fun PowerSaveCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = CardDarkBg
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Emerald500.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Sensors, contentDescription = null, tint = Emerald500, modifier = Modifier.size(20.dp))
                }
                Column {
                    Text(text = "Advanced Power Save", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(text = "Minimized background usage", color = Color.Gray, fontSize = 10.sp)
                }
            }
            Switch(
                checked = true,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Emerald500,
                    uncheckedThumbColor = Color.Gray,
                    uncheckedTrackColor = Color.DarkGray
                )
            )
        }
    }
}

@Composable
fun BottomNavBar() {
    Column(modifier = Modifier.fillMaxWidth().background(Background)) {
        Divider(color = Color(0xFFE1E2E9), thickness = 1.dp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomNavItem(icon = Icons.Rounded.CheckCircle, label = "Status", isSelected = true)
            BottomNavItem(icon = Icons.Outlined.List, label = "Logs", isSelected = false)
            BottomNavItem(icon = Icons.Outlined.Info, label = "About", isSelected = false)
        }
        Text(
            text = "Version 1.0.4 • Optimized for Android 10-16",
            color = Color.Gray,
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp, top = 8.dp)
        )
    }
}

@Composable
fun BottomNavItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, isSelected: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .background(
                    if (isSelected) CardBlueBg else Color.Transparent,
                    shape = CircleShape
                )
                .padding(horizontal = 20.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) CardBlueIcon else Color.Gray,
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = label,
            color = if (isSelected) CardBlueIcon else Color.Gray,
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

