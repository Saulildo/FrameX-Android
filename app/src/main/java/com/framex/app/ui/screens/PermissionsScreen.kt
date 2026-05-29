package com.framex.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.alpha
import android.content.Intent
import android.provider.Settings
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.framex.app.core.root.RootManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver

@HiltViewModel
class PermissionsViewModel @Inject constructor(
    private val rootManager: RootManager
) : ViewModel() {
    val isRootAvailable = rootManager.isRootAvailable
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Triggers the su grant prompt (or re-verifies an existing grant). */
    fun requestRoot() {
        viewModelScope.launch { rootManager.refresh() }
    }

    fun refreshRootState() {
        viewModelScope.launch { rootManager.refresh() }
    }
}

@Composable
fun PermissionsScreen(
    onNavigateBack: () -> Unit,
    viewModel: PermissionsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val isRootAvailable by viewModel.isRootAvailable.collectAsState()
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    
    fun checkUsageStats(): Boolean {
        val appOps = context.getSystemService(android.content.Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        } else {
            appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        }
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }
    
    var hasUsageStatsPermission by remember { mutableStateOf(checkUsageStats()) }

    val powerManager = remember {
        context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
    }
    var hasBatteryOptDisabled by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }

    // Re-check permissions on resume
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = Settings.canDrawOverlays(context)
                hasUsageStatsPermission = checkUsageStats()
                hasBatteryOptDisabled = powerManager.isIgnoringBatteryOptimizations(context.packageName)
                viewModel.refreshRootState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Spacer(modifier = Modifier.weight(1f))
                Text("System Status", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.width(48.dp)) // Optical centering
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
            ) {
                // Intro
                Text("Setup FrameX", style = MaterialTheme.typography.titleLarge.copy(fontSize = 28.sp), color = Color.White)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "FrameX needs root and a few system permissions to monitor performance metrics like FPS, GPU timings, thermal stats, and power usage.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Root Access Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                        .padding(20.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("#", color = Color.White, fontWeight = FontWeight.Bold) // su prompt
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Root Access", color = Color.White, fontWeight = FontWeight.Bold)
                                Row(
                                    modifier = Modifier.clip(CircleShape)
                                        .background(if (isRootAvailable) Color(0xFF22C55E).copy(0.1f) else Color.Red.copy(0.1f))
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(if (isRootAvailable) Color(0xFF22C55E) else Color.Red))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        if (isRootAvailable) "GRANTED" else "NOT GRANTED",
                                        color = if (isRootAvailable) Color(0xFF22C55E) else Color.Red,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "FrameX uses a root (su) shell to read system telemetry and hook the GPU for per-frame timings. Approve the superuser prompt when asked.",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.requestRoot() },
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            shape = CircleShape,
                            enabled = !isRootAvailable,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRootAvailable) Color(0xFF22C55E) else MaterialTheme.colorScheme.primary,
                                disabledContainerColor = Color(0xFF22C55E).copy(alpha = 0.5f),
                                disabledContentColor = Color.White
                            )
                        ) {
                            Text(if (isRootAvailable) "Authorized" else "Grant Root", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text("REQUIRED PERMISSIONS", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp, start = 8.dp))

                // Permissions List
                // 1. Overlay
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(0.05f)).border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(12.dp)).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(0.1f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Layers, contentDescription = null, tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Overlay Permission", color = Color.White, fontWeight = FontWeight.Bold)
                        Text("Required for the FPS counter overlay", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    }
                    if (hasOverlayPermission) {
                        Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF10B981).copy(0.1f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(20.dp))
                        }
                    } else {
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                                context.startActivity(intent)
                            }, 
                            modifier = Modifier.height(36.dp), 
                            shape = CircleShape, 
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("Grant", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 2. Usage Access
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(0.05f)).border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(12.dp)).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(0.1f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.BarChart, contentDescription = null, tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Usage Access", color = Color.White, fontWeight = FontWeight.Bold)
                        Text("Read app-specific performance data", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                            context.startActivity(intent)
                        }, 
                        modifier = Modifier.height(36.dp), 
                        shape = CircleShape, 
                        colors = ButtonDefaults.buttonColors(containerColor = if (hasUsageStatsPermission) Color(0xFF10B981) else MaterialTheme.colorScheme.primary)
                    ) {
                        Text(if (hasUsageStatsPermission) "Granted" else "Grant", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 3. Battery Optimization
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(0.05f)).border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(12.dp)).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(0.1f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.BatteryFull, contentDescription = null, tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Battery Optimization", color = Color.White, fontWeight = FontWeight.Bold)
                        Text("Prevents OS from killing overlay", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    }
                    if (hasBatteryOptDisabled) {
                        Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF10B981).copy(0.1f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(20.dp))
                        }
                    } else {
                        Button(
                            onClick = {
                                val pkg = context.packageName
                                val intent = Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    android.net.Uri.parse("package:$pkg")
                                )
                                context.startActivity(intent)
                            },
                            modifier = Modifier.height(36.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("Disable", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Explainer
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(0.05f)).border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(12.dp)).padding(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("Why are these needed?", color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "FrameX operates as a high-privilege tool. Without overlay access, we cannot draw the HUD. Without usage stats, we cannot identify which game is running. Root access lets us read low-level GPU/system telemetry and hook the rendering API.",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(100.dp))
            }
        }
        
        // Bottom Action Placeholder
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.95f))
                .padding(24.dp)
        ) {
            val isAllReady = isRootAvailable && hasOverlayPermission
            Button(
                onClick = onNavigateBack,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isAllReady) MaterialTheme.colorScheme.primary else Color.White.copy(0.1f), 
                    contentColor = if (isAllReady) Color.White else Color.Gray
                ),
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("Return to Dashboard", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(8.dp))
                if (!isAllReady) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                } else {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

