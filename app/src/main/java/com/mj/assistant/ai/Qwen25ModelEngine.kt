package com.mj.assistant.ai

import android.content.Context
import android.net.Uri
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real on-device Qwen2.5-3B-Instruct GGUF engine. Third selectable model
 * slot alongside [QwenModelEngine] (4B Thinking) and [Qwen17BModelEngine]
 * (1.7B Fast). No canned AI fallback is used.
 *
 * NOTE on thinking mode: unlike Qwen3, Qwen2.5-Instruct has no
 * thinking/non-thinking mode and does not understand the "/think" or
 * "/no_think" control suffixes the Qwen3 engines append to their prompts.
 * This engine deliberately never appends them — doing so would just leak
 * literal "/no_think" text into the prompt and risk the model echoing or
 * reacting to it. Everything else (repetition-loop stripping, script-match
 * retry, phone-sized context/threads) mirrors the 1.7B "fast" tuning since
 * this slot is meant to run as quickly as the 1.7B one on a phone/Termux.
 */
class Qwen25ModelEngine(context: Context) : AIEngine {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("qwen25_model", Context.MODE_PRIVATE)
    private val memory = ConversationMemory(appContext)
    private var model: LlamaModel? = null
    private val operationLock = Mutex()
    private val busy = AtomicBoolean(false)
    private val closeRequested = AtomicBoolean(false)
    private val _state = MutableStateFlow(loadInitialState())

    val state: StateFlow<ModelState> = _state.asStateFlow()

    data class ModelState(
        val name: String? = null,
        val sizeBytes: Long = 0L,
        val copiedBytes: Long = 0L,
        val status: Status = Status.NO_MODEL,
        val message: String = "Qwen2.5 3B model import karo.",
        val error: String? = null,
    ) {
        enum class Status { NO_MODEL, IMPORTING, READY, LOADING, LOADED, UNLOADING, DELETING, ERROR }
        val progress: Float
            get() = if (sizeBytes > 0) (copiedBytes.toFloat() / sizeBytes).coerceIn(0f, 1f) else 0f
    }

    override suspend fun reply(input: String): String {
        val userInput = input.trim()
        if (userInput.isBlank()) return "Hmm, kuch likho na ji 😊"
        if (!busy.compareAndSet(false, true)) return "Ek kaam abhi chal raha hai ji. Bas woh complete hone do 😊"

        return try {
            operationLock.withLock {
                val current = model
                    ?: return@withLock "Abhi Qwen2.5 3B model loaded nahi hai. Profile → Model par jaakar model Load karo."
                if (!current.isLoaded) return@withLock "Qwen2.5 3B model loaded nahi hai. Pehle model Load karo."

                try {
                    val needsContext = shouldUseConversationContext(userInput)
                    val recentLimit = if (needsContext) 2 else 0
                    val recent = if (needsContext) memory.recentTurns(recentLimit) else emptyList()
                    val relevant = if (needsContext) memory.findRelevantTurns(userInput, 1) else emptyList()
                    val facts = if (needsContext || isMemoryRequest(userInput)) memory.allFacts().takeLast(2) else emptyList()
                    val context = buildPromptContext(recent, relevant, facts, maxChars = if (needsContext) 900 else 0)
                    // The llama-android API already applies the GGUF chat template to
                    // systemPrompt + prompt. Do NOT fake role markers like "User:" inside
                    // the user message. Keep the user prompt as an actual user turn, and
                    // do NOT append a "/think" or "/no_think" suffix here — Qwen2.5 has
                    // no thinking mode and doesn't recognize that control syntax.
                    val prompt = if (context.isBlank()) {
                        userInput
                    } else {
                        "Relevant context (use only if it helps):\n$context\n\n$userInput"
                    }
                    val baseTokens = fitGenerationBudget(prompt, generationBudget(userInput), CONTEXT_SIZE)

                    var maxTokens = baseTokens
                    var retryCount = 0
                    var result = withContext(Dispatchers.Default) {
                        Llama.complete(
                            current,
                            prompt = prompt,
                            systemPrompt = MJPersonality.SYSTEM_PROMPT,
                            maxTokens = maxTokens,
                        )
                    }
                    var answer = stripRepetitionLoop(cleanThinkingOutput(result.text))
                    val scriptMismatch = isScriptMismatch(userInput, answer)
                    val needsCorrection = answer.isBlank() || isGenericDeflection(userInput, answer) || isEchoingUser(userInput, answer) || (isCodeRequest(userInput) && !looksLikeCode(answer)) || scriptMismatch

                    // A second full generation is expensive on phone CPU. Only retry
                    // when the first pass is empty or clearly dodges the user's request,
                    // same bounded-retry approach as the 1.7B fast slot.
                    if (needsCorrection && baseTokens <= FAST_RETRY_THRESHOLD) {
                        val scriptInstruction = if (scriptMismatch) {
                            "The user typed in Roman/English letters, not Devanagari script. Reply in Roman Hindi/Hinglish letters ONLY — do not use Devanagari script.\n"
                        } else ""
                        val correctionPrompt = "$scriptInstruction" + "Do not repeat or quote the user's question. Answer it now, directly, in the user's language.\n$userInput"
                        maxTokens = fitGenerationBudget(correctionPrompt, (baseTokens + FAST_RETRY_EXTRA).coerceAtMost(MAX_RETRY_TOKENS), CONTEXT_SIZE)
                        retryCount = 1
                        result = withContext(Dispatchers.Default) {
                            Llama.complete(
                                current,
                                prompt = correctionPrompt,
                                systemPrompt = MJPersonality.SYSTEM_PROMPT,
                                maxTokens = maxTokens,
                            )
                        }
                        answer = stripRepetitionLoop(cleanThinkingOutput(result.text))
                    }
                    val finalAnswer = answer.ifBlank {
                        "Hmm, MJ abhi bhi poora jawab nahi soch paayi ji — chhota/simple sawaal try karo, ya thoda ruk kar dobara poochho."
                    }
                    withContext(Dispatchers.IO) {
                        memory.addRecentTurn(userInput, finalAnswer)
                        captureUsefulMemory(userInput)
                    }
                    val approxOutputTokens = estimateTokens(result.text)
                    val approxInputTokens = estimateTokens(MJPersonality.SYSTEM_PROMPT + prompt)
                    _state.value = _state.value.copy(
                        message = "2.5B loaded • ${result.tokensPerSecond.formatSpeed()} tok/s • ~${approxInputTokens} in / ~${approxOutputTokens} out tok • retry $retryCount",
                        error = null
                    )
                    finalAnswer
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    val msg = t.message?.take(180) ?: "Inference failed"
                    _state.value = _state.value.copy(
                        status = if (model?.isLoaded == true) ModelState.Status.LOADED else ModelState.Status.ERROR,
                        message = "Inference failed",
                        error = msg
                    )
                    "Qwen2.5 se jawab nahi aa saka. Error: $msg"
                }
            }
        } finally {
            busy.set(false)
            if (closeRequested.compareAndSet(true, false)) {
                releaseModelSafely()
            }
        }
    }

    private fun buildPromptContext(
        recent: List<Pair<String, String>>,
        relevant: List<Pair<String, String>>,
        facts: List<ConversationMemory.Fact>,
        maxChars: Int
    ): String {
        if (recent.isEmpty() && relevant.isEmpty() && facts.isEmpty()) return ""
        val out = StringBuilder()
        if (facts.isNotEmpty()) {
            out.append("Known useful memory (only use when relevant):\n")
            facts.forEach { fact ->
                if (out.length < maxChars) out.append("- ").append(fact.value.take(240)).append('\n')
            }
        }
        if (relevant.isNotEmpty() && out.length < maxChars) {
            out.append("Relevant older conversation:\n")
            relevant.forEach { (u, a) ->
                if (out.length < maxChars) {
                    out.append("User: ").append(u.take(320)).append('\n')
                    out.append("MJ: ").append(a.take(320)).append('\n')
                }
            }
        }
        if (recent.isNotEmpty() && out.length < maxChars) {
            out.append("Recent conversation:\n")
            recent.forEach { (u, a) ->
                if (out.length < maxChars) {
                    out.append("User: ").append(u.take(420)).append('\n')
                    out.append("MJ: ").append(a.take(420)).append('\n')
                }
            }
        }
        return out.toString().take(maxChars).trim()
    }

    private fun shouldUseConversationContext(input: String): Boolean {
        val s = input.lowercase(Locale.ROOT)
        if (s.length > 180) return true
        val cues = listOf(
            "ye", "yeh", "wo", "woh", "it", "that", "this", "iske", "uske",
            "pehle", "previous", "upar", "continue", "continue karo", "wahi", "isko", "usko",
            "yaad", "remember"
        )
        return cues.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(s) }
    }

    private fun isMemoryRequest(input: String): Boolean {
        val s = input.lowercase(Locale.ROOT)
        return s.contains("yaad rakh") || s.contains("remember")
    }

    private fun isSimpleConversation(input: String): Boolean {
        val s = input.lowercase(Locale.ROOT).trim()
        if (s.length > 100) return false
        val words = s.split(Regex("\\s+")).filter { it.isNotBlank() }
        val simplePhrases = listOf(
            "hi", "hello", "hey", "hii", "hlo", "salam", "assalamualaikum",
            "kaise ho", "kesi ho", "kaisi ho", "kya haal", "good morning",
            "good night", "good evening", "thanks", "thank you", "shukriya"
        )
        val commonFactStarts = listOf(
            "what is ", "who is ", "where is ", "when is ", "capital of ",
            "how old is ", "kitna hai", "kitni hai", "kaun hai", "kahan hai",
            "kab hai", "kya hai", "time", "date"
        )
        val greeting = simplePhrases.any { s == it || s.startsWith("$it ") }
        val tinyCasual = words.size <= 5 && !s.contains("code") && !s.contains("coding") && !s.contains("termux")
        val tinyFact = words.size <= 9 && commonFactStarts.any { s.startsWith(it) }
        return greeting || tinyCasual || tinyFact
    }

    private fun isPlanningRequest(input: String): Boolean {
        val s = input.lowercase(Locale.ROOT)
        return listOf("plan bana", "planning", "roadmap", "steps bata", "step plan", "kaise karna hai", "kaise karein").any { s.contains(it) }
    }

    private fun needsDeepReasoning(input: String): Boolean {
        val s = input.lowercase(Locale.ROOT).trim()
        val deepCue = listOf(
            "debug", "bug", "error", "fix this code", "why does this fail",
            "algorithm", "solve this", "calculate step by step", "reason",
            "explain why", "step by step", "gradle error", "termux error"
        ).any { s.contains(it) }
        val longCode = (s.contains("python") || s.contains("kotlin") || s.contains("java") || s.contains("code") || s.contains("program")) && s.length > 160
        return deepCue || longCode || s.length > 260
    }

    private fun generationBudget(input: String): Int {
        val s = input.lowercase(Locale.ROOT)
        val words = s.split(Regex("\\s+")).count { it.isNotBlank() }
        return when {
            isSimpleConversation(input) && words <= 5 -> 384
            isSimpleConversation(input) -> 512
            isCodeRequest(input) && s.length <= 180 -> 768
            isPlanningRequest(input) && s.length <= 500 -> 1024
            needsDeepReasoning(input) && s.length <= 220 -> 1536
            s.length <= 260 -> 768
            s.length <= 600 -> 1536
            else -> 2048
        }
    }

    suspend fun importModel(uri: Uri): Result<Unit> = operationLock.withLock { withContext(Dispatchers.IO) {
        if (busy.get() || model != null) return@withContext Result.failure(IllegalStateException("Model abhi busy/loaded hai. Pehle unload karo."))
        val dir = File(appContext.getExternalFilesDir("models") ?: appContext.filesDir, "qwen25_3b")
        dir.mkdirs()
        val resolver = appContext.contentResolver
        val name = resolver.query(uri, arrayOf("_display_name", "_size"), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) to if (!c.isNull(1)) c.getLong(1) else -1L else null
        } ?: ("qwen2.5-3b-instruct.gguf" to -1L)
        val lowerName = name.first.lowercase(Locale.ROOT)
        if (!lowerName.endsWith(".gguf")) {
            return@withContext Result.failure(IllegalArgumentException("Sirf .gguf model file import karo."))
        }
        val looksLikeQwen25 = lowerName.contains("qwen2.5") || lowerName.contains("qwen2_5") || lowerName.contains("qwen-2.5") || lowerName.contains("qwen2-5")
        val looksLike3b = lowerName.contains("3b")
        if (!(looksLikeQwen25 && looksLike3b)) {
            return@withContext Result.failure(IllegalArgumentException("Is slot mein Qwen2.5-3B GGUF hi import karo (Q4_K_M recommended)."))
        }
        val safeName = name.first.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        val temp = File(dir, "$safeName.part")
        val target = File(dir, safeName)
        val total = name.second.takeIf { it > 0 } ?: -1L
        _state.value = ModelState(safeName, total, 0L, ModelState.Status.IMPORTING, "Qwen2.5 3B model copy ho raha hai…")
        try {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER)
                    var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        copied += n
                        _state.value = _state.value.copy(copiedBytes = copied, sizeBytes = total.coerceAtLeast(0L), message = "Importing • ${formatBytes(copied)}${if (total > 0) " / ${formatBytes(total)}" else ""}")
                    }
                }
            } ?: throw IllegalArgumentException("File read nahi ho saki.")
            FileInputStream(temp).use { input ->
                val magic = ByteArray(4)
                if (input.read(magic) != 4 || String(magic, Charsets.US_ASCII) != "GGUF") {
                    temp.delete()
                    throw IllegalArgumentException("Ye valid GGUF file nahi hai.")
                }
            }
            val oldPath = prefs.getString(KEY_PATH, null)
            if (target.exists()) target.delete()
            check(temp.renameTo(target)) { "Imported model ko final file mein move nahi kiya ja saka." }
            if (!oldPath.isNullOrBlank() && oldPath != target.absolutePath) File(oldPath).delete()
            prefs.edit().putString(KEY_PATH, target.absolutePath).putString(KEY_NAME, safeName).apply()
            _state.value = ModelState(safeName, target.length(), target.length(), ModelState.Status.READY, "Model ready • Load karne ke liye tayyar")
            Result.success(Unit)
        } catch (t: Throwable) {
            if (t is CancellationException) {
                temp.delete()
                throw t
            }
            temp.delete()
            _state.value = ModelState(status = ModelState.Status.ERROR, message = "Import failed", error = t.message ?: "Unknown import error")
            Result.failure(t)
        }.also {
            if (closeRequested.compareAndSet(true, false)) releaseModelSafely()
        }
    } }

    suspend fun load(): Result<Unit> = operationLock.withLock { withContext(Dispatchers.Default) {
        if (busy.get()) return@withContext Result.failure(IllegalStateException("Inference chal rahi hai."))
        if (model?.isLoaded == true) return@withContext Result.success(Unit)
        val path = prefs.getString(KEY_PATH, null)
            ?: return@withContext Result.failure(IllegalStateException("Pehle GGUF model import karo."))
        val file = File(path)
        if (!file.isFile) return@withContext Result.failure(IllegalStateException("Model file missing hai. Dobara import karo."))
        _state.value = _state.value.copy(status = ModelState.Status.LOADING, message = "Qwen2.5 3B memory mein load ho raha hai…", error = null)
        try {
            // Same phone-friendly thread ceiling as the 1.7B fast slot — this
            // slot is meant to run as fast as possible, not as capable as
            // possible, so it deliberately does not reach for the 4B slot's
            // larger context/thread budget.
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(3, 6)
            if (!ModelRuntimeCoordinator.tryClaim(this@Qwen25ModelEngine)) {
                _state.value = _state.value.copy(status = ModelState.Status.READY, message = "Another AI model loaded hai. Pehle usko Unload karo.")
                return@withContext Result.failure(IllegalStateException("Another AI model already loaded hai. Pehle usko Unload karo."))
            }
            model = Llama.loadModel(
                file.absolutePath,
                LlamaConfig(
                    contextSize = CONTEXT_SIZE,
                    threads = threads,
                    // GPU offload left at 0 on purpose — see Qwen17BModelEngine's
                    // identical note: this .aar is not confirmed to ship a
                    // working GPU backend for arbitrary devices.
                    gpuLayers = 0,
                    temperature = 0.35f,
                    topP = 0.9f,
                    topK = 40,
                    // This .aar (llama-android 0.1.1) has no repeatPenalty/
                    // repeatLastN sampler option — see Qwen17BModelEngine's
                    // note. Repetition loops are handled post-generation by
                    // stripRepetitionLoop() below instead.
                )
            )
            _state.value = _state.value.copy(status = ModelState.Status.LOADED, message = "Qwen2.5 3B • Ready", error = null)
            Result.success(Unit)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            model = null
            ModelRuntimeCoordinator.release(this@Qwen25ModelEngine)
            _state.value = _state.value.copy(status = ModelState.Status.ERROR, message = "Model load failed", error = t.message ?: "Unknown load error")
            Result.failure(t)
        }.also {
            if (closeRequested.compareAndSet(true, false)) releaseModelSafely()
        }
    } }

    suspend fun unload(): Result<Unit> = operationLock.withLock { withContext(Dispatchers.Default) {
        if (busy.get()) return@withContext Result.failure(IllegalStateException("Chat generation chal rahi hai. Pehle uska wait karo."))
        val current = model ?: run {
            _state.value = _state.value.copy(status = if (prefs.getString(KEY_PATH, null) != null) ModelState.Status.READY else ModelState.Status.NO_MODEL, message = "Model unloaded")
            return@withContext Result.success(Unit)
        }
        _state.value = _state.value.copy(status = ModelState.Status.UNLOADING, message = "Model memory se unload ho raha hai…", error = null)
        return@withContext try {
            Llama.releaseModel(current)
            model = null
            ModelRuntimeCoordinator.release(this@Qwen25ModelEngine)
            _state.value = _state.value.copy(status = ModelState.Status.READY, message = "Model unloaded • File safe hai")
            Result.success(Unit)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _state.value = _state.value.copy(status = ModelState.Status.ERROR, message = "Unload failed", error = t.message ?: "Unknown unload error")
            Result.failure(t)
        }.also {
            if (closeRequested.compareAndSet(true, false)) releaseModelSafely()
        }
    } }

    suspend fun delete(): Result<Unit> = operationLock.withLock { withContext(Dispatchers.IO) {
        if (busy.get() || model?.isLoaded == true) return@withContext Result.failure(IllegalStateException("Delete se pehle model Unload karo."))
        val path = prefs.getString(KEY_PATH, null) ?: return@withContext Result.success(Unit)
        _state.value = _state.value.copy(status = ModelState.Status.DELETING, message = "Model file delete ho rahi hai…", error = null)
        return@withContext try {
            val ok = File(path).delete()
            if (!ok && File(path).exists()) throw IllegalStateException("Model file delete nahi hui.")
            prefs.edit().clear().apply()
            _state.value = ModelState(status = ModelState.Status.NO_MODEL, message = "No model • Import GGUF")
            Result.success(Unit)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _state.value = _state.value.copy(status = ModelState.Status.ERROR, message = "Delete failed", error = t.message ?: "Unknown delete error")
            Result.failure(t)
        }.also {
            if (closeRequested.compareAndSet(true, false)) releaseModelSafely()
        }
    } }

    fun close() {
        // Never release a native model while any serialized model operation is
        // running. This avoids a native use-after-free if Activity is disposed
        // during inference/load/unload.
        if (busy.get() || !operationLock.tryLock()) {
            closeRequested.set(true)
            return
        }
        try {
            releaseModelSafely()
        } finally {
            operationLock.unlock()
        }
    }

    private fun releaseModelSafely() {
        val current = model ?: return
        runCatching { Llama.releaseModel(current) }
        model = null
        ModelRuntimeCoordinator.release(this)
    }

    private fun captureUsefulMemory(input: String) {
        val match = Regex("(?:yaad rakhna|yaad rakh lo|remember)\\s*[:,-]?\\s*(.+)", RegexOption.IGNORE_CASE).find(input)
        if (match != null) {
            val captured = match.groupValues[1].trim()
            if (captured.isNotBlank()) {
                val stableKey = captured
                    .lowercase(Locale.ROOT)
                    .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
                    .split(Regex("\\s+"))
                    .filter { it.isNotBlank() && !it.all(Char::isDigit) }
                    .take(3)
                    .joinToString("_")
                    .ifBlank { null }
                memory.remember("user_fact_${stableKey ?: System.currentTimeMillis()}", captured)
            }
        }
        val relations = listOf("ammi", "abbu", "bhai", "behen", "bhanji", "bhatija", "dost", "mummy", "papa")
        val possessives = listOf("mera", "meri", "mere")
        val lower = input.lowercase(Locale.ROOT)
        relations.firstOrNull { relation ->
            possessives.any { possessive -> lower.contains("$possessive $relation") }
        }?.let { memory.addRelationship(it, input.take(300)) }
    }

    private fun isGenericDeflection(input: String, answer: String): Boolean {
        val q = input.lowercase(Locale.ROOT)
        val a = answer.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
        if (q.length < 3 || a.isBlank()) return false
        val generic = listOf(
            "hello! how can i help",
            "how can i help you",
            "let me know if you need help",
            "what can i help you with",
            "how can i assist you today"
        )
        val greetingInput = listOf("hi", "hello", "hey", "hii", "hlo", "salam", "assalamualaikum").any { q == it }
        val beginsGeneric = generic.any { a.startsWith(it) }
        val tooShortForQuestion = a.length < 24 && (a.contains("let me know") || a == "hello" || a == "hi")
        return !greetingInput && (beginsGeneric || tooShortForQuestion)
    }

    private fun isEchoingUser(input: String, answer: String): Boolean {
        val q = input.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
        val a = answer.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
        if (q.length < 10 || a.isBlank()) return false
        val repeated = a.contains("$q $q") || a.contains("$q\n$q")
        val directEcho = q.length >= 16 && a.contains(q) && a.length <= q.length * 2 + 24
        return repeated || directEcho
    }

    private fun isScriptMismatch(input: String, answer: String): Boolean {
        if (answer.isBlank()) return false
        val devanagari = Regex("\\p{IsDevanagari}")
        val userHasDevanagari = devanagari.containsMatchIn(input)
        if (userHasDevanagari) return false
        val userHasLatinWords = Regex("[A-Za-z]").containsMatchIn(input)
        if (!userHasLatinWords) return false
        return devanagari.containsMatchIn(answer)
    }

    /**
     * Output-side fix for the "same sentence repeated forever" decoding loop
     * (same rationale as Qwen17BModelEngine — this .aar exposes no
     * repeatPenalty/repeatLastN sampler option). Returns the character offset
     * right after the FIRST copy of a chunk that repeats 3x in a row, or null.
     */
    private fun findRepetitionLoop(text: String): Int? {
        if (text.length < 40) return null
        val sentences = text.split(Regex("(?<=[।.!?])\\s+")).filter { it.isNotBlank() }
        if (sentences.size >= 3) {
            for (i in 0..sentences.size - 3) {
                val a = sentences[i].trim()
                if (a.length < 8) continue
                if (a == sentences[i + 1].trim() && a == sentences[i + 2].trim()) {
                    val start = text.indexOf(a)
                    if (start >= 0) return start + a.length
                }
            }
        }
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val n = 6
        if (words.size >= n * 3) {
            for (i in 0..words.size - n * 3) {
                val a = words.subList(i, i + n).joinToString(" ")
                val b = words.subList(i + n, i + 2 * n).joinToString(" ")
                val c = words.subList(i + 2 * n, i + 3 * n).joinToString(" ")
                if (a == b && a == c) {
                    return words.subList(0, i + n).joinToString(" ").length
                }
            }
        }
        return null
    }

    private fun stripRepetitionLoop(text: String): String {
        val cut = findRepetitionLoop(text) ?: return text
        return text.substring(0, cut).trim()
    }

    private fun isCodeRequest(input: String): Boolean {
        val s = input.lowercase(Locale.ROOT)
        return listOf("python", "kotlin", "java", "javascript", "code", "coding", "program").any { s.contains(it) }
    }

    private fun looksLikeCode(answer: String): Boolean {
        val a = answer.lowercase(Locale.ROOT)
        return a.contains("```") || a.contains("print(") || a.contains("=") ||
            a.contains("def ") || a.contains("fun ") || a.contains("public class") ||
            a.contains("import ")
    }

    /**
     * Qwen2.5-Instruct should never emit <think>/<analysis> tags (that's a
     * Qwen3 behaviour), but this is kept as a cheap defensive strip in case a
     * quantized build echoes stray template tokens — mirrors
     * Qwen17BModelEngine's cleanup so output handling stays consistent
     * across all three model slots.
     */
    private fun cleanThinkingOutput(raw: String): String {
        var text = raw.trim()
        if (text.isBlank()) return ""
        val close = text.lastIndexOf("</think>", ignoreCase = true)
        if (close >= 0) return text.substring(close + "</think>".length).trim()
        if (text.indexOf("<think>", ignoreCase = true) >= 0) return ""
        text = text.replace(Regex("(?is)<analysis>.*?</analysis>"), "")
        text = text.replace(Regex("(?is)<think>.*?</think>"), "")
        return text.replace("</think>", "", ignoreCase = true).trim()
    }

    private fun fitGenerationBudget(prompt: String, desired: Int, contextSize: Int): Int {
        val inputTokens = estimateTokens(MJPersonality.SYSTEM_PROMPT + prompt)
        return desired.coerceAtMost((contextSize - inputTokens - 32).coerceAtLeast(128))
    }

    /** Rough UI-only estimate; llama-android 0.1.1 exposes speed, not exact token counts. */
    private fun estimateTokens(text: String): Int = if (text.isBlank()) 0 else (text.length / 4).coerceAtLeast(1)

    private fun loadInitialState(): ModelState {
        val path = prefs.getString(KEY_PATH, null)
        val file = path?.let(::File)
        return if (file?.isFile == true) {
            ModelState(prefs.getString(KEY_NAME, file.name), file.length(), file.length(), ModelState.Status.READY, "Model ready • Load karne ke liye tayyar")
        } else ModelState()
    }

    companion object {
        private const val KEY_PATH = "path"
        private const val KEY_NAME = "name"
        private const val DEFAULT_BUFFER = 1024 * 1024
        // Same 3072 context as the 1.7B fast slot: this slot exists to run as
        // fast as possible on a phone/Termux, not to match the 4B slot's
        // larger context window.
        private const val CONTEXT_SIZE = 3072
        private const val MAX_RETRY_TOKENS = 896
        private const val FAST_RETRY_THRESHOLD = 640
        private const val FAST_RETRY_EXTRA = 256
        fun formatBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
            bytes >= 1024L * 1024L -> String.format(Locale.US, "%.0f MB", bytes / 1048576.0)
            bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
        private fun Float.formatSpeed(): String = String.format(Locale.US, "%.1f", this)
    }
}
