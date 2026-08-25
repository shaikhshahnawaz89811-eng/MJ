package com.mj.assistant.brain

import org.json.JSONArray
import org.json.JSONObject

/** Snapshot of device state sent with every request, per spec's `device_context`. */
data class BrainDeviceContext(
    val volume: Int,
    val brightness: Int,
    val wifi: Boolean,
    val battery: Int,
    val bluetoothDevices: List<String>
) {
    fun toJson(): JSONObject = JSONObject()
        .put("volume", volume)
        .put("brightness", brightness)
        .put("wifi", wifi)
        .put("battery", battery)
        .put("bluetooth_devices", JSONArray(bluetoothDevices))
}

data class BrainRequest(
    val message: String,
    val language: String = "hinglish",
    val deviceContext: BrainDeviceContext,
    val conversationId: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("message", message)
        .put("language", language)
        .put("device_context", deviceContext.toJson())
        .put("conversation_id", conversationId)
}

/** One planner step, kept as raw fields — [BrainActionRegistry] does the actual trust decision. */
data class BrainStep(
    val action: String,
    // Different actions use different value shapes (`value` vs `device_name`).
    // Both are captured here; validation for the specific action decides which is required.
    val value: Int?,
    val deviceName: String?
)

/** The three response shapes the Brain server's system prompt defines. */
enum class BrainResponseType { RESPONSE, QUESTION, ACTION_PLAN }

/**
 * Top-level AI Brain response, matching the server's system-prompt schema
 * exactly:
 *   {"type": "response",    "message": "..."}
 *   {"type": "question",    "message": "...", "steps": []}
 *   {"type": "action_plan", "message": "...", "steps": [{"action": "...", "value"|"device_name": ...}]}
 *
 * - RESPONSE  -> plain chat reply, no steps possible even if the server sends any.
 * - QUESTION  -> clarification needed, no steps possible even if the server sends any.
 * - ACTION_PLAN -> the only type whose steps are ever passed on to validation/execution.
 */
data class BrainResponse(
    val type: BrainResponseType,
    val message: String,
    val steps: List<BrainStep>
)

sealed class BrainParseResult {
    data class Ok(val response: BrainResponse) : BrainParseResult()
    data class SchemaError(val reason: String) : BrainParseResult()
}

object BrainJson {
    /**
     * Strict schema validation — this is the first gate in the
     * "APK MUST NOT directly trust the AI response" pipeline. Malformed or
     * missing fields are rejected here, before anything reaches the action
     * registry.
     *
     * `steps` is only ever kept for `action_plan`. For `response` and
     * `question` it's dropped even if the server mistakenly includes it —
     * those two types can never reach the executor with steps attached, no
     * matter what the server sends, so a stray/hallucinated `steps` array on
     * a chat reply or a clarifying question can never execute anything.
     */
    fun parseResponse(raw: String): BrainParseResult {
        if (raw.isBlank()) return BrainParseResult.SchemaError("empty response body")
        val obj = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return BrainParseResult.SchemaError("invalid JSON: ${e.message}")
        }
        val rawType = obj.optString("type", "")
        val type = when (rawType) {
            "response" -> BrainResponseType.RESPONSE
            "question" -> BrainResponseType.QUESTION
            "action_plan" -> BrainResponseType.ACTION_PLAN
            "" -> return BrainParseResult.SchemaError("missing 'type'")
            else -> return BrainParseResult.SchemaError("unsupported response type '$rawType'")
        }

        val message = obj.optString("message", "")
        val stepsArray = obj.optJSONArray("steps")
        val parsedSteps = mutableListOf<BrainStep>()
        if (stepsArray != null) {
            for (i in 0 until stepsArray.length()) {
                val stepObjRaw = stepsArray.opt(i)
                if (stepObjRaw !is JSONObject) {
                    return BrainParseResult.SchemaError("steps[$i] is not an object")
                }
                val action = stepObjRaw.optString("action", "")
                if (action.isBlank()) {
                    return BrainParseResult.SchemaError("steps[$i] missing 'action'")
                }
                val value = if (stepObjRaw.has("value") && !stepObjRaw.isNull("value")) stepObjRaw.optInt("value", Int.MIN_VALUE) else null
                val deviceName = if (stepObjRaw.has("device_name") && !stepObjRaw.isNull("device_name")) stepObjRaw.optString("device_name") else null
                parsedSteps += BrainStep(action = action, value = value, deviceName = deviceName)
            }
        }

        // Only action_plan is allowed to carry steps forward. A response or
        // question with a stray steps array is a malformed-but-recoverable
        // reply, not a reason to throw the whole message away.
        val steps = if (type == BrainResponseType.ACTION_PLAN) parsedSteps else emptyList()

        if (message.isBlank() && steps.isEmpty()) {
            return BrainParseResult.SchemaError("empty response: no message and no steps")
        }
        return BrainParseResult.Ok(BrainResponse(type = type, message = message, steps = steps))
    }
}
