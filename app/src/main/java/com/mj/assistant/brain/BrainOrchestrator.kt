package com.mj.assistant.brain

import android.content.Context
import android.content.Intent
import com.mj.assistant.commands.CommandOutcome
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** What MainActivity does after asking the Brain to handle a message. */
sealed class BrainOutcome {
    /** One or more steps ran (or a plain conversational reply came back) — show/speak [text], apply [action] if present. */
    data class Handled(val text: String, val action: ActionResult?) : BrainOutcome()
    /** AI Brain needs more info before it can act — surface [text] via the existing TTS/UI, same as any other reply. */
    data class NeedsClarification(val text: String) : BrainOutcome()
    /**
     * Brain is off, unconfigured, unreachable, or returned something that
     * didn't pass validation. Callers MUST fall back to the existing local
     * flow (DeviceCommandRegistry + the on-device Qwen model) exactly as it
     * worked before this integration — this is the "safe fallback" the spec
     * requires, and it's how every pre-existing capability keeps working
     * even when the AI Brain can't.
     */
    data class Fallback(val reason: String) : BrainOutcome()
}

data class ActionResult(val actionType: String, val actionEnabled: Boolean)

class BrainOrchestrator(context: Context) {
    private val secureStore = BrainSecureStore(context)
    private val client = BrainClient(secureStore)
    private val appContext = context.applicationContext
    private val conversationId = UUID.randomUUID().toString()
    private val inFlight = AtomicBoolean(false)

    fun isEnabled(): Boolean = secureStore.isEnabled() && secureStore.hasApiKey()
    fun settingsStore(): BrainSecureStore = secureStore

    suspend fun handle(
        message: String,
        deviceContext: BrainDeviceContext,
        launchIntent: (Intent) -> Boolean
    ): BrainOutcome {
        if (!isEnabled()) return BrainOutcome.Fallback("brain disabled or not configured")

        // Prevents duplicate/overlapping executions if this ever gets called
        // again before a prior call finished (belt-and-braces alongside
        // MainActivity's own `replying` guard on the send button).
        if (!inFlight.compareAndSet(false, true)) {
            return BrainOutcome.Fallback("a brain request is already in flight")
        }
        try {
            val request = BrainRequest(
                message = message,
                language = "hinglish",
                deviceContext = deviceContext,
                conversationId = conversationId
            )
            return when (val result = client.sendCommand(request)) {
                is BrainCallResult.Success -> interpret(result.response, deviceContext, launchIntent)
                is BrainCallResult.SchemaError -> BrainOutcome.Fallback("schema: ${result.reason}")
                BrainCallResult.NotConfigured -> BrainOutcome.Fallback("not configured")
                BrainCallResult.ConnectionFailed -> BrainOutcome.Fallback("connection failed")
                BrainCallResult.TimedOut -> BrainOutcome.Fallback("timed out")
                is BrainCallResult.HttpError -> BrainOutcome.Fallback("http ${result.code}")
                BrainCallResult.EmptyResponse -> BrainOutcome.Fallback("empty response")
                is BrainCallResult.Unknown -> BrainOutcome.Fallback("unknown error: ${result.message}")
            }
        } finally {
            inFlight.set(false)
        }
    }

    private fun interpret(
        response: BrainResponse,
        deviceContext: BrainDeviceContext,
        launchIntent: (Intent) -> Boolean
    ): BrainOutcome {
        if (response.type == BrainResponseType.QUESTION) {
            return BrainOutcome.NeedsClarification(
                response.message.ifBlank { "Thoda aur batao, kya karna hai?" }
            )
        }

        if (response.type == BrainResponseType.RESPONSE) {
            // Plain chat reply — no steps are ever attached to this type
            // (BrainJson strips any that a server mistakenly sends), so
            // there's nothing to validate or execute here.
            return BrainOutcome.Handled(response.message.ifBlank { "Ji." }, action = null)
        }

        // response.type == ACTION_PLAN. No steps at all just means a plain
        // reply came back on this type instead of "response" — still handled
        // the same way rather than treated as an error.
        if (response.steps.isEmpty()) {
            return BrainOutcome.Handled(response.message.ifBlank { "Ji." }, action = null)
        }

        val outcomes = mutableListOf<CommandOutcome>()
        var lastAction: ActionResult? = null
        var rejectedAny = false

        // Sequential execution, in the order the AI planned them — e.g.
        // "volume 40% karo aur headphones connect karo" must set volume
        // before attempting the bluetooth step, not in parallel.
        for (step in response.steps) {
            when (val validation = BrainActionRegistry.validate(step, deviceContext)) {
                is BrainValidationResult.Valid -> {
                    val outcome = BrainActionExecutor.execute(validation.action, appContext, launchIntent)
                    outcomes += outcome
                    if (outcome.actionType != null) {
                        lastAction = ActionResult(outcome.actionType, outcome.actionEnabled)
                    }
                }
                is BrainValidationResult.Rejected -> {
                    rejectedAny = true
                    // Rejected steps are never silently dropped from the
                    // user's view — they're surfaced as a short, honest note
                    // rather than executed. Internal reason ([reason]) stays
                    // out of the spoken/displayed text.
                }
            }
        }

        if (outcomes.isEmpty()) {
            // Every single step was rejected (e.g. all unknown actions) —
            // there is nothing safe to report as "done", so this is a
            // fallback rather than a false "Handled".
            return BrainOutcome.Fallback("all steps rejected by action registry")
        }

        val prefix = response.message.takeIf { it.isNotBlank() }
        val stepText = outcomes.joinToString(" ") { it.responseText }
        val suffix = if (rejectedAny) " (kuch steps samajh nahi aaye, unhe skip kar diya.)" else ""
        val combined = (if (prefix != null) "$prefix $stepText" else stepText) + suffix
        return BrainOutcome.Handled(combined.trim(), lastAction)
    }
}
