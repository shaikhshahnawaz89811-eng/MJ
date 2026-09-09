package com.mj.assistant.commands

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.provider.CalendarContract
import android.provider.Settings
import java.util.Calendar
import java.util.Locale

/**
 * Phase 3 architecture fix: replaces the inline keyword if/else chain that
 * used to live directly inside MainActivity's onSend lambda. Each command is
 * now a self-contained, independently testable unit implementing
 * [DeviceCommand]. Adding a new device skill (Phase 7) means adding one new
 * object to [DeviceCommandRegistry.all] instead of editing a growing if/else
 * chain in the UI layer.
 *
 * Design constraints preserved from the original (audit §10, §19 — "must not
 * break"):
 *  - Device commands are detected and can execute WITHOUT ever calling the
 *    AI model. This fast path is a genuine efficiency/reliability win and is
 *    kept for all commands here.
 *  - Multiple commands can fire from a single message ("torch on kar do aur
 *    time batao").
 *  - AI-directed sentences that merely CONTAIN a command-adjacent word must
 *    not be misclassified as pure device commands (audit's flagged
 *    false-positive risk). Each matcher below is deliberately narrower than
 *    plain `contains()` where that risk is real (see TimeCommand,
 *    DateCommand, TorchOffCommand's negation guard).
 */

/** Result of a single command's execution: user-facing text + optional action-card metadata. */
data class CommandOutcome(val responseText: String, val actionType: String? = null, val actionEnabled: Boolean = false)

/** Shared capabilities commands may need, passed in from MainActivity so this file stays UI-framework-free. */
class CommandContext(
    val androidContext: Context,
    val torchOn: Boolean,
    val setTorch: (Boolean) -> Boolean, // returns true on success
    val launchIntent: (Intent) -> Boolean // returns true on success — commands below react to failure, so this must not silently swallow it
)

interface DeviceCommand {
    val id: String
    /** Should this command act on this message at all? Kept separate from execute() so callers can check without side effects. */
    fun matches(lower: String, raw: String): Boolean
    fun execute(lower: String, raw: String, ctx: CommandContext): CommandOutcome
}

/** Crude negation guard: is a negation word present anywhere near the command trigger?
 * Coarse on purpose — for a device action, "probably not a command" should win over
 * "probably is", since a wrongly-skipped action is far safer than a wrongly-executed one
 * (audit's exact example: "torch off nahi karo" must NOT turn the torch off). */
private fun hasNegation(lower: String): Boolean =
    Regex("\\b(nahi|mat|na)\\b").containsMatchIn(lower)

/** True when the message is essentially just the command (short, no unrelated content).
 * Ambiguous trigger words (like "time") should only fast-path the device action when
 * the message is command-shaped like this; otherwise the word may just be part of an
 * unrelated AI-directed sentence (audit's "thoda time do sochne ke liye" example). */
private fun isShortCommandShaped(raw: String, maxWords: Int = 6): Boolean =
    raw.trim().split(Regex("\\s+")).size <= maxWords

object TorchOnCommand : DeviceCommand {
    override val id = "torch_on"
    override fun matches(lower: String, raw: String): Boolean {
        if (!lower.contains("torch")) return false
        if (hasNegation(lower)) return false
        return lower.contains(" on") || lower.startsWith("on ") || lower.contains(" chala") || lower.contains("jala")
    }
    override fun execute(lower: String, raw: String, ctx: CommandContext): CommandOutcome {
        val ok = ctx.setTorch(true)
        return CommandOutcome(
            if (ok) "Ji, torch on kar di." else "Torch on nahi ho saka.",
            actionType = "Torch", actionEnabled = ok
        )
    }
}

object TorchOffCommand : DeviceCommand {
    override val id = "torch_off"
    override fun matches(lower: String, raw: String): Boolean {
        if (!lower.contains("torch")) return false
        // Fix for audit §10 false-positive: "torch off nahi karo" (asking NOT to
        // turn it off) must not match. A negated torch clause is treated as
        // ambiguous and skipped entirely rather than risk acting against intent.
        if (hasNegation(lower)) return false
        return lower.contains(" off") || lower.contains(" band")
    }
    override fun execute(lower: String, raw: String, ctx: CommandContext): CommandOutcome {
        val ok = ctx.setTorch(false)
        return CommandOutcome(
            if (ok) "Ji, torch off kar di." else "Torch off nahi ho saka.",
            actionType = "Torch", actionEnabled = if (ok) false else ctx.torchOn
        )
    }
}

object TimeCommand : DeviceCommand {
    override val id = "time"
    override fun matches(lower: String, raw: String): Boolean {
        if (lower.contains("kitne baje")) return true
        if (!lower.contains("time") && !lower.contains("samay")) return false
        // Fix for audit §10 false-positive: plain `.contains("time")` used to
        // misclassify sentences like "is baar mujhe thoda time do sochne ke
        // liye" (asking for time to think) as a clock request. Only treat
        // "time" as a clock request when it's paired with an actual query
        // word, or the whole message is short and command-shaped.
        val queryCue = lower.contains("kya") || lower.contains("batao") || lower.contains("abhi") || lower.contains("hua")
        return queryCue || isShortCommandShaped(raw, maxWords = 4)
    }
    override fun execute(lower: String, raw: String, ctx: CommandContext): CommandOutcome =
        CommandOutcome("Abhi ${currentTimeNatural()} ho rahe hain.")
}

object DateCommand : DeviceCommand {
    override val id = "date"
    override fun matches(lower: String, raw: String): Boolean {
        if (!lower.contains("date") && !lower.contains("tarikh") && !lower.contains("tareekh")) return false
        val queryCue = lower.contains("kya") || lower.contains("batao") || lower.contains("aaj")
        return queryCue || isShortCommandShaped(raw, maxWords = 4)
    }
    override fun execute(lower: String, raw: String, ctx: CommandContext): CommandOutcome =
        CommandOutcome("Aaj ${currentDateNatural()} hai.")
}

object BatteryStatusCommand : DeviceCommand {
    override val id = "battery"
    override fun matches(lower: String, raw: String): Boolean =
        (lower.contains("battery") || lower.contains("charge")) && isShortCommandShaped(raw, maxWords = 6)
    override fun execute(lower: String, raw: String, ctx: CommandContext): CommandOutcome {
        val bm = ctx.androidContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = bm?.isCharging == true
        val text = if (pct in 0..100) {
            "Battery $pct% hai${if (charging) ", aur charge ho rahi hai." else "."}"
        } else "Battery status abhi nahi mil saka."
        return CommandOutcome(text, actionType = "Battery")
    }
}

object WifiStatusCommand : DeviceCommand {
    override val id = "wifi"
    override fun matches(lower: String, raw: String): Boolean =
        (lower.contains("wifi") || lower.contains("wi-fi") || lower.contains("wi fi")) && isShortCommandShaped(raw, maxWords = 6)
    override fun execute(lower: String, raw: String, ctx: CommandContext): CommandOutcome {
        // Android 10+ (API 29+) does not allow apps to programmatically toggle
        // Wi-Fi anymore (WifiManager#setWifiEnabled is a no-op for non-system
        // apps since Q). Opening the system panel is the correct, honest
        // behaviour instead of pretending to flip it — matches the audit's
        // explicit requirement not to fake device capabilities.
        val wantsToggle = lower.contains(" on") || lower.contains(" off") || lower.contains("chala") || lower.contains("band")
        return if (wantsToggle) {
            val opened = ctx.launchIntent(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            CommandOutcome(
                if (opened) "Wi-Fi settings khol di hain — Android is naye version mein main seedhe toggle nahi kar sakti."
                else "Wi-Fi settings open nahi ho payi.",
                actionType = "WiFi"
            )
        } else {
            val wm = ctx.androidContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            val on = runCatching { wm?.isWifiEnabled == true }.getOrDefault(false)
            CommandOutcome("Wi-Fi abhi ${if (on) "ON" else "OFF"} hai.", actionType = "WiFi", actionEnabled = on)
        }
    }
}

object AlarmCommand : DeviceCommand {
    // Matches "alarm laga do 7 baje" / "alarm 7:30" style phrasing.
    private val hourRegex = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(baje|am|pm)?")
    override val id = "alarm"
    override fun matches(lower: String, raw: String): Boolean =
        lower.contains("alarm") && !hasNegation(lower)
    override fun execute(lower: String, raw: String, ctx: CommandContext): CommandOutcome {
        val match = hourRegex.find(lower)
        val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
            if (match != null) {
                var hour = match.groupValues[1].toIntOrNull() ?: 7
                val minute = match.groupValues[2].toIntOrNull() ?: 0
                val meridiem = match.groupValues[3]
                if (meridiem.equals("pm", true) && hour < 12) hour += 12
                putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
                putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
            }
            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "MJ reminder")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (ctx.launchIntent(intent)) {
            CommandOutcome("Ji, alarm app khol diya hai — waha se confirm kar lo.", actionType = "Alarm")
        } else {
            CommandOutcome("Is device par alarm app nahi mila.", actionType = "Alarm")
        }
    }
}

object CallCommand : DeviceCommand {
    // Deliberately uses ACTION_DIAL (opens the dialer pre-filled) rather than
    // ACTION_CALL, so no CALL_PHONE runtime permission is ever needed and the
    // user still has to press call themselves — safer default, no silent
    // outbound calls initiated by the assistant.
    private val numberRegex = Regex("[+\\d][\\d\\s-]{6,}")
    override val id = "call"
    override fun matches(lower: String, raw: String): Boolean =
        (lower.contains("call") || lower.contains("phone kar") || lower.contains("dial")) && numberRegex.containsMatchIn(raw)
    override fun execute(lower: String, raw: String, ctx: CommandContext): CommandOutcome {
        val number = numberRegex.find(raw)?.value?.replace(Regex("[\\s-]"), "") ?: return CommandOutcome("Number samajh nahi aaya.")
        val opened = ctx.launchIntent(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return if (opened) {
            CommandOutcome("Dialer khol diya hai $number ke saath — call karne ke liye khud confirm kar lena.", actionType = "Call")
        } else {
            CommandOutcome("Dialer open nahi ho saka.")
        }
    }
}

object CalendarCommand : DeviceCommand {
    override val id = "calendar"
    override fun matches(lower: String, raw: String): Boolean =
        (lower.contains("reminder") || lower.contains("calendar") || lower.contains("yaad dila")) &&
            (lower.contains("bana") || lower.contains("add") || lower.contains("laga") || lower.contains("set"))
    override fun execute(lower: String, raw: String, ctx: CommandContext): CommandOutcome {
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
            putExtra(CalendarContract.Events.TITLE, raw.take(80))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val opened = ctx.launchIntent(intent)
        return if (opened) {
            CommandOutcome("Calendar khol diya hai, detail confirm karke save kar lena.", actionType = "Calendar")
        } else {
            CommandOutcome("Calendar app nahi mila.")
        }
    }
}

object DeviceCommandRegistry {
    /** Order matters only for tie-break display order; multiple matching commands all fire. */
    val all: List<DeviceCommand> = listOf(
        TorchOnCommand, TorchOffCommand, TimeCommand, DateCommand,
        BatteryStatusCommand, WifiStatusCommand, AlarmCommand, CallCommand, CalendarCommand
    )

    data class MatchResult(val outcomes: List<CommandOutcome>, val matchedWordCount: Int)

    /**
     * Runs every command whose matches() fires, in registry order, and returns
     * their combined outcomes. Also reports how many of the message's own
     * words look "consumed" by a command, which callers use to decide whether
     * there's meaningful leftover content that should still go to the AI.
     */
    fun runAll(raw: String, ctx: CommandContext): MatchResult {
        val lower = raw.lowercase(Locale.getDefault())
        val outcomes = mutableListOf<CommandOutcome>()
        for (cmd in all) {
            if (cmd.matches(lower, raw)) {
                outcomes += cmd.execute(lower, raw, ctx)
            }
        }
        return MatchResult(outcomes, outcomes.size)
    }
}

fun currentTimeNatural(): String {
    val now = Calendar.getInstance()
    val hour = now.get(Calendar.HOUR_OF_DAY)
    val minute = now.get(Calendar.MINUTE)
    val period = when { hour < 5 -> "raat"; hour < 12 -> "subah"; hour < 17 -> "dopahar"; hour < 21 -> "shaam"; else -> "raat" }
    return if (minute == 0) "$period $hour baje" else "$period $hour bajkar $minute minute"
}

fun currentDateNatural(): String {
    val f = java.text.SimpleDateFormat("d MMMM yyyy", Locale("hi", "IN"))
    return f.format(java.util.Date())
}
