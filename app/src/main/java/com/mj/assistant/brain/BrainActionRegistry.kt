package com.mj.assistant.brain

/**
 * Everything the AI Brain is allowed to ask the phone to do. This is the
 * "Action registry validation" + "Parameter validation" + "Device
 * validation" stage of the pipeline described in the spec:
 *
 *   API response -> JSON parse -> Schema validation -> Action registry
 *   validation -> Parameter validation -> Device validation -> existing APK
 *   action executor -> Android/accessory API
 *
 * A [BrainStep] that doesn't map to exactly one of these is REJECTED, never
 * partially executed and never passed through to Android APIs as-is.
 */
sealed class ValidatedBrainAction {
    data class SetVolume(val percent: Int) : ValidatedBrainAction()
    data class SetBrightness(val percent: Int) : ValidatedBrainAction()
    data class BluetoothConnect(val deviceName: String) : ValidatedBrainAction()
    data class BluetoothDisconnect(val deviceName: String) : ValidatedBrainAction()
    object WifiOn : ValidatedBrainAction()
    object WifiOff : ValidatedBrainAction()
    object GetBattery : ValidatedBrainAction()
    object GetVolume : ValidatedBrainAction()
}

sealed class BrainValidationResult {
    data class Valid(val action: ValidatedBrainAction) : BrainValidationResult()
    data class Rejected(val step: BrainStep, val reason: String) : BrainValidationResult()
}

object BrainActionRegistry {
    /** The complete, closed set of action names the AI Brain may invoke. Anything else is an unknown action -> REJECT. */
    private val SUPPORTED_ACTIONS = setOf(
        "set_volume", "set_brightness",
        "bluetooth_connect", "bluetooth_disconnect",
        "wifi_on", "wifi_off",
        "get_battery", "get_volume"
    )

    private const val MIN_PERCENT = 0
    private const val MAX_PERCENT = 100

    fun validate(step: BrainStep, deviceContext: BrainDeviceContext): BrainValidationResult {
        if (step.action !in SUPPORTED_ACTIONS) {
            return BrainValidationResult.Rejected(step, "unknown action '${step.action}'")
        }
        return when (step.action) {
            "set_volume" -> validatePercent(step) { ValidatedBrainAction.SetVolume(it) }
            "set_brightness" -> validatePercent(step) { ValidatedBrainAction.SetBrightness(it) }
            "get_battery" -> BrainValidationResult.Valid(ValidatedBrainAction.GetBattery)
            "get_volume" -> BrainValidationResult.Valid(ValidatedBrainAction.GetVolume)
            "wifi_on" -> BrainValidationResult.Valid(ValidatedBrainAction.WifiOn)
            "wifi_off" -> BrainValidationResult.Valid(ValidatedBrainAction.WifiOff)
            "bluetooth_connect" -> validateBluetoothDevice(step, deviceContext) { ValidatedBrainAction.BluetoothConnect(it) }
            "bluetooth_disconnect" -> validateBluetoothDevice(step, deviceContext) { ValidatedBrainAction.BluetoothDisconnect(it) }
            else -> BrainValidationResult.Rejected(step, "unknown action '${step.action}'")
        }
    }

    private fun validatePercent(step: BrainStep, build: (Int) -> ValidatedBrainAction): BrainValidationResult {
        val v = step.value
            ?: return BrainValidationResult.Rejected(step, "missing required 'value' for ${step.action}")
        if (v < MIN_PERCENT || v > MAX_PERCENT) {
            return BrainValidationResult.Rejected(step, "'value' ${v} out of range 0..100 for ${step.action}")
        }
        return BrainValidationResult.Valid(build(v))
    }

    /**
     * Device validation: a bluetooth_connect/disconnect step is only trusted
     * if it names a device that actually exists in the same device_context
     * we told the AI about (or is currently paired on-device) — this stops
     * the AI from directing the executor at an arbitrary/unverified name.
     */
    private fun validateBluetoothDevice(
        step: BrainStep,
        deviceContext: BrainDeviceContext,
        build: (String) -> ValidatedBrainAction
    ): BrainValidationResult {
        val name = step.deviceName?.trim()
        if (name.isNullOrEmpty()) {
            return BrainValidationResult.Rejected(step, "missing required 'device_name' for ${step.action}")
        }
        val known = deviceContext.bluetoothDevices.any { it.equals(name, ignoreCase = true) }
        if (!known) {
            return BrainValidationResult.Rejected(step, "device '$name' not in known bluetooth_devices")
        }
        return BrainValidationResult.Valid(build(name))
    }
}
