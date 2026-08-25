package com.mj.assistant.brain

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.mj.assistant.commands.CommandOutcome
import kotlin.math.roundToInt

/**
 * "Existing APK action executor" step of the pipeline, extended with the
 * device operations the Brain protocol needs (volume/brightness/bluetooth)
 * that [com.mj.assistant.commands.DeviceCommandRegistry] didn't previously
 * cover. Every function here only ever receives an already-validated
 * [ValidatedBrainAction] — never a raw AI string — matching the "APK MUST
 * NOT directly trust the AI response" requirement.
 *
 * Where Android itself won't let an app act silently (Wi-Fi toggling on
 * API 29+, brightness without WRITE_SETTINGS grant), this follows the same
 * honesty policy WifiStatusCommand already established: open the real
 * system UI and say so, rather than pretend the action happened.
 */
object BrainActionExecutor {

    fun execute(action: ValidatedBrainAction, androidContext: Context, launchIntent: (Intent) -> Boolean): CommandOutcome =
        when (action) {
            is ValidatedBrainAction.SetVolume -> setVolume(androidContext, action.percent)
            is ValidatedBrainAction.GetVolume -> getVolume(androidContext)
            is ValidatedBrainAction.SetBrightness -> setBrightness(androidContext, action.percent, launchIntent)
            is ValidatedBrainAction.GetBattery -> getBattery(androidContext)
            is ValidatedBrainAction.WifiOn -> wifiToggle(androidContext, on = true, launchIntent)
            is ValidatedBrainAction.WifiOff -> wifiToggle(androidContext, on = false, launchIntent)
            is ValidatedBrainAction.BluetoothConnect -> bluetoothConnect(androidContext, action.deviceName, launchIntent)
            is ValidatedBrainAction.BluetoothDisconnect -> bluetoothDisconnect(androidContext, action.deviceName, launchIntent)
        }

    // ---- volume ---------------------------------------------------------

    private fun setVolume(context: Context, percent: Int): CommandOutcome {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return CommandOutcome("Volume control abhi available nahi hai.")
        return try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = ((percent / 100f) * max).roundToInt().coerceIn(0, max)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            CommandOutcome("Volume $percent% kar diya.", actionType = "Volume", actionEnabled = true)
        } catch (_: Exception) {
            CommandOutcome("Volume set nahi ho saka.")
        }
    }

    private fun getVolume(context: Context): CommandOutcome {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return CommandOutcome("Volume status abhi nahi mil saka.")
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val percent = ((current.toFloat() / max) * 100).roundToInt()
        return CommandOutcome("Volume abhi $percent% hai.", actionType = "Volume", actionEnabled = current > 0)
    }

    // ---- brightness -------------------------------------------------------

    private fun setBrightness(context: Context, percent: Int, launchIntent: (Intent) -> Boolean): CommandOutcome {
        if (!Settings.System.canWrite(context)) {
            // Special permission, not a runtime dialog — same "open real
            // settings, be honest" pattern as WifiStatusCommand.
            val opened = launchIntent(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return CommandOutcome(
                if (opened) "Brightness change karne ke liye pehle 'Modify system settings' permission on karo — screen khol di hai."
                else "Brightness permission screen open nahi ho payi.",
                actionType = "Brightness"
            )
        }
        return try {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val value = ((percent / 100f) * 255).roundToInt().coerceIn(0, 255)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
            CommandOutcome("Brightness $percent% kar diya.", actionType = "Brightness", actionEnabled = true)
        } catch (_: Exception) {
            CommandOutcome("Brightness set nahi ho saki.")
        }
    }

    // ---- battery ----------------------------------------------------------

    private fun getBattery(context: Context): CommandOutcome {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = bm?.isCharging == true
        val text = if (pct in 0..100) {
            "Battery $pct% hai${if (charging) ", aur charge ho rahi hai." else "."}"
        } else "Battery status abhi nahi mil saka."
        return CommandOutcome(text, actionType = "Battery")
    }

    // ---- wifi ---------------------------------------------------------------

    private fun wifiToggle(context: Context, on: Boolean, launchIntent: (Intent) -> Boolean): CommandOutcome {
        // Android 10+ (API 29+) blocks WifiManager#setWifiEnabled for
        // non-system apps — identical constraint to WifiStatusCommand, so
        // this mirrors that command's honest behaviour instead of faking it.
        val opened = launchIntent(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return CommandOutcome(
            if (opened) "Wi-Fi settings khol di hain — Android is naye version mein main seedhe ${if (on) "on" else "off"} nahi kar sakti."
            else "Wi-Fi settings open nahi ho payi.",
            actionType = "WiFi"
        )
    }

    // ---- bluetooth ------------------------------------------------------

    private fun hasBluetoothConnectPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun findBondedDevice(context: Context, name: String): BluetoothDevice? {
        if (!hasBluetoothConnectPermission(context)) return null
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: BluetoothAdapter.getDefaultAdapter()
        return try {
            adapter?.bondedDevices?.firstOrNull { it.name?.equals(name, ignoreCase = true) == true }
        } catch (_: SecurityException) {
            null
        }
    }

    /**
     * Public BluetoothDevice API has no generic "connect this profile" call
     * — only pairing (createBond) and profile-specific proxies are public.
     * `connect()`/`disconnect()` exist on the platform but are marked
     * @hide; this best-effort reflection call is wrapped so any
     * SecurityException/NoSuchMethodException from a hidden-API restriction
     * on newer Android falls straight through to the honest settings
     * fallback below, never a crash and never a false "connected" claim.
     */
    private fun tryHiddenProfileCall(device: BluetoothDevice, methodName: String): Boolean =
        try {
            val method = device.javaClass.getMethod(methodName)
            method.invoke(device) == true
        } catch (_: Exception) {
            false
        }

    private fun bluetoothConnect(context: Context, deviceName: String, launchIntent: (Intent) -> Boolean): CommandOutcome {
        if (!hasBluetoothConnectPermission(context)) {
            val opened = launchIntent(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return CommandOutcome(
                if (opened) "Bluetooth permission chahiye — settings khol di hain." else "Bluetooth settings open nahi ho payi.",
                actionType = "Bluetooth"
            )
        }
        val device = findBondedDevice(context, deviceName)
            ?: return CommandOutcome("'$deviceName' paired devices mein nahi mila.", actionType = "Bluetooth")
        val connected = tryHiddenProfileCall(device, "connect")
        return if (connected) {
            CommandOutcome("'$deviceName' connect kar diya.", actionType = "Bluetooth", actionEnabled = true)
        } else {
            val opened = launchIntent(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            CommandOutcome(
                if (opened) "'$deviceName' ko seedhe connect nahi kar payi — Bluetooth settings khol di hain, wahan se connect kar lo."
                else "'$deviceName' connect nahi ho saka.",
                actionType = "Bluetooth"
            )
        }
    }

    private fun bluetoothDisconnect(context: Context, deviceName: String, launchIntent: (Intent) -> Boolean): CommandOutcome {
        if (!hasBluetoothConnectPermission(context)) {
            val opened = launchIntent(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return CommandOutcome(
                if (opened) "Bluetooth permission chahiye — settings khol di hain." else "Bluetooth settings open nahi ho payi.",
                actionType = "Bluetooth"
            )
        }
        val device = findBondedDevice(context, deviceName)
            ?: return CommandOutcome("'$deviceName' paired devices mein nahi mila.", actionType = "Bluetooth")
        val disconnected = tryHiddenProfileCall(device, "disconnect")
        return if (disconnected) {
            CommandOutcome("'$deviceName' disconnect kar diya.", actionType = "Bluetooth", actionEnabled = false)
        } else {
            val opened = launchIntent(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            CommandOutcome(
                if (opened) "'$deviceName' ko seedhe disconnect nahi kar payi — Bluetooth settings khol di hain."
                else "'$deviceName' disconnect nahi ho saka.",
                actionType = "Bluetooth"
            )
        }
    }
}
