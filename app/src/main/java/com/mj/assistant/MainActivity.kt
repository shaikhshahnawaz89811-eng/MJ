package com.mj.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.mj.assistant.ai.AIEngine
import com.mj.assistant.ai.LocalAIEngine

private val Bg = Color(0xFF060A12)
private val Surface = Color(0xFF0E1420)
private val Surface2 = Color(0xFF141B28)
private val Surface3 = Color(0xFF192233)
private val Purple = Color(0xFFB96CFF)
private val Purple2 = Color(0xFF7540B9)
private val Pink = Color(0xFFFF79C8)
private val TextMain = Color(0xFFF7F3FB)
private val TextMuted = Color(0xFF929AAA)
private val Green = Color(0xFF4DE19B)

private data class ChatMessage(
    val id: Long = System.nanoTime(),
    val text: String,
    val fromUser: Boolean,
    val action: ActionState? = null,
    val animate: Boolean = true
)

private data class ActionState(val type: String, val enabled: Boolean)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContent { MJApp() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MJApp() {
    var selected by remember { mutableIntStateOf(0) }
    var listening by remember { mutableStateOf(true) }
    var verified by remember { mutableStateOf(true) }
    var voiceOutput by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf("") }
    var replying by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var ttsReady by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var pendingTorch by remember { mutableStateOf<Boolean?>(null) }
    val torchPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val target = pendingTorch
        pendingTorch = null
        if (granted && target != null) {
            try {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = manager.cameraIdList.firstOrNull { id ->
                    manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
                if (cameraId != null) {
                    manager.setTorchMode(cameraId, target)
                    torchOn = target
                }
            } catch (_: Exception) { }
        }
    }
    fun requestOrSetTorch(target: Boolean): Boolean {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingTorch = target
            torchPermission.launch(Manifest.permission.CAMERA)
            return false
        }
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            manager.setTorchMode(cameraId, target)
            torchOn = target
            true
        } catch (_: Exception) { false }
    }
    val tts = remember {
        TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }.apply {
            language = java.util.Locale("hi", "IN")
            setSpeechRate(0.96f)
        }
    }
    DisposableEffect(Unit) { onDispose { tts.stop(); tts.shutdown() } }

    fun speak(text: String, utteranceId: String) {
        if (voiceOutput && ttsReady) {
            tts.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                utteranceId
            )
        }
    }

    val scope = rememberCoroutineScope()
    val aiEngine: AIEngine = remember { LocalAIEngine() }
    val listState = rememberLazyListState()

    var messages by remember {
        mutableStateOf(
            listOf(
                ChatMessage(
                    text = "Assalamu Alaikum, main MJ hoon. Aap jo kahenge, main samajh kar jawab dungi.",
                    fromUser = false,
                    animate = false
                )
            )
        )
    }

    LaunchedEffect(messages.size, replying) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Surface, primary = Purple, onSurface = TextMain)) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Column(Modifier.fillMaxSize()) {
                TopBar(
                    showBack = selected != 0,
                    onBack = { selected = 0 },
                    listening = listening,
                    verified = verified,
                    voiceOutput = voiceOutput,
                    onVoiceToggle = { voiceOutput = !voiceOutput },
                    onSettings = { }
                )

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AnimatedContent(
                        targetState = selected,
                        transitionSpec = {
                            (fadeIn(tween(180)) + slideInHorizontally { it / 12 }) togetherWith
                                    fadeOut(tween(100))
                        },
                        label = "page"
                    ) { page ->
                        when (page) {
                            0 -> ChatScreen(
                                listState = listState,
                                messages = messages,
                                text = text,
                                replying = replying,
                                listening = listening,
                                verified = verified,
                                onTextChange = { text = it },
                                onSend = {
                                    if (text.isNotBlank() && !replying) {
                                        val command = text.trim()
                                        keyboard?.hide()
                                        messages = messages + ChatMessage(text = command, fromUser = true)
                                        text = ""
                                        replying = true
                                        scope.launch {
                                            delay(520)
                                            val lower = command.lowercase()
                                            if (lower.contains("torch") && (lower.contains("on") || lower.contains("chala"))) {
                                                torchOn = requestOrSetTorch(true)
                                                val ok = torchOn
                                                val answer = if (ok) "Ji, torch on kar di." else "Torch on nahi ho saka."
                                                messages = messages + ChatMessage(
                                                    text = answer,
                                                    fromUser = false,
                                                    action = ActionState("Torch", ok)
                                                )
                                                if (voiceOutput && ttsReady) tts.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "mj_torch")
                                            } else if (lower.contains("torch") && (lower.contains("off") || lower.contains("band"))) {
                                                val ok = requestOrSetTorch(false)
                                                if (ok) torchOn = false
                                                val answer = if (ok) "Ji, torch off kar di." else "Torch off nahi ho saka."
                                                messages = messages + ChatMessage(
                                                    text = answer,
                                                    fromUser = false,
                                                    action = ActionState("Torch", torchOn)
                                                )
                                                if (voiceOutput && ttsReady) tts.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "mj_torch")
                                            } else {
                                                val answer = aiEngine.reply(command)
                                                messages = messages + ChatMessage(
                                                    text = answer,
                                                    fromUser = false
                                                )
                                                if (voiceOutput && ttsReady) {
                                                    tts.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "mj_reply")
                                                }
                                            }
                                            replying = false
                                        }
                                    }
                                },
                                onMic = { listening = !listening },
                                onSpeak = { answer -> speak(answer, "mj_manual_${System.nanoTime()}") },
                                torchOn = torchOn,
                                onTorchToggle = {
                                    val target = !torchOn
                                    val ok = requestOrSetTorch(target)
                                    if (ok) torchOn = target
                                }
                            )
                            1 -> HistoryScreen(messages)
                            2 -> ActionsScreen(torchOn, {
                                val target = !torchOn
                                if (requestOrSetTorch(target)) torchOn = target
                            })
                            else -> ProfileScreen(voiceOutput, { voiceOutput = !voiceOutput })
                        }
                    }
                }

                if (!WindowInsets.isImeVisible) {
                    NavigationBar(
                        windowInsets = NavigationBarDefaults.windowInsets,
                        containerColor = Color(0xFF090D16),
                        tonalElevation = 0.dp
                    ) {
                    val labels = listOf("Chat", "History", "Actions", "Profile")
                    val icons = listOf(Icons.Outlined.ChatBubbleOutline, Icons.Outlined.History, Icons.Outlined.Tune, Icons.Outlined.PersonOutline)
                    labels.forEachIndexed { i, label ->
                        NavigationBarItem(
                            selected = selected == i,
                            onClick = { selected = i },
                            icon = { Icon(icons[i], label, Modifier.size(21.dp)) },
                            label = { Text(label, fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Purple,
                                selectedTextColor = Purple,
                                indicatorColor = Purple.copy(alpha = .13f),
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted
                            )
                        )
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    showBack: Boolean,
    onBack: () -> Unit,
    listening: Boolean,
    verified: Boolean,
    voiceOutput: Boolean,
    onVoiceToggle: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showBack) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.ArrowBack, "Back", tint = TextMain)
            }
        }
        Column(Modifier.weight(1f)) {
            Text("MJ", fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (listening) Green else TextMuted))
                Spacer(Modifier.width(7.dp))
                Text(if (listening) "Always listening…" else "Paused", color = TextMuted, fontSize = 12.sp)
                if (verified) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Outlined.VerifiedUser, contentDescription = null, modifier = Modifier.size(14.dp), tint = Green)
                    Spacer(Modifier.width(3.dp))
                    Text("Verified", color = Green, fontSize = 11.sp)
                }
            }
        }
        IconButton(onClick = onVoiceToggle, modifier = Modifier.size(40.dp)) {
            Icon(if (voiceOutput) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff, "Voice replies", tint = if (voiceOutput) Purple else TextMuted)
        }
        IconButton(onClick = onSettings, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Outlined.Settings, "Settings", tint = TextMain)
        }
    }
}

@Composable
private fun ChatScreen(
    listState: androidx.compose.foundation.lazy.LazyListState,
    messages: List<ChatMessage>,
    text: String,
    replying: Boolean,
    listening: Boolean,
    verified: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
    onSpeak: (String) -> Unit,
    torchOn: Boolean,
    onTorchToggle: () -> Unit
) {
    val latestAssistantIndex = if (replying) -1 else messages.indexOfLast { !it.fromUser }

    Column(Modifier.fillMaxSize()) {
        if (messages.size == 1) {
            PresenceHero(listening, verified)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            itemsIndexed(messages, key = { _, it -> it.id }) { index, msg ->
                ChatMessageRow(
                    msg = msg,
                    isLatestAssistant = !replying && index == latestAssistantIndex,
                    torchOn = torchOn,
                    onTorchToggle = onTorchToggle,
                    onSpeak = onSpeak
                )
            }
            if (replying) item(key = "thinking") { ThinkingRow() }
        }

        Composer(
            text = text,
            onTextChange = onTextChange,
            onMic = onMic,
            onSend = onSend
        )
    }
}

@Composable
private fun PresenceHero(listening: Boolean, verified: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DynamicMJOrb(listening)
        Spacer(Modifier.height(1.dp))
        Text(
            if (listening) "Listening…" else "Paused",
            color = TextMuted,
            fontSize = 10.sp
        )
        Text(
            if (verified) "Voice verified" else "Waiting for verification",
            color = if (verified) Green else TextMuted,
            fontSize = 9.sp
        )
    }
}

@Composable
private fun DynamicMJOrb(active: Boolean) {
    val t = rememberInfiniteTransition(label = "orb")
    val pulse by t.animateFloat(.94f, 1.06f, infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val rotation by t.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "rotation")
    val glass by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(1900, easing = LinearEasing), RepeatMode.Restart), label = "glass")

    Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = 25.dp.toPx() * pulse
            drawCircle(Brush.radialGradient(listOf(Pink.copy(.25f), Purple.copy(.12f), Color.Transparent)), r * 1.9f, c)
            drawCircle(Brush.radialGradient(listOf(Purple.copy(.85f), Purple2.copy(.55f), Color(0xFF211330))), r, c)
            for (i in 0 until 44) {
                val a = Math.toRadians((i * 15.0 + rotation).toDouble())
                val r1 = r + 5.dp.toPx()
                val r2 = r + 9.dp.toPx() + (i % 4) * 1.6.dp.toPx()
                drawLine(
                    color = Purple.copy(if (active) .48f else .18f),
                    start = Offset(x = c.x + kotlin.math.cos(a).toFloat() * r1, y = c.y + kotlin.math.sin(a).toFloat() * r1),
                    end = Offset(x = c.x + kotlin.math.cos(a).toFloat() * r2, y = c.y + kotlin.math.sin(a).toFloat() * r2),
                    strokeWidth = 1.4.dp.toPx(), cap = StrokeCap.Round
                )
            }
            val bx = size.width * (.22f + (glass + 1f) * .39f)
            drawOval(
                brush = Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(.13f), Color.Transparent)),
                topLeft = Offset(x = bx, y = c.y - r * .82f),
                size = Size(width = 34.dp.toPx(), height = r * 1.64f)
            )
        }
        Box(Modifier.size(54.dp).clip(CircleShape).background(Color(0xFF170F23)).border(1.dp, Purple.copy(.5f), CircleShape), contentAlignment = Alignment.Center) {
            Text("MJ", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ChatMessageRow(
    msg: ChatMessage,
    isLatestAssistant: Boolean,
    torchOn: Boolean,
    onTorchToggle: () -> Unit,
    onSpeak: (String) -> Unit
) {
    AnimatedVisibility(
        visible = true,
        enter = if (msg.fromUser) fadeIn(tween(180)) + slideInHorizontally { it / 3 }
        else fadeIn(tween(260)) + slideInHorizontally { -it / 3 },
        label = "message-${msg.id}"
    ) {
        if (msg.fromUser) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                UserMessage(msg.text)
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                if (isLatestAssistant) {
                    MessageOrb()
                    Spacer(Modifier.width(7.dp))
                } else {
                    Spacer(Modifier.width(31.dp))
                }
                Column(Modifier.widthIn(max = 315.dp)) {
                    AssistantMessage(msg.text, msg.animate && isLatestAssistant, onSpeak)
                    if (msg.action != null) {
                        Spacer(Modifier.height(8.dp))
                        DeviceActionCard(msg.action.type, torchOn, onTorchToggle)
                    }
                }
            }
        }
    }
}

@Composable
private fun UserMessage(text: String) {
    Text(
        text = text,
        color = TextMain,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        modifier = Modifier
            .widthIn(max = 300.dp)
            .background(Purple2.copy(.82f), RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    )
}

@Composable
private fun AssistantMessage(text: String, animateGlass: Boolean, onSpeak: (String) -> Unit) {
    var glassActive by remember(text) { mutableStateOf(animateGlass) }
    val transition = rememberInfiniteTransition(label = "assistantGlass-$text")
    val phase by transition.animateFloat(
        -1f, 1f,
        infiniteRepeatable(tween(1050, easing = LinearEasing), RepeatMode.Restart),
        label = "assistantGlassPhase"
    )

    LaunchedEffect(text, animateGlass) {
        glassActive = animateGlass
        if (animateGlass) {
            delay(1200)
            glassActive = false
        }
    }

    Column(Modifier.widthIn(max = 315.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("MJ", color = Purple, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            IconButton(
                onClick = { onSpeak(text) },
                modifier = Modifier.size(26.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.VolumeUp,
                    contentDescription = "Listen to MJ",
                    modifier = Modifier.size(14.dp),
                    tint = TextMuted
                )
            }
        }
        Box(Modifier.fillMaxWidth()) {
            // No visible chat card. Only the text remains after the response settles.
            Text(
                text = text,
            color = TextMain,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            modifier = Modifier.padding(vertical = 1.dp)
        )
        if (glassActive) {
            Canvas(Modifier.matchParentSize()) {
                val x = size.width * (.08f + (phase + 1f) * .46f)
                drawRect(
                    brush = Brush.linearGradient(
                        listOf(
                            Color.Transparent,
                            Color.White.copy(.05f),
                            Color.White.copy(.16f),
                            Purple.copy(.08f),
                            Color.Transparent
                        )
                    ),
                    topLeft = Offset(x = x - 55.dp.toPx(), y = 0f),
                    size = androidx.compose.ui.geometry.Size(
                        width = 110.dp.toPx(),
                        height = size.height
                    )
                )
            }
        }
        }
    }
}

@Composable
private fun MessageOrb() {
    val t = rememberInfiniteTransition(label = "sideOrb")
    val glow by t.animateFloat(.62f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "glow")
    val rotation by t.animateFloat(0f, 360f, infiniteRepeatable(tween(5000, easing = LinearEasing)), label = "orbRotation")
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            drawCircle(Purple.copy(alpha = .13f * glow), radius = 11.dp.toPx(), center = c)
            drawCircle(
                Brush.radialGradient(listOf(Pink.copy(.92f), Purple.copy(.72f), Color(0xFF29163C))),
                radius = 6.dp.toPx(), center = c
            )
            val a = Math.toRadians(rotation.toDouble())
            drawCircle(
                Color.White.copy(.82f),
                radius = 1.dp.toPx(),
                center = Offset(
                    x = c.x + kotlin.math.cos(a).toFloat() * 7.dp.toPx(),
                    y = c.y + kotlin.math.sin(a).toFloat() * 7.dp.toPx()
                )
            )
        }
    }
}

@Composable
private fun DeviceActionCard(type: String, enabled: Boolean, onToggle: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onToggle),
        shape = RoundedCornerShape(17.dp),
        color = Surface3,
        border = BorderStroke(1.dp, Color.White.copy(.05f))
    ) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(39.dp).clip(RoundedCornerShape(12.dp)).background(if (enabled) Purple.copy(.18f) else Color(0xFF101722)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.FlashlightOn, contentDescription = null, modifier = Modifier.size(21.dp), tint = if (enabled) Purple else TextMuted)
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(type, fontWeight = FontWeight.Medium)
                Text(if (enabled) "ON" else "OFF", color = if (enabled) Green else TextMuted, fontSize = 11.sp)
            }
            Switch(checked = enabled, onCheckedChange = { onToggle() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Purple, uncheckedThumbColor = TextMuted, uncheckedTrackColor = Color(0xFF252C39)))
        }
    }
}

@Composable
private fun ThinkingRow() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        MessageOrb()
        Spacer(Modifier.width(8.dp))
        repeat(3) { i ->
            val t = rememberInfiniteTransition(label = "think$i")
            val y by t.animateFloat(
                0f, -4f,
                infiniteRepeatable(tween(420, delayMillis = i * 100), RepeatMode.Reverse),
                label = "dot$i"
            )
            Box(
                Modifier
                    .offset(y = y.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(Purple)
            )
            if (i < 2) Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun Composer(text: String, onTextChange: (String) -> Unit, onMic: () -> Unit, onSend: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        IconButton(onClick = onMic, modifier = Modifier.size(46.dp).clip(CircleShape).background(Surface)) {
            Icon(Icons.Outlined.Mic, contentDescription = "Microphone", modifier = Modifier.size(22.dp), tint = Purple)
        }
        Spacer(Modifier.width(7.dp))
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message MJ…", color = TextMuted) },
            minLines = 1,
            maxLines = 4,
            shape = RoundedCornerShape(22.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Purple.copy(.75f),
                unfocusedBorderColor = Color(0xFF252C3B),
                focusedContainerColor = Surface,
                unfocusedContainerColor = Surface,
                cursorColor = Purple
            )
        )
        Spacer(Modifier.width(7.dp))
        FloatingActionButton(onClick = onSend, modifier = Modifier.size(48.dp), containerColor = Purple, contentColor = Color.White) {
            Icon(Icons.Outlined.ArrowUpward, "Send")
        }
    }
}

@Composable
private fun HistoryScreen(messages: List<ChatMessage>) {
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("History", fontSize = 23.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp)) }
        items(messages.filter { it.fromUser }.reversed()) { msg ->
            Surface(shape = RoundedCornerShape(17.dp), color = Surface) {
                ListItem(
                    headlineContent = { Text(msg.text, maxLines = 2) },
                    supportingContent = { Text("Chat") },
                    leadingContent = { Icon(Icons.Outlined.History, null, tint = Purple) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }
    }
}

@Composable
private fun ActionsScreen(torchOn: Boolean, onTorchToggle: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Quick Actions", fontSize = 23.sp, fontWeight = FontWeight.SemiBold); Text("Device controls", color = TextMuted, fontSize = 12.sp); Spacer(Modifier.height(5.dp)) }
        item { DeviceActionCard("Torch", torchOn, onTorchToggle) }
        item { ActionCard("Wi‑Fi", Icons.Outlined.Wifi, "Device control") }
        item { ActionCard("Bluetooth", Icons.Outlined.Bluetooth, "Device control") }
        item { ActionCard("Mobile Data", Icons.Outlined.SignalCellularAlt, "Device control") }
        item { ActionCard("Battery", Icons.Outlined.BatteryFull, "Device info") }
        item { ActionCard("Time", Icons.Outlined.Schedule, "Device info") }
        item { ActionCard("Date", Icons.Outlined.CalendarToday, "Device info") }
    }
}

@Composable
private fun ActionCard(name: String, icon: androidx.compose.ui.graphics.vector.ImageVector, sub: String) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(17.dp), color = Surface) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Purple.copy(.11f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Purple) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text(name, fontWeight = FontWeight.Medium); Text(sub, color = TextMuted, fontSize = 12.sp) }
            Icon(Icons.Outlined.ChevronRight, null, tint = TextMuted)
        }
    }
}

@Composable
private fun ProfileScreen(voiceOutput: Boolean, onVoiceToggle: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Profile", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = Surface) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MessageOrb()
                        Spacer(Modifier.width(12.dp))
                        Column { Text("MJ", fontSize = 21.sp, fontWeight = FontWeight.SemiBold); Text("AI voice assistant", color = TextMuted, fontSize = 12.sp) }
                    }
                    Spacer(Modifier.height(18.dp))
                    HorizontalDivider(color = Color.White.copy(.05f))
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.VolumeUp, null, tint = Purple)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text("Voice replies"); Text("Chat likhne par MJ bolegi", color = TextMuted, fontSize = 12.sp) }
                        Switch(checked = voiceOutput, onCheckedChange = { onVoiceToggle() })
                    }
                }
            }
        }
    }
}
