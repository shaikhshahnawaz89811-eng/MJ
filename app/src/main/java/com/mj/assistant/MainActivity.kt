package com.mj.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.content.Intent
import android.speech.RecognizerIntent
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.mj.assistant.ai.QwenModelEngine
import com.mj.assistant.ai.Qwen17BModelEngine
import com.mj.assistant.ai.Qwen25ModelEngine
import com.mj.assistant.ai.ChatHistoryStore
import com.mj.assistant.ai.ConversationMemory
import com.mj.assistant.commands.CommandContext
import com.mj.assistant.commands.DeviceCommandRegistry
import com.mj.assistant.commands.currentTimeNatural
import com.mj.assistant.commands.currentDateNatural
import com.mj.assistant.brain.ActionResult
import com.mj.assistant.brain.BrainDeviceContext
import com.mj.assistant.brain.BrainOrchestrator
import com.mj.assistant.brain.BrainOutcome
import com.mj.assistant.brain.BrainSecureStore

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

private fun ChatMessage.toPersisted() = ChatHistoryStore.PersistedMessage(
    id = id, text = text, fromUser = fromUser,
    actionType = action?.type, actionEnabled = action?.enabled ?: false
)

private fun ChatHistoryStore.PersistedMessage.toChatMessage() = ChatMessage(
    id = id, text = text, fromUser = fromUser,
    action = actionType?.let { ActionState(it, actionEnabled) },
    animate = false
)

private data class ActionState(val type: String, val enabled: Boolean)

/** UI-neutral model state used by ChatScreen so either Qwen engine can drive the same chat UI. */
private data class ChatModelState(
    val status: QwenModelEngine.ModelState.Status,
    val message: String,
    val error: String?
)

private fun QwenModelEngine.ModelState.toChatModelState() =
    ChatModelState(status = status, message = message, error = error)

private fun Qwen17BModelEngine.ModelState.toChatModelState() =
    ChatModelState(
        status = QwenModelEngine.ModelState.Status.valueOf(status.name),
        message = message,
        error = error
    )

private fun Qwen25ModelEngine.ModelState.toChatModelState() =
    ChatModelState(
        status = QwenModelEngine.ModelState.Status.valueOf(status.name),
        message = message,
        error = error
    )

/** Which of the three GGUF model slots currently drives chat. */
private enum class ModelSlot { FOUR_B, ONE_SEVEN_B, TWO_FIVE_3B }

/**
 * Phase-UI fix (audit §11 "orb state differentiation — requirement not
 * met"): MessageOrb previously took zero parameters and looked/animated
 * identically in ThinkingRow, next to a settled reply, and in ProfileScreen.
 * This gives it real, verifiable states instead of invented ones — THINKING
 * drives the existing ThinkingRow usage, SPEAKING is wired to TextToSpeech's
 * actual UtteranceProgressListener (not a guess), ERROR reflects a real
 * ModelState.error, IDLE is the default/unchanged look.
 */
private enum class OrbState { IDLE, THINKING, SPEAKING, ERROR }


private fun sanitizeForSpeech(text: String): String {
    val out = StringBuilder(text.length)
    text.codePoints().forEach { cp ->
        val emoji = (cp in 0x1F1E6..0x1F1FF) ||
            (cp in 0x1F300..0x1FAFF) ||
            (cp in 0x2600..0x27BF) ||
            (cp in 0x2300..0x23FF) ||
            cp == 0x200D || cp == 0xFE0F || (cp in 0x1F3FB..0x1F3FF)
        if (!emoji) out.appendCodePoint(cp) else out.append(' ')
    }
    return out.toString().replace(Regex("\\s+"), " ").trim()
}

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
    var listening by remember { mutableStateOf(false) }
    var voiceOutput by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf("") }
    var replying by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var ttsReady by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current
    var pendingTorch by remember { mutableStateOf<Boolean?>(null) }
    var voicePermissionGranted by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        voicePermissionGranted = granted
        if (!granted) {
            // Fix (Bug 1): "listening" was only ever reset to false inside the
            // speech-recognition result callback, which never runs if the mic
            // permission is denied. Without this explicit failure path, the
            // top bar gets stuck showing "Listening…" until the app restarts.
            listening = false
        }
    }
    val scope = rememberCoroutineScope()
    val modelEngine = remember { QwenModelEngine(context) }
    val midModelEngine = remember { Qwen17BModelEngine(context) }
    val qwen25ModelEngine = remember { Qwen25ModelEngine(context) }
    val modelState by modelEngine.state.collectAsState()
    val midModelState by midModelEngine.state.collectAsState()
    val qwen25ModelState by qwen25ModelEngine.state.collectAsState()
    var selectedModelSlot by remember { mutableStateOf(ModelSlot.ONE_SEVEN_B) }
    val activeModelState: ChatModelState = when (selectedModelSlot) {
        ModelSlot.ONE_SEVEN_B -> midModelState.toChatModelState()
        ModelSlot.TWO_FIVE_3B -> qwen25ModelState.toChatModelState()
        ModelSlot.FOUR_B -> modelState.toChatModelState()
    }
    val aiEngine: com.mj.assistant.ai.AIEngine = when (selectedModelSlot) {
        ModelSlot.ONE_SEVEN_B -> midModelEngine
        ModelSlot.TWO_FIVE_3B -> qwen25ModelEngine
        ModelSlot.FOUR_B -> modelEngine
    }

    // Local AI Brain integration (see com.mj.assistant.brain). This is purely
    // additive: when it's off/unconfigured, isEnabled() is false and the
    // onSend flow below behaves exactly as it did before this integration —
    // DeviceCommandRegistry and aiEngine are untouched.
    val brainOrchestrator = remember { BrainOrchestrator(context) }
    var brainEnabled by remember { mutableStateOf(brainOrchestrator.settingsStore().isEnabled()) }
    var brainHasKey by remember { mutableStateOf(brainOrchestrator.settingsStore().hasApiKey()) }

    fun currentBrainDeviceContext(): BrainDeviceContext {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        val volumePct = am?.let {
            val max = it.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            ((it.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / max) * 100).toInt()
        } ?: 0
        val brightnessPct = try {
            val raw = android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
            ((raw / 255f) * 100).toInt()
        } catch (_: Exception) { 0 }
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val wifiOn = runCatching { wm?.isWifiEnabled == true }.getOrDefault(false)
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        val battery = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val bluetoothDevices: List<String> = try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            ) {
                val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
                adapter?.bondedDevices?.mapNotNull { it.name } ?: emptyList()
            } else emptyList()
        } catch (_: Exception) { emptyList() }
        return BrainDeviceContext(
            volume = volumePct,
            brightness = brightnessPct,
            wifi = wifiOn,
            battery = battery,
            bluetoothDevices = bluetoothDevices
        )
    }
    DisposableEffect(modelEngine, midModelEngine, qwen25ModelEngine) {
        onDispose {
            modelEngine.close()
            midModelEngine.close()
            qwen25ModelEngine.close()
        }
    }
    val importModelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch { modelEngine.importModel(uri).onFailure { importError = it.message ?: "Import failed" } }
        }
    }
    val importMidModelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch { midModelEngine.importModel(uri).onFailure { importError = it.message ?: "1.7B model import failed" } }
        }
    }
    val importQwen25ModelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch { qwen25ModelEngine.importModel(uri).onFailure { importError = it.message ?: "2.5B model import failed" } }
        }
    }
    val voiceRecognition = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) text = spoken.trim()
        listening = false
    }
    LaunchedEffect(voicePermissionGranted) {
        if (voicePermissionGranted) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "MJ ko bolo…")
            }
            voiceRecognition.launch(intent)
            voicePermissionGranted = false
        }
    }

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
    var ttsSpeaking by remember { mutableStateOf(false) }
    val tts = remember {
        TextToSpeech(context) { status ->
            // Fix (Bug 7): language/rate are only meaningful once the engine
            // has actually finished initializing. Setting them immediately on
            // construction (before onInit fires) races the async init on some
            // OEM TTS engines and can silently fall back to defaults.
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
            }
        }
    }
    DisposableEffect(tts) {
        // Real "speaking" orb state (audit §11: no distinct speaking visual
        // tied to TTS actually playing existed before). Driven off TTS's own
        // callbacks rather than guessed from timing.
        val listener = object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { ttsSpeaking = true }
            override fun onDone(utteranceId: String?) { ttsSpeaking = false }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { ttsSpeaking = false }
            override fun onError(utteranceId: String?, errorCode: Int) { ttsSpeaking = false }
        }
        tts.setOnUtteranceProgressListener(listener)
        onDispose { }
    }
    LaunchedEffect(ttsReady) {
        if (ttsReady) {
            tts.language = java.util.Locale("hi", "IN")
            tts.setSpeechRate(0.96f)
        }
    }
    DisposableEffect(Unit) { onDispose { tts.stop(); tts.shutdown() } }

    fun speak(text: String, utteranceId: String, force: Boolean = false) {
        // Manual playback can override the voice toggle; automatic narration still
        // respects it. TTS receives a sanitized copy so UI emojis never get spoken.
        if ((voiceOutput || force) && ttsReady) {
            val speechText = sanitizeForSpeech(text)
            if (speechText.isNotBlank()) {
                tts.speak(
                    speechText,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    utteranceId
                )
            }
        }
    }


    val listState = rememberLazyListState()
    val historyStore = remember { ChatHistoryStore(context) }
    val conversationMemory = remember { ConversationMemory(context) }
    var showSettings by remember { mutableStateOf(false) }

    fun welcomeMessage() = ChatMessage(
        text = "Assalamu Alaikum, main MJ hoon. Aap jo kahenge, main samajh kar jawab dungi.",
        fromUser = false,
        animate = false
    )

    // Phase 3 fix: the visible transcript now survives process death / app
    // relaunch, loaded once from ChatHistoryStore instead of always starting
    // from just the welcome message. See ChatHistoryStore's doc comment.
    var messages by remember {
        mutableStateOf(
            historyStore.load().map { it.toChatMessage() }.ifEmpty { listOf(welcomeMessage()) }
        )
    }

    LaunchedEffect(messages) {
        historyStore.save(messages.map { it.toPersisted() })
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
                    voiceOutput = voiceOutput,
                    onVoiceToggle = { voiceOutput = !voiceOutput },
                    onSettings = { showSettings = true }
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
                                modelState = activeModelState,
                                listening = listening,
                                            onTextChange = { text = it },
                                onCopy = { value -> clipboard.setText(AnnotatedString(value)) },
                                onSend = {
                                    if (text.isNotBlank() && !replying) {
                                        val command = text.trim()
                                        keyboard?.hide()
                                        messages = messages + ChatMessage(text = command, fromUser = true)
                                        text = ""
                                        replying = true
                                        scope.launch {
                                            try {
                                                // Phase 3 fix: the inline keyword if/else chain now lives in
                                                // DeviceCommandRegistry (see commands/DeviceCommands.kt) as a set
                                                // of independent, extensible DeviceCommand units. Multiple
                                                // commands can still fire from one message.
                                                val cmdContext = CommandContext(
                                                    androidContext = context,
                                                    torchOn = torchOn,
                                                    setTorch = { target -> val ok = requestOrSetTorch(target); if (ok) torchOn = target; ok },
                                                    launchIntent = { intent -> runCatching { context.startActivity(intent); true }.getOrDefault(false) }
                                                )
                                                val matched = DeviceCommandRegistry.runAll(command, cmdContext)
                                                val commandText = matched.outcomes.joinToString(" ") { it.responseText }
                                                val action = matched.outcomes.lastOrNull { it.actionType != null }
                                                    ?.let { ActionState(it.actionType!!, it.actionEnabled) }

                                                // Phase 4 "tool calling"-style merge: if a command fired but the
                                                // message clearly carries more than just the command (a longer,
                                                // mixed message), still ask the AI for the rest instead of
                                                // silently dropping it — this is exactly the gap the audit
                                                // flagged (§10) where a command-only fast path made the AI
                                                // unreachable even for messages that deserved a real reply.
                                                val messageIsJustTheCommand = command.trim().split(Regex("\\s+")).size <= 8
                                                val needsAiToo = matched.outcomes.isEmpty() || !messageIsJustTheCommand

                                                if (matched.outcomes.isNotEmpty() && !needsAiToo) {
                                                    messages = messages + ChatMessage(text = commandText, fromUser = false, action = action)
                                                    speak(commandText, "mj_action")
                                                } else {
                                                    // Local AI Brain: tried first (only if the user has enabled it
                                                    // and configured a key in Settings), for the same "leftover"
                                                    // text that would otherwise have gone to the on-device model.
                                                    // On ANY failure (disabled, unreachable, bad response) this
                                                    // falls through to the exact same aiEngine.reply(command) path
                                                    // that ran before this integration existed — nothing about the
                                                    // old behaviour changes when the Brain is off or unavailable.
                                                    val brainResult = if (brainEnabled && brainHasKey) {
                                                        brainOrchestrator.handle(
                                                            message = command,
                                                            deviceContext = currentBrainDeviceContext(),
                                                            launchIntent = { intent -> runCatching { context.startActivity(intent); true }.getOrDefault(false) }
                                                        )
                                                    } else {
                                                        BrainOutcome.Fallback("brain off")
                                                    }

                                                    when (brainResult) {
                                                        is BrainOutcome.Handled -> {
                                                            val combined = if (commandText.isNotBlank()) "$commandText ${brainResult.text}" else brainResult.text
                                                            val brainAction = brainResult.action?.let { ActionState(it.actionType, it.actionEnabled) } ?: action
                                                            messages = messages + ChatMessage(text = combined, fromUser = false, action = brainAction)
                                                            speak(combined, "mj_brain_reply")
                                                        }
                                                        is BrainOutcome.NeedsClarification -> {
                                                            messages = messages + ChatMessage(text = brainResult.text, fromUser = false, action = action)
                                                            speak(brainResult.text, "mj_brain_question")
                                                        }
                                                        is BrainOutcome.Fallback -> {
                                                            val aiAnswer = aiEngine.reply(command)
                                                            val combined = if (commandText.isNotBlank()) "$commandText $aiAnswer" else aiAnswer
                                                            messages = messages + ChatMessage(text = combined, fromUser = false, action = action)
                                                            speak(combined, "mj_reply")
                                                        }
                                                    }
                                                }
                                            } finally {
                                                replying = false
                                            }
                                        }
                                    }
                                },
                                onMic = {
                                    if (!listening) {
                                        listening = true
                                    }
                                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                        audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                                    } else {
                                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                                            putExtra(RecognizerIntent.EXTRA_PROMPT, "MJ ko bolo…")
                                        }
                                        voiceRecognition.launch(intent)
                                    }
                                },
                                onSpeak = { answer -> speak(answer, "mj_manual_${System.nanoTime()}", force = true) },
                                torchOn = torchOn,
                                onTorchToggle = {
                                    val target = !torchOn
                                    val ok = requestOrSetTorch(target)
                                    if (ok) torchOn = target
                                },
                                ttsSpeaking = ttsSpeaking,
                                hasError = activeModelState.error != null
                            )
                            1 -> HistoryScreen(messages)
                            2 -> ActionsScreen(torchOn, {
                                val target = !torchOn
                                if (requestOrSetTorch(target)) torchOn = target
                            })
                            else -> ProfileScreen(
                                voiceOutput = voiceOutput,
                                onVoiceToggle = { voiceOutput = !voiceOutput },
                                modelState = modelState,
                                midModelState = midModelState,
                                qwen25ModelState = qwen25ModelState,
                                selectedModelSlot = selectedModelSlot,
                                onSelectModel = { selectedModelSlot = it },
                                onImport = { importModelLauncher.launch(arrayOf("application/octet-stream", "application/x-gguf", "*/*")) },
                                onLoad = { scope.launch { modelEngine.load().onFailure { importError = it.message ?: "Load failed" } } },
                                onUnload = { scope.launch { modelEngine.unload().onFailure { importError = it.message ?: "Unload failed" } } },
                                onDelete = { scope.launch { modelEngine.delete().onFailure { importError = it.message ?: "Delete failed" } } },
                                onMidImport = { importMidModelLauncher.launch(arrayOf("application/octet-stream", "application/x-gguf", "*/*")) },
                                onMidLoad = { scope.launch { midModelEngine.load().onFailure { importError = it.message ?: "1.7B model load failed" } } },
                                onMidUnload = { scope.launch { midModelEngine.unload().onFailure { importError = it.message ?: "1.7B model unload failed" } } },
                                onMidDelete = { scope.launch { midModelEngine.delete().onFailure { importError = it.message ?: "1.7B model delete failed" } } },
                                onQwen25Import = { importQwen25ModelLauncher.launch(arrayOf("application/octet-stream", "application/x-gguf", "*/*")) },
                                onQwen25Load = { scope.launch { qwen25ModelEngine.load().onFailure { importError = it.message ?: "2.5B model load failed" } } },
                                onQwen25Unload = { scope.launch { qwen25ModelEngine.unload().onFailure { importError = it.message ?: "2.5B model unload failed" } } },
                                onQwen25Delete = { scope.launch { qwen25ModelEngine.delete().onFailure { importError = it.message ?: "2.5B model delete failed" } } },
                                error = importError,
                                clearError = { importError = null }
                            )
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

    if (showSettings) {
        SettingsScreen(
            voiceOutput = voiceOutput,
            onVoiceToggle = { voiceOutput = !voiceOutput },
            onClearChat = {
                messages = listOf(welcomeMessage())
                historyStore.clear()
            },
            onClearMemory = { conversationMemory.clearMemory() },
            brainEnabled = brainEnabled,
            brainHasKey = brainHasKey,
            brainBaseUrl = brainOrchestrator.settingsStore().getBaseUrl(),
            onBrainToggle = { target ->
                brainEnabled = target
                brainOrchestrator.settingsStore().setEnabled(target)
            },
            onBrainSaveKey = { key, url ->
                brainOrchestrator.settingsStore().setBaseUrl(url)
                val saved = brainOrchestrator.settingsStore().saveApiKey(key)
                if (saved) brainHasKey = true
                saved
            },
            onBrainClearKey = {
                brainOrchestrator.settingsStore().clearApiKey()
                brainHasKey = false
                brainEnabled = false
                brainOrchestrator.settingsStore().setEnabled(false)
            },
            onDismiss = { showSettings = false }
        )
    }
}

@Composable
private fun SettingsScreen(
    voiceOutput: Boolean,
    onVoiceToggle: () -> Unit,
    onClearChat: () -> Unit,
    onClearMemory: () -> Unit,
    brainEnabled: Boolean,
    brainHasKey: Boolean,
    brainBaseUrl: String,
    onBrainToggle: (Boolean) -> Unit,
    onBrainSaveKey: (String, String) -> Boolean,
    onBrainClearKey: () -> Unit,
    onDismiss: () -> Unit
) {
    // Fix: the gear icon in TopBar previously had an empty onClick lambda —
    // a pure dead button with no screen behind it (audit §11). This also
    // wires up ConversationMemory.clearMemory(), which existed in code but
    // was never called from any UI element.
    var clearChatConfirm by remember { mutableStateOf(false) }
    var clearMemoryConfirm by remember { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = Surface) {
            Column(Modifier.padding(20.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.ArrowBack, "Close") }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.VolumeUp, null, tint = Purple)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) { Text("Voice replies"); Text(if (voiceOutput) "On" else "Off", color = TextMuted, fontSize = 12.sp) }
                    Switch(checked = voiceOutput, onCheckedChange = { onVoiceToggle() })
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = Color.White.copy(.06f))
                Spacer(Modifier.height(16.dp))
                Text("Data", color = TextMuted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { clearChatConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Clear visible chat")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { clearMemoryConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Clear MJ's long-term memory")
                }
                Text(
                    "Long-term memory mein remembered facts aur relationships hain — chat history se alag.",
                    color = TextMuted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 6.dp)
                )
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = Color.White.copy(.06f))
                Spacer(Modifier.height(16.dp))
                Text("Local AI Brain", color = TextMuted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                var keyInput by remember { mutableStateOf("") }
                var urlInput by remember { mutableStateOf(brainBaseUrl) }
                var saveMsg by remember { mutableStateOf<String?>(null) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Brain se commands bhejein")
                        Text(
                            if (brainEnabled && brainHasKey) "On — configured"
                            else if (brainHasKey) "Off — key saved"
                            else "Off — key set nahi hai",
                            color = TextMuted, fontSize = 12.sp
                        )
                    }
                    Switch(checked = brainEnabled && brainHasKey, enabled = brainHasKey, onCheckedChange = { onBrainToggle(it) })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("Brain URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it; saveMsg = null },
                    label = { Text(if (brainHasKey) "New API key (leave blank to keep current)" else "API key") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            if (keyInput.isBlank()) {
                                saveMsg = "Key khaali nahi ho sakti."
                            } else {
                                val ok = onBrainSaveKey(keyInput, urlInput)
                                saveMsg = if (ok) "Saved." else "Save nahi ho saka."
                                if (ok) keyInput = ""
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Save key") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { onBrainClearKey(); saveMsg = "Key removed." },
                        modifier = Modifier.weight(1f)
                    ) { Text("Remove key") }
                }
                saveMsg?.let {
                    Text(it, color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Text(
                    "API key encrypted rehti hai (Android Keystore) aur kabhi bhi logs ya code mein nahi likhi jaati. " +
                        "Brain sirf $urlInput (local network) se baat karta hai.",
                    color = TextMuted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
    if (clearChatConfirm) {
        AlertDialog(
            onDismissRequest = { clearChatConfirm = false },
            title = { Text("Chat clear karein?") },
            text = { Text("Visible chat history hamesha ke liye delete ho jayegi.") },
            confirmButton = { TextButton(onClick = { onClearChat(); clearChatConfirm = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { clearChatConfirm = false }) { Text("Cancel") } }
        )
    }
    if (clearMemoryConfirm) {
        AlertDialog(
            onDismissRequest = { clearMemoryConfirm = false },
            title = { Text("Memory clear karein?") },
            text = { Text("MJ ke yaad rakhe hue saare facts aur relationships hamesha ke liye delete ho jayenge.") },
            confirmButton = { TextButton(onClick = { onClearMemory(); clearMemoryConfirm = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { clearMemoryConfirm = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun TopBar(
    showBack: Boolean,
    onBack: () -> Unit,
    listening: Boolean,
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
                Text(if (listening) "Listening…" else "Mic ready", color = TextMuted, fontSize = 12.sp)
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
    modelState: ChatModelState,
    listening: Boolean,
    onTextChange: (String) -> Unit,
    onCopy: (String) -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
    onSpeak: (String) -> Unit,
    torchOn: Boolean,
    onTorchToggle: () -> Unit,
    ttsSpeaking: Boolean = false,
    hasError: Boolean = false
) {
    val latestAssistantIndex = if (replying) -1 else messages.indexOfLast { !it.fromUser }

    Column(Modifier.fillMaxSize()) {
        if (messages.size == 1) {
            PresenceHero(listening, modelState.status == QwenModelEngine.ModelState.Status.LOADED)
        }

        if (modelState.status != QwenModelEngine.ModelState.Status.LOADED) {
            ModelChatBanner(modelState)
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
                    onCopy = onCopy,
                    onSpeak = onSpeak,
                    orbState = when {
                        hasError -> OrbState.ERROR
                        ttsSpeaking && !replying && index == latestAssistantIndex -> OrbState.SPEAKING
                        else -> OrbState.IDLE
                    }
                )
            }
            if (replying) item(key = "thinking") { ThinkingRow() }
        }

        Composer(
            text = text,
            onTextChange = onTextChange,
            onMic = onMic,
            onSend = onSend,
            enabled = !replying && modelState.status !in setOf(QwenModelEngine.ModelState.Status.IMPORTING, QwenModelEngine.ModelState.Status.LOADING, QwenModelEngine.ModelState.Status.UNLOADING, QwenModelEngine.ModelState.Status.DELETING)
        )
    }
}

@Composable
private fun PresenceHero(listening: Boolean, modelLoaded: Boolean) {
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
            if (modelLoaded) "Qwen3 4B Thinking ready" else "Import / load Qwen3 model",
            color = if (modelLoaded) Green else TextMuted,
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
            // Rotate the COMPLETE orb drawing. The sphere, glow, light streak and
            // particle ring all move together; this is intentionally not a
            // stationary orb with only its outer dots rotating.
            rotate(degrees = rotation, pivot = center) {
                val c = center
                val r = 25.dp.toPx() * pulse
                drawCircle(Brush.radialGradient(listOf(Pink.copy(.25f), Purple.copy(.12f), Color.Transparent)), r * 1.9f, c)
                drawCircle(Brush.radialGradient(listOf(Purple.copy(.85f), Purple2.copy(.55f), Color(0xFF211330))), r, c)
                for (i in 0 until 44) {
                    val a = Math.toRadians((i * 15.0).toDouble())
                    val r1 = r + 5.dp.toPx()
                    val r2 = r + 9.dp.toPx() + (i % 4) * 1.6.dp.toPx()
                    drawLine(
                        color = Purple.copy(if (active) .48f else .18f),
                        start = Offset(x = c.x + kotlin.math.cos(a).toFloat() * r1, y = c.y + kotlin.math.sin(a).toFloat() * r1),
                        end = Offset(x = c.x + kotlin.math.cos(a).toFloat() * r2, y = c.y + kotlin.math.sin(a).toFloat() * r2),
                        strokeWidth = 1.4.dp.toPx(), cap = StrokeCap.Round
                    )
                }
                // A non-symmetric inner highlight makes the orb's own rotation visible;
                // the sphere is not a static circle behind a moving outer ring.
                val arcRadius = r * .62f
                val arcRect = androidx.compose.ui.geometry.Rect(
                    c.x - arcRadius, c.y - arcRadius, c.x + arcRadius, c.y + arcRadius
                )
                drawArc(
                    brush = Brush.sweepGradient(listOf(Color.Transparent, Pink.copy(.72f), Purple.copy(.9f), Color.Transparent)),
                    startAngle = rotation,
                    sweepAngle = 105f,
                    useCenter = false,
                    topLeft = Offset(arcRect.left, arcRect.top),
                    size = Size(arcRect.width, arcRect.height),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                )
                val bx = size.width * (.22f + (glass + 1f) * .39f)
                drawOval(
                    brush = Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(.13f), Color.Transparent)),
                    topLeft = Offset(x = bx, y = c.y - r * .82f),
                    size = Size(width = 34.dp.toPx(), height = r * 1.64f)
                )
            }
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
    onCopy: (String) -> Unit,
    onSpeak: (String) -> Unit,
    orbState: OrbState = OrbState.IDLE
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
                    MessageOrb(orbState)
                    Spacer(Modifier.width(7.dp))
                } else {
                    Spacer(Modifier.width(31.dp))
                }
                Column(Modifier.widthIn(max = 315.dp)) {
                    AssistantMessage(msg.text, onCopy, onSpeak)
                    // DeviceActionCard is a real torch control. Other command
                    // outcomes (Battery/Wi-Fi/Alarm/Call/Calendar) must never
                    // be wired to the torch callback; doing that would make a
                    // non-torch response look like an ON/OFF switch and could
                    // toggle the camera torch when the user taps it.
                    if (msg.action?.type == "Torch") {
                        Spacer(Modifier.height(8.dp))
                        DeviceActionCard("Torch", torchOn, onTorchToggle)
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
private fun AssistantMessage(text: String, onCopy: (String) -> Unit, onSpeak: (String) -> Unit) {
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
            IconButton(
                onClick = { onCopy(text) },
                modifier = Modifier.size(26.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = "Copy MJ reply",
                    modifier = Modifier.size(14.dp),
                    tint = TextMuted
                )
            }
        }
        Text(
            text = text,
            color = TextMain,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            modifier = Modifier.padding(vertical = 1.dp)
        )
    }
}

@Composable
private fun MessageOrb(state: OrbState = OrbState.IDLE) {
    val t = rememberInfiniteTransition(label = "sideOrb")
    val speed = when (state) { OrbState.THINKING -> 900; OrbState.SPEAKING -> 500; else -> 1400 }
    val glow by t.animateFloat(.62f, 1f, infiniteRepeatable(tween(speed), RepeatMode.Reverse), label = "glow")
    val rotSpeed = if (state == OrbState.THINKING || state == OrbState.SPEAKING) 1400 else 2400
    val rotation by t.animateFloat(0f, 360f, infiniteRepeatable(tween(rotSpeed, easing = LinearEasing)), label = "orbRotation")
    val coreColors = when (state) {
        OrbState.ERROR -> listOf(Color(0xFFFF8A9A).copy(.95f), Color(0xFFB9426C).copy(.72f), Color(0xFF2A1418))
        OrbState.SPEAKING -> listOf(Pink.copy(1f), Purple.copy(.85f), Color(0xFF29163C))
        OrbState.THINKING -> listOf(Purple.copy(.95f), Purple2.copy(.8f), Color(0xFF211330))
        OrbState.IDLE -> listOf(Pink.copy(.95f), Purple.copy(.72f), Color(0xFF29163C))
    }
    val ringColor = if (state == OrbState.ERROR) Color(0xFFFF8A9A) else Pink

    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            drawCircle(Purple.copy(alpha = .13f * glow), radius = 12.dp.toPx(), center = c)

            // The whole ring rotates, not only a single dot.
            for (i in 0 until 12) {
                val a = Math.toRadians((i * 30.0 + rotation).toDouble())
                val r = 8.5.dp.toPx()
                val dot = if (i % 3 == 0) 1.35.dp.toPx() else 0.9.dp.toPx()
                drawCircle(
                    color = if (i % 3 == 0) ringColor.copy(.85f) else Purple.copy(.55f),
                    radius = dot,
                    center = Offset(
                        x = c.x + kotlin.math.cos(a).toFloat() * r,
                        y = c.y + kotlin.math.sin(a).toFloat() * r
                    )
                )
            }
            drawCircle(
                Brush.radialGradient(coreColors),
                radius = 5.2.dp.toPx(),
                center = c
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
private fun ModelChatBanner(state: ChatModelState) {
    val working = state.status in setOf(
        QwenModelEngine.ModelState.Status.IMPORTING,
        QwenModelEngine.ModelState.Status.LOADING,
        QwenModelEngine.ModelState.Status.UNLOADING,
        QwenModelEngine.ModelState.Status.DELETING
    )
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (working) Purple.copy(.08f) else Surface
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (working) Icons.Outlined.Memory else Icons.Outlined.Info, null, tint = if (working) Purple else TextMuted, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(state.message, color = TextMain, fontSize = 12.sp, maxLines = 2)
                if (state.error != null) Text(state.error, color = Color(0xFFFF9BAA), fontSize = 10.sp, maxLines = 2)
            }
        }
    }
}

@Composable
private fun ThinkingRow() {
    val transition = rememberInfiniteTransition(label = "thinkingGlass")
    val phase by transition.animateFloat(
        -1f, 1f,
        infiniteRepeatable(tween(1350, easing = LinearEasing), RepeatMode.Restart),
        label = "thinkingGlassPhase"
    )
    val pulse by transition.animateFloat(
        0.98f, 1.02f,
        infiniteRepeatable(tween(760, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "thinkingGlassPulse"
    )

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // MJ's orb remains outside the glass effect.
        MessageOrb(OrbState.THINKING)
        Spacer(Modifier.width(7.dp))

        // Small glass capsule only around the animated dots + "MJ soch rahi hai…" text.
        Box(
            Modifier
                .graphicsLayer { scaleX = pulse; scaleY = pulse }
                .clip(RoundedCornerShape(13.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.045f),
                            Purple.copy(alpha = 0.065f),
                            Color.White.copy(alpha = 0.025f)
                        )
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(13.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Canvas(Modifier.matchParentSize()) {
                val x = size.width * (0.10f + (phase + 1f) * 0.40f)
                drawRect(
                    brush = Brush.linearGradient(
                        listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.11f),
                            Color.Transparent
                        ),
                        start = Offset(x - 55f, 0f),
                        end = Offset(x + 55f, size.height)
                    )
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                repeat(3) { i ->
                    val dotTransition = rememberInfiniteTransition(label = "thinkDot$i")
                    val y by dotTransition.animateFloat(
                        0f, -3f,
                        infiniteRepeatable(tween(420, delayMillis = i * 95), RepeatMode.Reverse),
                        label = "dot$i"
                    )
                    Box(
                        Modifier
                            .offset(y = y.dp)
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(Purple)
                    )
                    if (i < 2) Spacer(Modifier.width(3.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text("MJ soch rahi hai…", color = TextMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Composer(text: String, onTextChange: (String) -> Unit, onMic: () -> Unit, onSend: () -> Unit, enabled: Boolean) {
    Row(
        Modifier.fillMaxWidth().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        IconButton(onClick = onMic, enabled = enabled, modifier = Modifier.size(46.dp).clip(CircleShape).background(Surface)) {
            Icon(Icons.Outlined.Mic, contentDescription = "Microphone", modifier = Modifier.size(22.dp), tint = Purple)
        }
        Spacer(Modifier.width(7.dp))
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            enabled = enabled,
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
        FloatingActionButton(onClick = onSend, modifier = Modifier.size(48.dp), containerColor = if (enabled) Purple else Surface3, contentColor = if (enabled) Color.White else TextMuted) {
            Icon(Icons.Outlined.ArrowUpward, "Send")
        }
    }
}

@Composable
private fun HistoryScreen(messages: List<ChatMessage>) {
    // Fix (audit §3/§11): History previously showed only user messages (no
    // assistant replies), which is a poor "history" of a conversation. It
    // now shows both sides, and — since ChatHistoryStore persists the
    // transcript now — this is genuinely historical rather than
    // session-only.
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("History", fontSize = 23.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp)) }
        items(messages.reversed()) { msg ->
            Surface(shape = RoundedCornerShape(17.dp), color = Surface) {
                ListItem(
                    headlineContent = { Text(msg.text, maxLines = 2) },
                    supportingContent = { Text(if (msg.fromUser) "Aap" else "MJ") },
                    leadingContent = { Icon(if (msg.fromUser) Icons.Outlined.PersonOutline else Icons.Outlined.History, null, tint = Purple) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }
    }
}

@Composable
private fun ActionsScreen(torchOn: Boolean, onTorchToggle: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Fix (audit §11 "dead UI"): these six rows previously rendered with no
    // .clickable modifier at all — purely decorative. They now open the
    // matching real system panel (Android 10+/API 29+ forbids apps from
    // programmatically toggling Wi-Fi/Bluetooth/mobile data, so opening the
    // real settings panel is the honest behaviour, not a fake toggle) or show
    // a live value read from the device.
    var time by remember { mutableStateOf(currentTimeNatural()) }
    var date by remember { mutableStateOf(currentDateNatural()) }
    var batteryText by remember { mutableStateOf("Tap to check") }
    LaunchedEffect(Unit) {
        while (true) {
            time = currentTimeNatural()
            date = currentDateNatural()
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            val pct = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (pct in 0..100) batteryText = "$pct%"
            delay(30_000)
        }
    }
    fun open(action: String) = runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Quick Actions", fontSize = 23.sp, fontWeight = FontWeight.SemiBold); Text("Device controls", color = TextMuted, fontSize = 12.sp); Spacer(Modifier.height(5.dp)) }
        item { DeviceActionCard("Torch", torchOn, onTorchToggle) }
        item { ActionCard("Wi‑Fi", Icons.Outlined.Wifi, "Settings kholein") { open(android.provider.Settings.ACTION_WIFI_SETTINGS) } }
        item { ActionCard("Bluetooth", Icons.Outlined.Bluetooth, "Settings kholein") { open(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS) } }
        item { ActionCard("Mobile Data", Icons.Outlined.SignalCellularAlt, "Settings kholein") { open(android.provider.Settings.ACTION_DATA_ROAMING_SETTINGS) } }
        item { ActionCard("Battery", Icons.Outlined.BatteryFull, batteryText) { open(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS) } }
        item { ActionCard("Time", Icons.Outlined.Schedule, time) { open(android.provider.AlarmClock.ACTION_SHOW_ALARMS) } }
        item {
            ActionCard("Date", Icons.Outlined.CalendarToday, date) {
                runCatching { context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }
}

@Composable
private fun ActionCard(name: String, icon: androidx.compose.ui.graphics.vector.ImageVector, sub: String, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(17.dp), color = Surface) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Purple.copy(.11f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Purple) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text(name, fontWeight = FontWeight.Medium); Text(sub, color = TextMuted, fontSize = 12.sp) }
            Icon(Icons.Outlined.ChevronRight, null, tint = TextMuted)
        }
    }
}

@Composable
private fun ProfileScreen(
    voiceOutput: Boolean,
    onVoiceToggle: () -> Unit,
    modelState: QwenModelEngine.ModelState,
    midModelState: Qwen17BModelEngine.ModelState,
    qwen25ModelState: Qwen25ModelEngine.ModelState,
    selectedModelSlot: ModelSlot,
    onSelectModel: (ModelSlot) -> Unit,
    onImport: () -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onDelete: () -> Unit,
    onMidImport: () -> Unit,
    onMidLoad: () -> Unit,
    onMidUnload: () -> Unit,
    onMidDelete: () -> Unit,
    onQwen25Import: () -> Unit,
    onQwen25Load: () -> Unit,
    onQwen25Unload: () -> Unit,
    onQwen25Delete: () -> Unit,
    error: String?,
    clearError: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Profile", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = Surface) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MessageOrb()
                        Spacer(Modifier.width(12.dp))
                        Column { Text("MJ", fontSize = 21.sp, fontWeight = FontWeight.SemiBold); Text("Private on-device assistant", color = TextMuted, fontSize = 12.sp) }
                    }
                    Spacer(Modifier.height(18.dp))
                    HorizontalDivider(color = Color.White.copy(.05f))
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.VolumeUp, null, tint = Purple)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text("Voice replies"); Text(if (voiceOutput) "MJ chat ke saath bolegi" else "MJ sirf chat mein reply karegi", color = TextMuted, fontSize = 12.sp) }
                        Switch(checked = voiceOutput, onCheckedChange = { onVoiceToggle() })
                    }
                }
            }
        }
        item {
            ActiveModelPicker(selectedModelSlot, onSelectModel)
        }
        item {
            ModelManagerCard(
                title = "Qwen3 4B Thinking",
                subtitle = "Main reasoning model • GGUF",
                state = modelState,
                onImport = onImport,
                onLoad = onLoad,
                onUnload = onUnload,
                onDelete = onDelete
            )
        }
        item {
            ModelManagerCard(
                title = "Qwen3 1.7B Fast",
                subtitle = "Fast middle model • same chat, memory & actions",
                state = midModelState,
                onImport = onMidImport,
                onLoad = onMidLoad,
                onUnload = onMidUnload,
                onDelete = onMidDelete
            )
        }
        item {
            ModelManagerCard(
                title = "Qwen2.5 3B",
                subtitle = "Extra fast slot • same chat, memory & actions",
                state = qwen25ModelState,
                onImport = onQwen25Import,
                onLoad = onQwen25Load,
                onUnload = onQwen25Unload,
                onDelete = onQwen25Delete
            )
        }
        if (error != null) {
            item {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(17.dp), color = Color(0xFF2A151A)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Outlined.ErrorOutline, null, tint = Color(0xFFFF8A9A))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(error, color = TextMain, fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = clearError) { Text("Dismiss") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveModelPicker(selectedModelSlot: ModelSlot, onSelectModel: (ModelSlot) -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Surface) {
        Column(Modifier.padding(14.dp)) {
            Text("Active chat model", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Text("Ek waqt mein sirf ek GGUF model native memory mein load rahega.", color = TextMuted, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = selectedModelSlot == ModelSlot.FOUR_B, onClick = { onSelectModel(ModelSlot.FOUR_B) }, label = { Text("4B Thinking") }, leadingIcon = { Icon(Icons.Outlined.Psychology, null, Modifier.size(16.dp)) })
                FilterChip(selected = selectedModelSlot == ModelSlot.ONE_SEVEN_B, onClick = { onSelectModel(ModelSlot.ONE_SEVEN_B) }, label = { Text("1.7B Fast") }, leadingIcon = { Icon(Icons.Outlined.Bolt, null, Modifier.size(16.dp)) })
                FilterChip(selected = selectedModelSlot == ModelSlot.TWO_FIVE_3B, onClick = { onSelectModel(ModelSlot.TWO_FIVE_3B) }, label = { Text("2.5 3B") }, leadingIcon = { Icon(Icons.Outlined.Speed, null, Modifier.size(16.dp)) })
            }
        }
    }
}

private data class ModelCardUiState(
    val name: String?, val sizeBytes: Long, val copiedBytes: Long, val progress: Float,
    val status: String, val message: String, val error: String?
)

@Composable
private fun ModelManagerCard(
    title: String, subtitle: String,
    state: QwenModelEngine.ModelState,
    onImport: () -> Unit, onLoad: () -> Unit, onUnload: () -> Unit, onDelete: () -> Unit
) = ModelManagerCardImpl(title, subtitle, ModelCardUiState(state.name, state.sizeBytes, state.copiedBytes, state.progress, state.status.name, state.message, state.error), onImport, onLoad, onUnload, onDelete)

@Composable
private fun ModelManagerCard(
    title: String, subtitle: String,
    state: Qwen17BModelEngine.ModelState,
    onImport: () -> Unit, onLoad: () -> Unit, onUnload: () -> Unit, onDelete: () -> Unit
) = ModelManagerCardImpl(title, subtitle, ModelCardUiState(state.name, state.sizeBytes, state.copiedBytes, state.progress, state.status.name, state.message, state.error), onImport, onLoad, onUnload, onDelete)

@Composable
private fun ModelManagerCard(
    title: String, subtitle: String,
    state: Qwen25ModelEngine.ModelState,
    onImport: () -> Unit, onLoad: () -> Unit, onUnload: () -> Unit, onDelete: () -> Unit
) = ModelManagerCardImpl(title, subtitle, ModelCardUiState(state.name, state.sizeBytes, state.copiedBytes, state.progress, state.status.name, state.message, state.error), onImport, onLoad, onUnload, onDelete)

@Composable
private fun ModelManagerCardImpl(
    title: String, subtitle: String, state: ModelCardUiState,
    onImport: () -> Unit, onLoad: () -> Unit, onUnload: () -> Unit, onDelete: () -> Unit
) {
    val busy = state.status in setOf("IMPORTING", "LOADING", "UNLOADING", "DELETING")
    val loaded = state.status == "LOADED"
    val ready = state.status == "READY"
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = Surface) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Purple.copy(.12f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Memory, null, tint = Purple, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, color = TextMuted, fontSize = 12.sp)
                }
                ModelStatusPill(state.status)
            }
            Spacer(Modifier.height(14.dp))
            Text(state.name ?: "No model imported", fontWeight = FontWeight.Medium, maxLines = 2)
            if (state.sizeBytes > 0) {
                Spacer(Modifier.height(5.dp))
                Text("Size ${QwenModelEngine.formatBytes(state.sizeBytes)}", color = TextMuted, fontSize = 12.sp)
            }
            if (state.status == "IMPORTING") {
                Spacer(Modifier.height(12.dp))
                if (state.sizeBytes > 0) LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(7.dp))
            Text(state.message, color = if (state.error == null) TextMuted else Color(0xFFFF9BAA), fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onImport, enabled = !busy && !loaded, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.FileOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Import")
                }
                Button(onClick = if (loaded) onUnload else onLoad, enabled = !busy && (ready || loaded), modifier = Modifier.weight(1f)) {
                    Icon(if (loaded) Icons.Outlined.StopCircle else Icons.Outlined.PlayCircle, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (loaded) "Unload" else "Load")
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDelete, enabled = !busy && ready, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("Delete model file")
            }
            Text("Model ko chat se alag manage kiya jata hai. Loaded model delete nahi hoga; pehle Unload karna zaroori hai.", color = TextMuted, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun ModelStatusPill(status: String) {
    val (label, color) = when (status) {
        "LOADED" -> "Loaded" to Green
        "READY" -> "Ready" to Purple
        "IMPORTING", "LOADING", "UNLOADING", "DELETING" -> "Working" to Purple
        "ERROR" -> "Error" to Color(0xFFFF8A9A)
        else -> "Not set" to TextMuted
    }
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = .12f)) {
        Text(label, color = color, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
    }
}
