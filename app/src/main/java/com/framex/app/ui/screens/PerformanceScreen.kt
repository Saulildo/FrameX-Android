package com.framex.app.ui.screens

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.framex.app.gaming.AppInfo
import com.framex.app.gaming.GamingModeEngine
import com.framex.app.gaming.GamingModeService
import com.framex.app.gaming.GamingModeState
import com.framex.app.repository.SettingsRepository
import com.framex.app.shizuku.ShizukuManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

@HiltViewModel
class PerformanceViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val gamingModeEngine: GamingModeEngine,
    private val shizukuManager: ShizukuManager,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val gamingModeState = gamingModeEngine.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GamingModeState.Idle)

    val isShizukuAvailable = shizukuManager.isShizukuAvailable
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val hasShizukuPermission = shizukuManager.hasPermission
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val whitelist = settingsRepository.gamingModeWhitelist
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _userApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val userApps = _userApps.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _googleApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val googleApps = _googleApps.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        loadUserApps()
    }

    fun loadUserApps() {
        viewModelScope.launch {
            _userApps.value = withContext(Dispatchers.IO) {
                gamingModeEngine.getInstalledUserApps()
            }
            _googleApps.value = withContext(Dispatchers.IO) {
                gamingModeEngine.getGoogleAppsForWhitelist()
            }
        }
    }

    fun toggleWhitelist(packageName: String) {
        settingsRepository.toggleGamingWhitelistApp(packageName)
    }

    fun enableGamingMode(context: Context) {
        viewModelScope.launch {
            val currentWhitelist = settingsRepository.gamingModeWhitelist.value
            gamingModeEngine.enableGamingMode(currentWhitelist)
            if (gamingModeEngine.state.value == GamingModeState.Active) {
                context.startForegroundService(Intent(context, GamingModeService::class.java))
            }
        }
    }

    fun disableGamingMode(context: Context) {
        viewModelScope.launch {
            gamingModeEngine.disableGamingMode()
            context.startService(
                Intent(context, GamingModeService::class.java).apply {
                    action = GamingModeService.ACTION_STOP
                }
            )
        }
    }

    val safeToSuspendList: List<String> get() = gamingModeEngine.SAFE_TO_SUSPEND
    val googleSafeToSuspendList: List<String> get() = gamingModeEngine.GOOGLE_SAFE_TO_SUSPEND
    val gamingDaemonsList: List<String> get() = gamingModeEngine.GAMING_DAEMONS
}

// ---------------------------------------------------------------------------
// Composable helpers
// ---------------------------------------------------------------------------

@Composable
private fun RequirementRow(
    label: String,
    description: String,
    satisfied: Boolean,
    onAction: (() -> Unit)? = null,
    actionLabel: String = "Grant"
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(0.05f))
            .border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (satisfied) Color(0xFF22C55E).copy(0.15f)
                    else MaterialTheme.colorScheme.primary.copy(0.1f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (satisfied) Icons.Default.Check else Icons.Default.Lock,
                contentDescription = null,
                tint = if (satisfied) Color(0xFF22C55E) else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(description, color = Color.Gray, fontSize = 11.sp)
        }
        if (!satisfied && onAction != null) {
            Button(
                onClick = onAction,
                modifier = Modifier.height(32.dp),
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(actionLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AppWhitelistRow(
    app: AppInfo,
    isWhitelisted: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar: first two letters of the label
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = app.label.take(2).uppercase(),
                color = Color.White.copy(0.7f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(app.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                app.packageName,
                color = Color.Gray,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = isWhitelisted,
            onCheckedChange = { onToggle() },
            modifier = Modifier.graphicsLayer { scaleX = 0.85f; scaleY = 0.85f },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF22C55E)
            )
        )
    }
}

// ---------------------------------------------------------------------------
// Main screen
// ---------------------------------------------------------------------------

@Composable
fun PerformanceScreen(
    onNavigateBack: () -> Unit,
    viewModel: PerformanceViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val gamingState by viewModel.gamingModeState.collectAsState()
    val isShizukuAvailable by viewModel.isShizukuAvailable.collectAsState()
    val hasShizukuPermission by viewModel.hasShizukuPermission.collectAsState()
    val whitelist by viewModel.whitelist.collectAsState()
    val userApps by viewModel.userApps.collectAsState()
    val googleApps by viewModel.googleApps.collectAsState()

    val nm = remember { context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }

    var hasDndAccess by remember { mutableStateOf(nm.isNotificationPolicyAccessGranted) }
    var hasNotifListenerAccess by remember {
        mutableStateOf(
            android.provider.Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            )?.contains(context.packageName) == true
        )
    }

    // Re-check on every resume
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasDndAccess = nm.isNotificationPolicyAccessGranted
                hasNotifListenerAccess = android.provider.Settings.Secure.getString(
                    context.contentResolver, "enabled_notification_listeners"
                )?.contains(context.packageName) == true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val shizukuReady = isShizukuAvailable && hasShizukuPermission
    val canActivate = shizukuReady

    val isActive = gamingState is GamingModeState.Active
    val isBusy = gamingState is GamingModeState.Enabling || gamingState is GamingModeState.Disabling

    // Color accents
    val activeColor = Color(0xFF22C55E)
    val inactiveColor = Color.Gray
    val primaryRed = MaterialTheme.colorScheme.primary

    // Animated progress for the progress bar
    val progressTarget = when (val s = gamingState) {
        is GamingModeState.Enabling -> s.progress
        is GamingModeState.Disabling -> 0.5f
        else -> 0f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = progressTarget,
        animationSpec = tween(300),
        label = "progress"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ---- Header -------------------------------------------------------
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "Performance",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.width(48.dp))
            }
        }

        // ---- Hero Gaming Mode card ----------------------------------------
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isActive) activeColor.copy(0.25f) else Color.White.copy(0.05f)
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    // Status row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column {
                            Text(
                                "GAMING MODE",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            AnimatedContent(
                                targetState = gamingState,
                                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                                label = "stateLabel"
                            ) { state ->
                                Text(
                                    text = when (state) {
                                        is GamingModeState.Idle -> "Inactive"
                                        is GamingModeState.Enabling -> "Activating…"
                                        is GamingModeState.Active -> "Active"
                                        is GamingModeState.Disabling -> "Restoring…"
                                        is GamingModeState.Error -> "Error"
                                    },
                                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp),
                                    color = Color.White
                                )
                            }
                        }
                        // Status pill
                        Box(
                            modifier = Modifier
                                .background(
                                    color = when {
                                        isActive -> activeColor.copy(0.1f)
                                        gamingState is GamingModeState.Error -> primaryRed.copy(0.1f)
                                        else -> Color.Gray.copy(0.1f)
                                    },
                                    shape = CircleShape
                                )
                                .border(
                                    1.dp,
                                    color = when {
                                        isActive -> activeColor.copy(0.25f)
                                        gamingState is GamingModeState.Error -> primaryRed.copy(0.25f)
                                        else -> Color.Gray.copy(0.2f)
                                    },
                                    shape = CircleShape
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(
                                            color = when {
                                                isActive -> activeColor
                                                gamingState is GamingModeState.Error -> primaryRed
                                                else -> Color.Gray
                                            },
                                            shape = CircleShape
                                        )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = when {
                                        isActive -> "ACTIVE"
                                        gamingState is GamingModeState.Error -> "ERROR"
                                        else -> "INACTIVE"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when {
                                        isActive -> activeColor
                                        gamingState is GamingModeState.Error -> primaryRed
                                        else -> Color.Gray
                                    }
                                )
                            }
                        }
                    }

                    // Progress bar (visible while busy)
                    AnimatedVisibility(visible = isBusy) {
                        Column {
                            Spacer(modifier = Modifier.height(16.dp))
                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = if (isActive) activeColor else primaryRed,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            val statusText = when (val s = gamingState) {
                                is GamingModeState.Enabling -> s.statusText
                                is GamingModeState.Disabling -> "Restoring system state…"
                                else -> ""
                            }
                            Text(
                                text = statusText,
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                    }

                    // Error message
                    AnimatedVisibility(visible = gamingState is GamingModeState.Error) {
                        Column {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(primaryRed.copy(0.08f))
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = primaryRed,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = (gamingState as? GamingModeState.Error)?.message ?: "",
                                    color = primaryRed,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Main action button
                    if (!isBusy) {
                        if (isActive) {
                            Button(
                                onClick = { viewModel.disableGamingMode(context) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = primaryRed.copy(0.15f),
                                    contentColor = primaryRed
                                )
                            ) {
                                Icon(Icons.Default.Stop, null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Deactivate Gaming Mode", fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Button(
                                onClick = {
                                    if (canActivate) viewModel.enableGamingMode(context)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = CircleShape,
                                enabled = canActivate,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = Color.White,
                                    disabledContainerColor = Color.White.copy(0.06f),
                                    disabledContentColor = Color.Gray
                                )
                            ) {
                                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (canActivate) "Activate Gaming Mode" else "Complete setup first",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else {
                        // Busy — show a spinner placeholder button
                        Button(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                disabledContainerColor = Color.White.copy(0.06f),
                                disabledContentColor = Color.Gray
                            )
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.Gray,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                if (gamingState is GamingModeState.Enabling) "Activating…" else "Restoring…",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // ---- Requirements section -----------------------------------------
        item {
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                Text(
                    "REQUIREMENTS",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 10.dp, start = 4.dp)
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(0.05f))
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RequirementRow(
                            label = "Shizuku Service",
                            description = if (shizukuReady) "Connected — ADB shell is available"
                                          else if (isShizukuAvailable) "Running but permission not granted"
                                          else "Shizuku not running",
                            satisfied = shizukuReady,
                            onAction = if (!shizukuReady) ({
                                context.startActivity(
                                    context.packageManager
                                        .getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                        ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                )
                            }) else null,
                            actionLabel = if (isShizukuAvailable) "Grant" else "Open"
                        )
                        HorizontalDivider(color = Color.White.copy(0.04f))
                        RequirementRow(
                            label = "DND / Interruption Policy",
                            description = if (hasDndAccess) "Can suppress notifications via DND"
                                          else "Required to enable Do Not Disturb during gaming",
                            satisfied = hasDndAccess,
                            onAction = {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                            }
                        )
                        HorizontalDivider(color = Color.White.copy(0.04f))
                        RequirementRow(
                            label = "Notification Listener",
                            description = if (hasNotifListenerAccess) "Active — system notifications will be cancelled"
                                          else "Optional: cancels notifications that bypass DND",
                            satisfied = hasNotifListenerAccess,
                            onAction = {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            },
                            actionLabel = "Enable"
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        // ---- App Whitelist header -----------------------------------------
        item {
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "APP WHITELIST",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                    Text(
                        "${whitelist.size} protected",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Apps switched ON will NOT be killed or restricted when Gaming Mode activates. " +
                        "Shizuku and FrameX are always protected.",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
                )
            }
        }

        // ---- App list inside a Card ---------------------------------------
        // We render apps as a Card-wrapped column (not nested LazyColumn).
        // The outer LazyColumn provides scrolling; a second lazy list is not needed
        // because the total count is bounded by installed user apps (~20-60 typically).
        if (userApps.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .fillMaxWidth()
                        .height(80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        } else {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(0.05f))
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        userApps.forEachIndexed { idx, app ->
                            AppWhitelistRow(
                                app = app,
                                isWhitelisted = whitelist.contains(app.packageName),
                                onToggle = { viewModel.toggleWhitelist(app.packageName) }
                            )
                            if (idx < userApps.lastIndex) {
                                HorizontalDivider(
                                    color = Color.White.copy(0.04f),
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        // ---- Google Apps section ------------------------------------------
        if (googleApps.isNotEmpty()) {
            item {
                Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "GOOGLE APPS",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                        Text(
                            "${googleApps.count { whitelist.contains(it.packageName) }} protected",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF4285F4)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Google apps that will be suspended during Gaming Mode. " +
                            "Toggle ON to keep an app running.",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
                    )
                }
            }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4285F4).copy(0.15f))
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        googleApps.forEachIndexed { idx, app ->
                            AppWhitelistRow(
                                app = app,
                                isWhitelisted = whitelist.contains(app.packageName),
                                onToggle = { viewModel.toggleWhitelist(app.packageName) }
                            )
                            if (idx < googleApps.lastIndex) {
                                HorizontalDivider(
                                    color = Color.White.copy(0.04f),
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        // ---- Protected Gaming Daemons info --------------------------------
        item {
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                Text(
                    "PROTECTED GAMING DAEMONS",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF22C55E).copy(0.1f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "These daemons are NEVER touched — they control 120Hz lock, " +
                                "4D vibration, frame interpolation and thermal management.",
                            color = Color.Gray,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                        viewModel.gamingDaemonsList.forEach { pkg ->
                            Row(
                                modifier = Modifier.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(Color(0xFF22C55E), CircleShape)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    pkg.substringAfterLast('.'),
                                    color = Color(0xFF22C55E),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "  ·  $pkg",
                                    color = Color.Gray,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        // ---- Safe-to-suspend info -----------------------------------------
        item {
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                Text(
                    "WILL BE SUSPENDED (OEM BLOATWARE)",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(0.1f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Suspended via pm suspend (immune to PEM restart). " +
                                "Automatically restored when Gaming Mode is turned off.",
                            color = Color.Gray,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                        viewModel.safeToSuspendList.forEach { pkg ->
                            Row(
                                modifier = Modifier.padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(MaterialTheme.colorScheme.primary.copy(0.7f), CircleShape)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    pkg.substringAfterLast('.'),
                                    color = Color.LightGray,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "  ·  $pkg",
                                    color = Color.Gray,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}
