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

/** Real on-device Qwen3-1.7B GGUF engine. No canned AI fallback is used. */
class Qwen17BModelEngine(context: Context) : AIEngine {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("qwen_17b_model", Context.MODE_PRIVATE)
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
        val message: String = "Qwen3 1.7B Fast model import karo.",
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
                    ?: return@withLock "Abhi Qwen3 1.7B Fast model loaded nahi hai. Profile → Model par jaakar model Load karo."
                if (!current.isLoaded) return@withLock "Qwen3 1.7B Fast model loaded nahi hai. Pehle model Load karo."

                try {
                    // Keep prompts small enough for a phone. The previous implementation
                    // always sent 10 full turns + 12 facts, even for "hello".
                    val needsContext = shouldUseConversationContext(userInput)
                    val recentLimit = if (needsContext) 2 else 0
                    val recent = if (needsContext) memory.recentTurns(recentLimit) else emptyList()
                    // Relevant-memory search scans persisted turns, so do it only for
                    // genuine follow-ups. Facts are cached, but still should not be
                    // injected into every factual/coding request.
                    val relevant = if (needsContext) memory.findRelevantTurns(userInput, 1) else emptyList()
                    val facts = if (needsContext || isMemoryRequest(userInput)) memory.allFacts().takeLast(2) else emptyList()
                    val context = buildPromptContext(recent, relevant, facts, maxChars = if (needsContext) 900 else 0)
                    // The llama-android API already applies the GGUF chat template to
                    // systemPrompt + prompt. Do NOT fake role markers like "User:" inside
                    // the user message; Qwen3 can then echo the question or ignore the
                    // language instruction. Keep the user prompt as an actual user turn.
                    val prompt = if (context.isBlank()) {
                        userInput
                    } else {
                        "Relevant context (use only if it helps):\n$context\n\n$userInput"
                    }
                    val baseTokens = fitGenerationBudget(prompt, generationBudget(userInput), 3072)
                    // Short code requests should stay fast. Reserve thinking mode for
                    // genuinely multi-step reasoning/debugging rather than every message
                    // containing the word "python" or "code".
                    val useThinking = needsDeepReasoning(userInput) && !isPlanningRequest(userInput)
                    val inferencePrompt = "$prompt\n${if (useThinking) "/think" else "/no_think"}"

                    // Qwen3-1.7B supports both thinking and non-thinking modes. The fast
                    // MJ slot deliberately uses non-thinking mode; complex work can still
                    // be handed to the 4B Thinking slot.
                    // Keep the first pass small on a phone and only do a bounded
                    // second pass for the fast/simple tiers when no visible answer
                    // survived the thinking-output cleanup.
                    var maxTokens = baseTokens
                    var retryCount = 0
                    var result = withContext(Dispatchers.Default) {
                        Llama.complete(
                            current,
                            prompt = inferencePrompt,
                            systemPrompt = MJPersonality.SYSTEM_PROMPT,
                            maxTokens = maxTokens,
                        )
                    }
                    var answer = stripRepetitionLoop(cleanThinkingOutput(result.text))
                    val scriptMismatch = isScriptMismatch(userInput, answer)
                    val needsCorrection = answer.isBlank() || isGenericDeflection(userInput, answer) || isEchoingUser(userInput, answer) || (isCodeRequest(userInput) && !looksLikeCode(answer)) || scriptMismatch

                    // A second full generation is expensive on phone CPU. Only retry
                    // when the first pass is empty or clearly dodges the user's request.
                    // The retry remains bounded so a bad generation can never turn into
                    // the old multi-thousand-token stall.
                    if (needsCorrection && baseTokens <= FAST_RETRY_THRESHOLD && !useThinking) {
                        val scriptInstruction = if (scriptMismatch) {
                            "The user typed in Roman/English letters, not Devanagari script. Reply in Roman Hindi/Hinglish letters ONLY — do not use Devanagari script.\n"
                        } else ""
                        val correctionPrompt = "$scriptInstruction" + "Do not repeat or quote the user's question. Answer it now, directly, in the user's language.\n$userInput\n${if (useThinking) "/think" else "/no_think"}"
                        maxTokens = fitGenerationBudget(correctionPrompt, (baseTokens + FAST_RETRY_EXTRA).coerceAtMost(MAX_RETRY_TOKENS), 3072)
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
                    // Phase 2 perf fix: reply() runs on whatever dispatcher the
                    // caller's coroutine scope used (Main, via rememberCoroutineScope
                    // in MJApp) except where explicitly switched to Default/IO. The
                    // Llama.complete() call above was already correctly moved to
                    // Dispatchers.Default, but addRecentTurn()/captureUsefulMemory()
                    // were still running back on that original (Main) dispatcher —
                    // and captureUsefulMemory() -> ConversationMemory.remember() ->
                    // writeFacts() ends in a synchronous SharedPreferences commit(),
                    // which was therefore blocking the UI thread on every "yaad
                    // rakhna"/relationship-word turn. Moving both persistence calls to
                    // Dispatchers.IO fixes this without changing either function's
                    // synchronous-commit-for-facts / async-apply-for-turns semantics.
                    withContext(Dispatchers.IO) {
                        memory.addRecentTurn(userInput, finalAnswer)
                        captureUsefulMemory(userInput)
                    }
                    val approxOutputTokens = estimateTokens(result.text)
                    val approxInputTokens = estimateTokens(MJPersonality.SYSTEM_PROMPT + prompt)
                    val mode = if (useThinking) "think" else "no_think"
                    _state.value = _state.value.copy(
                        message = "1.7B loaded • ${result.tokensPerSecond.formatSpeed()} tok/s • ~${approxInputTokens} in / ~${approxOutputTokens} out tok • $mode • retry $retryCount",
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
                    "Qwen3 se jawab nahi aa saka. Error: $msg"
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
        // Qwen3-1.7B is reasoning-capable, so a small but non-zero generation budget
        // is still used even for greetings/facts. Keep
        // phone replies bounded; complex work gets more room automatically.
        return when {
            // Non-thinking is the default fast path. Keep short replies small so
            // the model stops as soon as the answer is complete.
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
        val dir = File(appContext.getExternalFilesDir("models") ?: appContext.filesDir, "qwen_17b")
        dir.mkdirs()
        val resolver = appContext.contentResolver
        val name = resolver.query(uri, arrayOf("_display_name", "_size"), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) to if (!c.isNull(1)) c.getLong(1) else -1L else null
        } ?: ("qwen3-1.7b.gguf" to -1L)
        val lowerName = name.first.lowercase(Locale.ROOT)
        if (!lowerName.endsWith(".gguf")) {
            return@withContext Result.failure(IllegalArgumentException("Sirf .gguf model file import karo."))
        }
        if (!(lowerName.contains("qwen3") && lowerName.contains("1.7b"))) {
            return@withContext Result.failure(IllegalArgumentException("Is slot mein Qwen3-1.7B GGUF hi import karo (Q4_K_M recommended)."))
        }
        val safeName = name.first.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        val temp = File(dir, "$safeName.part")
        val target = File(dir, safeName)
        val total = name.second.takeIf { it > 0 } ?: -1L
        _state.value = ModelState(safeName, total, 0L, ModelState.Status.IMPORTING, "Qwen3 1.7B model copy ho raha hai…")
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
            // Fix (Bug 8): close() can only set closeRequested (rather than
            // releasing immediately) while this operation holds operationLock.
            // reply()/load() already consumed that flag symmetrically on their
            // own way out; importModel() didn't, even though it can run for a
            // long time and race an Activity teardown just as easily.
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
        _state.value = _state.value.copy(status = ModelState.Status.LOADING, message = "Qwen3 1.7B Fast memory mein load ho raha hai…", error = null)
        try {
            // Was capped at (3,4). Device has more real cores available for a
            // 1.7B model than that; raise the ceiling to 6 as requested. Still
            // reserves at least 1-2 cores for UI/system so the phone doesn't
            // freeze under sustained inference.
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(3, 6)
            // Keep the KV context smaller than the 4B slot. Prompt history is already
            // bounded, and 3072 leaves room for short coding/reasoning turns without
            // paying the full 4B context-cache cost.
            if (!ModelRuntimeCoordinator.tryClaim(this@Qwen17BModelEngine)) {
                _state.value = _state.value.copy(status = ModelState.Status.READY, message = "Another AI model loaded hai. Pehle usko Unload karo.")
                return@withContext Result.failure(IllegalStateException("Another AI model already loaded hai. Pehle usko Unload karo."))
            }
            model = Llama.loadModel(
                file.absolutePath,
                LlamaConfig(
                    contextSize = 3072,
                    threads = threads,
                    // GPU offload left at 0 on purpose: this .aar (llama-android
                    // 0.1.1) is not confirmed to ship a GPU (Vulkan/OpenCL)
                    // backend for this device's driver. Setting gpuLayers > 0
                    // on a CPU-only build either no-ops or crashes the native
                    // loader. If you want to try it, bump this to e.g. 12 and
                    // test — but keep a fallback path to 0 since it's unverified.
                    gpuLayers = 0,
                    // Lower sampling reduces factual hallucinations while keeping
                    // everyday Hinglish replies natural. The old 0.7/0.8 combination
                    // was unnecessarily random for a 1.7B factual assistant.
                    temperature = 0.35f,
                    topP = 0.9f,
                    topK = 40,
                    // NOTE: this .aar (dev.ffmpegkit-maintained:llama-android:0.1.1)
                    // does NOT expose repeatPenalty/repeatLastN (or any repetition
                    // control) on LlamaConfig — confirmed by decompiling
                    // LlamaConfig.class: it only has contextSize, threads,
                    // gpuLayers, temperature, topP, topK, seed. Passing those two
                    // named args is a compile error, not a runtime option, so the
                    // "same sentence repeated forever" bug (root cause: no
                    // repetition control at the sampler) is instead handled after
                    // generation by stripRepetitionLoop() below, since the library
                    // gives us no way to prevent it during decoding.
                )
            )
            _state.value = _state.value.copy(status = ModelState.Status.LOADED, message = "Qwen3 1.7B Fast • Ready", error = null)
            Result.success(Unit)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            model = null
            ModelRuntimeCoordinator.release(this@Qwen17BModelEngine)
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
            ModelRuntimeCoordinator.release(this@Qwen17BModelEngine)
            _state.value = _state.value.copy(status = ModelState.Status.READY, message = "Model unloaded • File safe hai")
            Result.success(Unit)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _state.value = _state.value.copy(status = ModelState.Status.ERROR, message = "Unload failed", error = t.message ?: "Unknown unload error")
            Result.failure(t)
        }.also {
            // Fix (Bug 8): see importModel() above — consume closeRequested
            // symmetrically here too, since unload() can also hold
            // operationLock long enough to race Activity disposal.
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
            // Fix (Bug 8): see importModel() above — same symmetry fix.
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
                // Fix (Bug 5): a timestamp-based key meant every "yaad rakhna"
                // statement was a brand-new fact, so remember()'s existing
                // dedup-by-key logic never triggered even when the user was
                // restating/correcting something already saved. Deriving a
                // stable key from the fact's own content (ignoring numbers,
                // which are the part most likely to change on a correction)
                // means restating the same subject updates it in place instead
                // of accumulating contradictory duplicates.
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
        // Fix (Bug 4): Hindi possessives are gendered (mera/meri/mere) and the
        // correct form depends on the grammatical gender of the relation word
        // that follows it, so matching only the fixed string "meri $it" missed
        // several grammatically valid phrasings (e.g. "mera bhai", "mere papa").
        // Matching the relation word together with any nearby possessive form
        // instead of one hardcoded concatenation covers all of them.
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

    /**
     * Bug: user types in Roman letters ("india ka capital kya hai") but the
     * model answers back in Devanagari script even though MJPersonality's
     * system prompt says to mirror the user's script. Small models often
     * don't follow that instruction reliably, so detect the mismatch after
     * generation and force a corrected retry instead of trusting the prompt
     * alone.
     */
    private fun isScriptMismatch(input: String, answer: String): Boolean {
        if (answer.isBlank()) return false
        val devanagari = Regex("\\p{IsDevanagari}")
        val userHasDevanagari = devanagari.containsMatchIn(input)
        if (userHasDevanagari) return false // user already typed Hindi script; no mismatch check needed
        val userHasLatinWords = Regex("[A-Za-z]").containsMatchIn(input)
        if (!userHasLatinWords) return false
        return devanagari.containsMatchIn(answer)
    }

    /**
     * Output-side fix for the "same sentence repeated forever" decoding loop.
     * This .aar's LlamaConfig has no repeatPenalty/repeatLastN sampler option
     * (see load()), so a small quantized model can still get stuck echoing the
     * same sentence or word n-gram. Detect a chunk of text that repeats 3x in a
     * row and return the character offset right after the FIRST copy of it, so
     * the caller can cut the loop out instead of showing it to the user.
     * Returns null if no loop is found.
     */
    private fun findRepetitionLoop(text: String): Int? {
        if (text.length < 40) return null
        // Sentence-level repeats (handles both Devanagari '।' and Latin punctuation).
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
        // Fallback: repeated word n-gram, for loops with no clean sentence
        // boundary (e.g. inside an unterminated <think> block).
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

    /** Cuts a decoding loop out of [text], if findRepetitionLoop() finds one. */
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
        // Keep input + requested output inside the native context window. The
        // llama wrapper does not expose a separate exact tokenizer count, so use
        // the same conservative character estimate used by the diagnostics.
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
        // Ceiling for the bounded second pass on very small/simple replies.
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
