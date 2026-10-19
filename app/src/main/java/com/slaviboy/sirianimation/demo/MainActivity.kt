package com.slaviboy.sirianimation.demo

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.slaviboy.sirianimation.SiriAnimation
import com.slaviboy.sirianimation.SiriAnimationConfig
import com.slaviboy.sirianimation.SiriAnimationPreset
import com.slaviboy.sirianimation.SiriAnimationState
import com.slaviboy.sirianimation.SiriAnimationStyle
import com.slaviboy.sirianimation.SiriAnimationView
import com.slaviboy.sirianimation.SiriPalette
import com.slaviboy.sirianimation.demo.ui.theme.SiriAnimationTheme
import kotlin.math.abs
import kotlin.math.sin

private val Background = Color(0xFF05040C)
private val Panel = Color(0xFF0E0C1C)
private val Accent = Color(0xFF8C7BFF)

/** Example custom palette used by the CUSTOM preset chip. */
private val SunsetPalette = SiriPalette(
    colors = listOf(0xFFFF6A3D.toInt(), 0xFFFFC23D.toInt(), 0xFFFF2E88.toInt(), 0xFF9B3DFF.toInt()),
    glowColor = 0xFFFF4D6D.toInt(),
    rimColor = 0xFFFFC8B0.toInt(),
    bodyColor = 0xFF2A0E1E.toInt()
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val args = DemoArgs.from(intent)
        setContent {
            SiriAnimationTheme(darkTheme = true, dynamicColor = false) {
                DemoScreen(args)
            }
        }
    }
}

private enum class BandLayout { STANDALONE, ASSISTANT }
private enum class Host { COMPOSE, VIEW }
private enum class Compare { OFF, RENDERERS, GLOW }

/**
 * Optional start values, so screenshot and profiling runs are reproducible, e.g.
 * `adb shell am start -n com.slaviboy.sirianimation/.demo.MainActivity --es state speaking --ez forceCpu true`.
 * Extras: `state` (idle|listening|thinking|speaking), `style` (orb|horizontal_band), `forceCpu`, `paused`,
 * `compare` (off|renderers|glow), `orbSize` (orb diameter fraction, 0.2..1).
 */
private class DemoArgs(
    val state: SiriAnimationState?,
    val forceCpu: Boolean,
    val paused: Boolean,
    val compare: Compare,
    val style: SiriAnimationStyle?,
    val orbSize: Float?
) {
    companion object {
        fun from(intent: Intent?): DemoArgs = DemoArgs(
            state = intent?.getStringExtra("state")?.let { s -> SiriAnimationState.entries.firstOrNull { it.name.equals(s, true) } },
            forceCpu = intent?.getBooleanExtra("forceCpu", false) ?: false,
            paused = intent?.getBooleanExtra("paused", false) ?: false,
            compare = intent?.getStringExtra("compare")?.let { s -> Compare.entries.firstOrNull { it.name.equals(s, true) } } ?: Compare.OFF,
            style = intent?.getStringExtra("style")?.let { s -> SiriAnimationStyle.entries.firstOrNull { it.name.equals(s, true) } },
            orbSize = intent?.getFloatExtra("orbSize", -1f)?.takeIf { it in 0.2f..1f }
        )
    }
}

@Composable
private fun DemoScreen(args: DemoArgs) {
    var style by rememberSaveable { mutableStateOf(args.style ?: SiriAnimationStyle.ORB) }
    var state by rememberSaveable { mutableStateOf(args.state ?: SiriAnimationState.LISTENING) }
    var preset by rememberSaveable { mutableStateOf(SiriAnimationPreset.CLASSIC) }
    var playing by rememberSaveable { mutableStateOf(!args.paused) }
    var glowRadius by rememberSaveable { mutableStateOf(SiriAnimationConfig.DEFAULT.glowRadius) }
    var compare by rememberSaveable { mutableStateOf(args.compare) }
    var intensity by rememberSaveable { mutableStateOf(SiriAnimationConfig.DEFAULT.intensity) }
    var speed by rememberSaveable { mutableStateOf(SiriAnimationConfig.DEFAULT.speed) }
    var glow by rememberSaveable { mutableStateOf(SiriAnimationConfig.DEFAULT.glowIntensity) }
    var simulateAudio by rememberSaveable { mutableStateOf(false) }
    var core by rememberSaveable { mutableStateOf(SiriAnimationConfig.DEFAULT.coreIntensity) }
    var glowOn by rememberSaveable { mutableStateOf(true) }
    var forceCpu by rememberSaveable { mutableStateOf(args.forceCpu) }
    var bandLayout by rememberSaveable { mutableStateOf(BandLayout.ASSISTANT) }
    var host by rememberSaveable { mutableStateOf(Host.COMPOSE) }

    val base = if (style == SiriAnimationStyle.HORIZONTAL_BAND && bandLayout == BandLayout.ASSISTANT) {
        SiriAnimationConfig.ASSISTANT_BAND
    } else {
        SiriAnimationConfig.DEFAULT
    }
    val config = base.copy(
        intensity = intensity,
        speed = speed,
        glowIntensity = glow,
        preset = preset,
        customPalette = SunsetPalette,
        audioReactivity = if (simulateAudio) 1f else 0f,
        coreIntensity = core,
        isGlowEnabled = glowOn,
        glowRadius = glowRadius,
        orbSizeFraction = args.orbSize ?: 0.62f
    )
    // Fake "voice" envelope; a real app would pass its own microphone RMS instead.
    val audioLevel: () -> Float = remember(simulateAudio) {
        if (simulateAudio) {
            {
                val t = System.nanoTime() / 1_000_000_000f
                val syllables = abs(sin(t * 7.3f)) * (0.55f + 0.45f * sin(t * 2.1f))
                (syllables * (0.6f + 0.4f * sin(t * 0.7f))).coerceIn(0f, 1f)
            }
        } else {
            { 0f }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Background)
            .safeDrawingPadding()
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Siri Animation", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${style.label()} · ${state.label()} · ${if (playing) "Playing" else "Paused"} · ${host.name.lowercase()} host",
                color = Accent, fontSize = 13.sp
            )
        }

        // Stage
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF0A0818), Color.Black)))
        ) {
            val stageModifier = when {
                style == SiriAnimationStyle.HORIZONTAL_BAND && bandLayout == BandLayout.STANDALONE ->
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .height(150.dp)
                else -> Modifier.fillMaxSize()
            }
            if (style == SiriAnimationStyle.HORIZONTAL_BAND && bandLayout == BandLayout.ASSISTANT) {
                AssistantMock(state)
            }
            val orbCompare = if (style == SiriAnimationStyle.ORB) compare else Compare.OFF
            when {
                orbCompare != Compare.OFF -> CompareStage(orbCompare, state, config, playing, forceCpu, audioLevel)
                host == Host.COMPOSE -> SiriAnimation(
                    style = style,
                    state = state,
                    config = config,
                    isPlaying = playing,
                    audioLevel = audioLevel,
                    forceCompatibilityRenderer = forceCpu,
                    modifier = stageModifier
                )
                else -> SiriViewHost(style, state, config, playing, forceCpu, audioLevel, stageModifier)
            }
        }

        // Controls
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(12.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Panel)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Segmented("Style", SiriAnimationStyle.entries, style, { it.label() }) { style = it }
            if (style == SiriAnimationStyle.HORIZONTAL_BAND) {
                Segmented("Band layout", BandLayout.entries, bandLayout, { if (it == BandLayout.ASSISTANT) "Assistant screen" else "Standalone" }) { bandLayout = it }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { playing = !playing },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text(if (playing) "Pause" else "Play") }
                OutlinedButton(onClick = {
                    state = SiriAnimationState.LISTENING
                    preset = SiriAnimationPreset.CLASSIC
                    intensity = SiriAnimationConfig.DEFAULT.intensity
                    speed = SiriAnimationConfig.DEFAULT.speed
                    glow = SiriAnimationConfig.DEFAULT.glowIntensity
                    core = SiriAnimationConfig.DEFAULT.coreIntensity
                    glowRadius = SiriAnimationConfig.DEFAULT.glowRadius
                    compare = Compare.OFF
                    glowOn = true
                    simulateAudio = false
                    playing = true
                }) { Text("Reset", color = Color.White) }
                Spacer(Modifier.weight(1f))
                Text("Audio", color = Color.White, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Switch(checked = simulateAudio, onCheckedChange = { simulateAudio = it })
            }
            Chips("State", SiriAnimationState.entries, state, { it.label() }) { state = it }
            LabeledSlider("Intensity", intensity, 0f..1f) { intensity = it }
            LabeledSlider("Speed", speed, 0f..3f) { speed = it }
            LabeledSlider("Glow", glow, 0f..2f) { glow = it }
            LabeledSlider("Glow size", glowRadius, 0.5f..2f) { glowRadius = it }
            LabeledSlider("Core", core, 0f..2f) { core = it }
            ToggleRow("Outer glow", glowOn) { glowOn = it }
            if (style == SiriAnimationStyle.ORB) {
                Segmented("Compare (diagnostic)", Compare.entries, compare, { it.label() }) { compare = it }
            }
            ToggleRow("Force CPU orb renderer (API < 33 path)", forceCpu) { forceCpu = it }
            Chips("Preset", SiriAnimationPreset.entries, preset, { it.label() }) { preset = it }
            Segmented("Rendered by", Host.entries, host, { if (it == Host.COMPOSE) "SiriAnimation()" else "SiriAnimationView" }) { host = it }
        }
    }
}

/**
 * Two orbs side by side with the same state and config, so their differences are easy to see:
 * GPU shader vs CPU compatibility renderer, or outer glow on vs off.
 */
@Composable
private fun CompareStage(
    compare: Compare,
    state: SiriAnimationState,
    config: SiriAnimationConfig,
    playing: Boolean,
    forceCpu: Boolean,
    audioLevel: () -> Float
) {
    val left = if (compare == Compare.GLOW) config.copy(isGlowEnabled = true) else config
    val right = if (compare == Compare.GLOW) config.copy(isGlowEnabled = false) else config
    val labels = if (compare == Compare.GLOW) {
        "Glow on" to "Glow off"
    } else {
        (if (Build.VERSION.SDK_INT >= 33) "GPU shader" else "CPU (API < 33)") to "CPU compatibility"
    }
    Row(Modifier.fillMaxSize()) {
        // GPU | CPU always compares the two renderers; glow on | off uses whatever renderer is selected.
        val leftCpu = if (compare == Compare.GLOW) forceCpu else false
        val rightCpu = if (compare == Compare.GLOW) forceCpu else true
        listOf(left to leftCpu, right to rightCpu).forEachIndexed { i, (cfg, cpu) ->
            Box(Modifier.weight(1f).fillMaxHeight()) {
                SiriAnimation(
                    style = SiriAnimationStyle.ORB,
                    state = state,
                    config = cfg,
                    isPlaying = playing,
                    audioLevel = audioLevel,
                    forceCompatibilityRenderer = cpu,
                    modifier = Modifier.fillMaxSize()
                )
                Text(
                    if (i == 0) labels.first else labels.second,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp)
                )
            }
        }
    }
}

/** The same animation through the classic View API, embedded with AndroidView. */
@Composable
private fun SiriViewHost(
    style: SiriAnimationStyle,
    state: SiriAnimationState,
    config: SiriAnimationConfig,
    playing: Boolean,
    forceCpu: Boolean,
    audioLevel: () -> Float,
    modifier: Modifier
) {
    var view by remember { mutableStateOf<SiriAnimationView?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { SiriAnimationView(it).also { v -> v.setStyle(style, animate = false); view = v } },
        update = {
            it.style = style
            it.state = state
            it.config = config
            if (playing) it.play() else it.pause()
            it.forceCompatibilityRenderer = forceCpu
        }
    )
    LaunchedEffect(view, audioLevel) {
        val v = view ?: return@LaunchedEffect
        while (true) withFrameNanos { v.setAudioLevel(audioLevel()) }
    }
}

@Composable
private fun AssistantMock(state: SiriAnimationState) {
    Column(Modifier.fillMaxSize().padding(28.dp)) {
        Spacer(Modifier.height(24.dp))
        Text(
            when (state) {
                SiriAnimationState.IDLE -> "Tap to talk"
                SiriAnimationState.LISTENING -> "What can I help you with?"
                SiriAnimationState.THINKING -> "Thinking…"
                SiriAnimationState.SPEAKING -> "Here's what I found for you."
            },
            color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Light, lineHeight = 32.sp
        )
        Spacer(Modifier.height(8.dp))
        Text("Assistant layout · band anchored near the bottom", color = Color.White.copy(alpha = 0.4f), fontSize = 13.sp)
    }
}

@Composable
private fun <T> Segmented(title: String, items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Text(title, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        items.forEachIndexed { i, item ->
            SegmentedButton(
                selected = item == selected,
                onClick = { onSelect(item) },
                shape = SegmentedButtonDefaults.itemShape(i, items.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Accent.copy(alpha = 0.3f),
                    activeContentColor = Color.White,
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = Color.White.copy(alpha = 0.7f)
                )
            ) { Text(label(item), fontSize = 13.sp) }
        }
    }
}

@Composable
private fun <T> Chips(title: String, items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Text(title, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            FilterChip(
                selected = item == selected,
                onClick = { onSelect(item) },
                label = { Text(label(item)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Accent.copy(alpha = 0.3f),
                    selectedLabelColor = Color.White,
                    labelColor = Color.White.copy(alpha = 0.7f)
                )
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LabeledSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, modifier = Modifier.width(72.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent)
        )
        Text("%.2f".format(value), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.width(40.dp))
    }
}

private fun SiriAnimationStyle.label() = if (this == SiriAnimationStyle.ORB) "Orb" else "Horizontal Band"
private fun SiriAnimationState.label() = name.lowercase().replaceFirstChar { it.uppercase() }
private fun SiriAnimationPreset.label() = name.lowercase().replaceFirstChar { it.uppercase() }
private fun Compare.label() = when (this) {
    Compare.OFF -> "Off"
    Compare.RENDERERS -> "GPU | CPU"
    Compare.GLOW -> "Glow on | off"
}
