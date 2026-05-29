package com.framex.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.framex.app.hud.FloatingWindowService
import com.framex.app.overlay.OverlayService
import com.framex.app.ui.components.PrimaryButton
import com.framex.app.ui.components.QuickActionButton
import com.framex.app.ui.components.SectionCard

@Composable
fun DashboardScreen(
    onNavigateToAppearance: () -> Unit,
    onNavigateToOverlayCustomization: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    onNavigateToAbout: () -> Unit,
    viewModel: PermissionsViewModel = androidx.hilt.navigation.compose.hiltViewModel()
) {
    val isRootAvailable by viewModel.isRootAvailable.collectAsState()
    val isHudRunning by FloatingWindowService.isRunning.collectAsState()
    val isLegacyOverlayRunning by OverlayService.isRunning.collectAsState()
    val isOverlayRunning = isHudRunning || isLegacyOverlayRunning
    val context = LocalContext.current

    // Re-check overlay draw permission on every resume (user may grant/revoke in Settings).
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Both must be true before the overlay can be started.
    val allPermissionsReady = hasOverlayPermission && isRootAvailable
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(
                    painter = painterResource(id = com.framex.app.R.mipmap.ic_launcher),
                    contentDescription = "Logo",
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "FrameX",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
            }
            IconButton(
                onClick = onNavigateToAbout,
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
            ) {
                Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings", tint = Color.LightGray)
            }
        }

        // Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            // Hero Status Card
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column {
                        Text(
                            text = "OVERLAY STATUS",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                        Text(
                            text = if (isOverlayRunning) "Monitoring Active" else "Ready to Monitor",
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp),
                            color = Color.White
                        )
                    }
                    Box(
                        modifier = Modifier
                            .background(if (isOverlayRunning) Color(0xFF22C55E).copy(alpha = 0.1f) else Color.Gray.copy(0.1f), CircleShape)
                            .border(1.dp, if (isOverlayRunning) Color(0xFF22C55E).copy(alpha = 0.2f) else Color.Gray.copy(0.2f), CircleShape)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(8.dp).background(if (isOverlayRunning) Color(0xFF22C55E) else Color.Gray, CircleShape))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = if (isOverlayRunning) "ACTIVE" else "INACTIVE", style = MaterialTheme.typography.labelSmall, color = if (isOverlayRunning) Color(0xFF22C55E) else Color.Gray)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                val context = LocalContext.current
                
                if (isOverlayRunning) {
                    Button(
                        onClick = {
                            if (isHudRunning) FloatingWindowService.stop(context)
                            if (isLegacyOverlayRunning) {
                                context.startService(
                                    Intent(context, OverlayService::class.java).apply {
                                        action = OverlayService.ACTION_STOP
                                    },
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(0.2f), contentColor = Color.Red),
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Text("Stop Metal HUD", fontWeight = FontWeight.Bold)
                    }
                } else {
                    if (allPermissionsReady) {
                        PrimaryButton(
                            text = "Start Metal HUD",
                            onClick = {
                                if (isLegacyOverlayRunning) {
                                    context.startService(
                                        Intent(context, OverlayService::class.java).apply {
                                            action = OverlayService.ACTION_STOP
                                        },
                                    )
                                }
                                FloatingWindowService.start(context)
                            },
                            icon = { Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(28.dp)) }
                        )
                    } else {
                        // One or more required permissions are missing — guide the user to fix them.
                        val missing = buildList {
                            if (!hasOverlayPermission) add("Overlay permission")
                            if (!isRootAvailable) add("Root access")
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Missing: ${missing.joinToString(" · ")}",
                                color = Color(0xFFFBBF24),
                                fontSize = 11.sp,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Button(
                                onClick = onNavigateToPermissions,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFBBF24).copy(alpha = 0.12f),
                                    contentColor = Color(0xFFFBBF24)
                                ),
                                shape = CircleShape,
                                modifier = Modifier.fillMaxWidth().height(56.dp)
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Complete Setup First", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Grid Actions
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                QuickActionButton(
                    title = "Metal HUD",
                    subtitle = "Size, opacity, graph",
                    iconContainerColor = Color(0xFF6366F1).copy(alpha = 0.1f),
                    iconContentColor = Color(0xFF818CF8),
                    onClick = onNavigateToOverlayCustomization,
                    modifier = Modifier.weight(1f),
                    icon = { Icon(Icons.Default.DashboardCustomize, null) }
                )
                QuickActionButton(
                    title = "Theme",
                    subtitle = "Colors, Opacity, Size",
                    iconContainerColor = Color(0xFFF59E0B).copy(alpha = 0.1f),
                    iconContentColor = Color(0xFFFBBF24),
                    onClick = onNavigateToAppearance,
                    modifier = Modifier.weight(1f),
                    icon = { Icon(Icons.Default.Palette, null) }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            QuickActionButton(
                title = "Root",
                subtitle = if (isRootAvailable) "Access Granted" else "Not Granted",
                iconContainerColor = if (isRootAvailable) Color(0xFF3B82F6).copy(alpha = 0.1f) else Color.Red.copy(0.1f),
                iconContentColor = if (isRootAvailable) Color(0xFF60A5FA) else Color.Red,
                onClick = onNavigateToPermissions,
                modifier = Modifier.fillMaxWidth(),
                icon = { Text("su", color = if (isRootAvailable) Color(0xFF60A5FA) else Color.Red, fontWeight = FontWeight.Bold) }
            )

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
