package com.framex.app.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.framex.app.core.root.RootManager
import com.framex.app.hud.FloatingWindowService
import com.framex.app.hud.GpuLayerInjector
import com.framex.app.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HudInjectableApp(
    val packageName: String,
    val label: String
)

@HiltViewModel
class OverlayCustomizationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val rootManager: RootManager
) : ViewModel() {
    val mode = settingsRepository.overlayMode
    val opacity = settingsRepository.overlayOpacity
    val textSize = settingsRepository.overlayTextSize
    val useMonospace = settingsRepository.overlayUseMonospace
    val colorIndex = settingsRepository.overlayColorIndex
    val targetPackage = settingsRepository.hudTargetPackage
    val targetLabel = settingsRepository.hudTargetLabel

    private val _apps = MutableStateFlow<List<HudInjectableApp>>(emptyList())
    val apps = _apps.asStateFlow()

    private val _isInjecting = MutableStateFlow(false)
    val isInjecting = _isInjecting.asStateFlow()

    private val _injectionStatus = MutableStateFlow("")
    val injectionStatus = _injectionStatus.asStateFlow()

    init {
        loadApps()
    }

    fun save(mode: String, opacity: Float, textSize: Int, useMonospace: Boolean, colorIndex: Int) {
        settingsRepository.setOverlayMode(mode)
        settingsRepository.setOverlayOpacity(opacity)
        settingsRepository.setOverlayTextSize(textSize)
        settingsRepository.setOverlayUseMonospace(useMonospace)
        settingsRepository.setOverlayColorIndex(colorIndex)
    }

    fun selectTarget(app: HudInjectableApp) {
        settingsRepository.setHudTargetApp(app.packageName, app.label)
        _injectionStatus.value = "${app.label} selected"
    }

    fun injectSelectedTarget() {
        val packageName = targetPackage.value
        val label = targetLabel.value.ifBlank { packageName }
        if (packageName.isBlank()) {
            _injectionStatus.value = "Choose an app first"
            return
        }

        viewModelScope.launch {
            _isInjecting.value = true
            if (!FloatingWindowService.start(context)) {
                _injectionStatus.value = "Grant overlay permission first"
                _isInjecting.value = false
                return@launch
            }

            _injectionStatus.value = "Requesting root..."
            val rooted = rootManager.refresh()
            if (!rooted) {
                _injectionStatus.value = "Root access is required"
                _isInjecting.value = false
                return@launch
            }

            val ok = GpuLayerInjector(rootManager).enable(packageName, forceRestart = true)
            _injectionStatus.value = if (ok) {
                "Zygisk target set for $label. Launch it after reboot."
            } else {
                "Injection failed"
            }
            _isInjecting.value = false
        }
    }

    fun clearInjection() {
        viewModelScope.launch {
            _isInjecting.value = true
            if (rootManager.refresh()) {
                GpuLayerInjector(rootManager).disable()
            }
            settingsRepository.clearHudTargetApp()
            _injectionStatus.value = "Injection cleared"
            _isInjecting.value = false
        }
    }

    private fun loadApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val result = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { it.packageName != context.packageName }
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .map {
                    HudInjectableApp(
                        packageName = it.packageName,
                        label = it.loadLabel(pm).toString()
                    )
                }
                .sortedWith(compareBy<HudInjectableApp> { it.label.lowercase() }.thenBy { it.packageName })
                .toList()
            _apps.value = result
        }
    }
}

@Composable
fun OverlayCustomizationScreen(
    onNavigateBack: () -> Unit,
    viewModel: OverlayCustomizationViewModel = hiltViewModel()
) {
    val savedMode by viewModel.mode.collectAsState()
    val savedOpacity by viewModel.opacity.collectAsState()
    val savedTextSize by viewModel.textSize.collectAsState()
    val savedUseMonospace by viewModel.useMonospace.collectAsState()
    val savedColorIndex by viewModel.colorIndex.collectAsState()
    val apps by viewModel.apps.collectAsState()
    val targetPackage by viewModel.targetPackage.collectAsState()
    val targetLabel by viewModel.targetLabel.collectAsState()
    val isInjecting by viewModel.isInjecting.collectAsState()
    val injectionStatus by viewModel.injectionStatus.collectAsState()
    val context = LocalContext.current

    val modes = listOf("Compact", "Standard", "Expanded")
    val colors = listOf(
        Color(0xFF64D262),
        Color(0xFF4EB4D8),
        Color(0xFFFF756D),
        Color(0xFFE8C45A),
        Color(0xFFA78BFA),
        Color(0xFFFFFFFF)
    )

    var selectedMode by remember(savedMode) {
        mutableStateOf(if (savedMode in modes) savedMode else "Compact")
    }
    var selectedOpacity by remember(savedOpacity) { mutableFloatStateOf(savedOpacity.coerceIn(0.35f, 0.95f)) }
    var selectedTextSize by remember(savedTextSize) { mutableIntStateOf(savedTextSize.coerceIn(0, 2)) }
    var selectedUseMonospace by remember(savedUseMonospace) { mutableStateOf(savedUseMonospace) }
    var selectedColorIndex by remember(savedColorIndex) {
        mutableIntStateOf(savedColorIndex.coerceIn(colors.indices))
    }

    val hasChanges = selectedMode != savedMode ||
        selectedOpacity != savedOpacity ||
        selectedTextSize != savedTextSize ||
        selectedUseMonospace != savedUseMonospace ||
        selectedColorIndex != savedColorIndex

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Back", tint = Color.White)
                }
                Text("Metal HUD Config", style = MaterialTheme.typography.titleMedium, color = Color.White)
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                contentPadding = PaddingValues(bottom = 108.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                item {
                    MetalHudPreview(
                        mode = selectedMode,
                        opacity = selectedOpacity,
                        textSize = selectedTextSize,
                        useMonospace = selectedUseMonospace,
                        accent = colors[selectedColorIndex]
                    )
                }

                item {
                    ConfigLabel("Zygisk Injection")
                    InjectionMenu(
                        apps = apps,
                        selectedPackage = targetPackage,
                        selectedLabel = targetLabel,
                        status = injectionStatus,
                        isInjecting = isInjecting,
                        accent = colors[selectedColorIndex],
                        onSelect = viewModel::selectTarget,
                        onInject = viewModel::injectSelectedTarget,
                        onClear = viewModel::clearInjection
                    )
                }

                item {
                    ConfigLabel("HUD Size")
                    SegmentedOptions(modes, selectedMode) { selectedMode = it }
                }

                item {
                    ConfigLabel("Opacity ${Math.round(selectedOpacity * 100f)}%")
                    Slider(
                        value = selectedOpacity,
                        onValueChange = { selectedOpacity = it },
                        valueRange = 0.35f..0.95f
                    )
                }

                item {
                    ConfigLabel("Text Size")
                    SegmentedOptions(listOf("Small", "Medium", "Large"), textSizeLabel(selectedTextSize)) {
                        selectedTextSize = when (it) {
                            "Small" -> 0
                            "Large" -> 2
                            else -> 1
                        }
                    }
                }

                item {
                    ConfigToggle(
                        title = "Monospace Readout",
                        checked = selectedUseMonospace,
                        accent = colors[selectedColorIndex],
                        onCheckedChange = { selectedUseMonospace = it }
                    )
                }

                item {
                    ConfigLabel("Accent")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        colors.forEachIndexed { index, color ->
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (index == selectedColorIndex) 3.dp else 1.dp,
                                        color = if (index == selectedColorIndex) Color.White else Color.White.copy(alpha = 0.25f),
                                        shape = CircleShape
                                    )
                                    .clickable { selectedColorIndex = index }
                            )
                        }
                    }
                }

                item {
                    ConfigLabel("Telemetry Rows")
                    TelemetryRows(colors[selectedColorIndex])
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.95f))
                .padding(24.dp)
        ) {
            Button(
                onClick = {
                    if (hasChanges) {
                        viewModel.save(
                            selectedMode,
                            selectedOpacity,
                            selectedTextSize,
                            selectedUseMonospace,
                            selectedColorIndex
                        )
                        Toast.makeText(context, "Metal HUD configuration saved", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = hasChanges,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors[selectedColorIndex],
                    contentColor = Color.White,
                    disabledContainerColor = Color.DarkGray.copy(alpha = 0.5f),
                    disabledContentColor = Color.Gray
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(if (hasChanges) "Apply Changes" else "Applied", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun InjectionMenu(
    apps: List<HudInjectableApp>,
    selectedPackage: String,
    selectedLabel: String,
    status: String,
    isInjecting: Boolean,
    accent: Color,
    onSelect: (HudInjectableApp) -> Unit,
    onInject: () -> Unit,
    onClear: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val displayLabel = selectedLabel.ifBlank { "Choose target app" }
    val displayPackage = selectedPackage.ifBlank { "No app selected" }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, accent.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    displayLabel,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    displayPackage,
                    color = Color.Gray,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Button(
                onClick = { expanded = !expanded },
                colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.18f), contentColor = accent),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(if (expanded) "Close" else "Apps", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        if (expanded) {
            Spacer(modifier = Modifier.height(10.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 230.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.background)
            ) {
                items(apps, key = { it.packageName }) { app ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(app)
                                expanded = false
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            app.label,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            app.packageName,
                            color = Color.Gray,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onInject,
                enabled = selectedPackage.isNotBlank() && !isInjecting,
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (isInjecting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Text("Inject & Restart", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            Button(
                onClick = onClear,
                enabled = !isInjecting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.08f),
                    contentColor = Color.LightGray
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Clear", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        if (status.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(status, color = accent, fontSize = 11.sp)
        }
    }
}

@Composable
private fun MetalHudPreview(
    mode: String,
    opacity: Float,
    textSize: Int,
    useMonospace: Boolean,
    accent: Color
) {
    val fontSize = when (textSize) {
        0 -> 9.sp
        2 -> 11.sp
        else -> 10.sp
    }
    val height = when (mode) {
        "Expanded" -> 220.dp
        "Standard" -> 198.dp
        else -> 176.dp
    }
    val fontFamily = if (useMonospace) FontFamily.Monospace else FontFamily.Default

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(300.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(75, 75, 75).copy(alpha = opacity))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text("Dimensity 9300+   [2400x1260]", color = Color.White, fontSize = fontSize, fontFamily = fontFamily)
            Text("1.0x Direct no layer 144Hz", color = Color.White, fontSize = fontSize, fontFamily = fontFamily)
            Text("Thermal:    Nominal", color = accent, fontSize = fontSize, fontFamily = fontFamily)
            Text("FPS:       0.00 [  0.00   0.00]", color = Color.White, fontSize = fontSize, fontFamily = fontFamily)
            Text("GPU:       --   [  --     --  ]", color = accent, fontSize = fontSize, fontFamily = fontFamily)
            Text("Frame Interval:   0.00", color = Color.White, fontSize = fontSize, fontFamily = fontFamily)
            Text("Cmd Buffer CPU:   --", color = Color(0xFF4EB4D8), fontSize = fontSize, fontFamily = fontFamily)
            Text("Mem:  5.82GB    [6542MB free]", color = Color.White, fontSize = fontSize, fontFamily = fontFamily)
            Spacer(modifier = Modifier.height(4.dp))
            Canvas(modifier = Modifier.fillMaxWidth().height(36.dp)) {
                drawRect(Color.White.copy(alpha = 0.35f), style = Stroke(width = 1.dp.toPx()))
                val path = Path().apply {
                    moveTo(0f, size.height * 0.72f)
                    lineTo(size.width * 0.2f, size.height * 0.55f)
                    lineTo(size.width * 0.4f, size.height * 0.62f)
                    lineTo(size.width * 0.62f, size.height * 0.38f)
                    lineTo(size.width * 0.82f, size.height * 0.48f)
                    lineTo(size.width, size.height * 0.3f)
                }
                drawPath(path, accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
private fun ConfigLabel(text: String) {
    Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun SegmentedOptions(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(4.dp)
    ) {
        options.forEach { option ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (selected == option) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    option,
                    color = if (selected == option) Color.White else Color.Gray,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun ConfigToggle(
    title: String,
    checked: Boolean,
    accent: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = accent)
        )
    }
}

@Composable
private fun TelemetryRows(accent: Color) {
    val rows = listOf("CPU/SoC", "Display", "Thermal", "FPS", "GPU timings", "Memory")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                pair.forEach { row ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(row, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

private fun textSizeLabel(size: Int): String =
    when (size) {
        0 -> "Small"
        2 -> "Large"
        else -> "Medium"
    }
