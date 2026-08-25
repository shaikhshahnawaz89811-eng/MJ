# Roadmap Progress — Phases 2–7 (post audit)

Base: `MJ_Assistant_Audit_Report.md`'s §20 roadmap. Phase 1 (the 8 numbered
bugs) was already fixed before this pass — confirmed by reading the
`// Fix (Bug N)` comments already present in `QwenModelEngine.kt` and
`MainActivity.kt` — so this pass covers everything the audit filed under
Phase 2 through Phase 7, plus the standing UI gaps repeatedly flagged in §11.

**Important, stated plainly:** this pass was written and reviewed in a
sandbox with no Android SDK, no Gradle, and no network — the same
constraint the original audit operated under. Every change below was
checked for brace/paren balance and for matching Kotlin API signatures by
hand, but **none of it has been compiled**. Build it with a real Android
toolchain before shipping, and treat this file as a change log to review
against, not a guarantee.

---

## Phase 2 — Performance

- **Fixed a real Main-thread-blocking bug that Phase 1 didn't cover**: in
  `QwenModelEngine.reply()`, after the inference call returns,
  `memory.addRecentTurn()` and `captureUsefulMemory()` were running back on
  whatever dispatcher the caller's `scope.launch` used (Main, via
  `rememberCoroutineScope()` in `MJApp()`). `captureUsefulMemory()` calls
  `ConversationMemory.remember()` → `writeFacts()`, which ends in a
  synchronous `SharedPreferences.commit()` — so every "yaad rakhna" or
  relationship-word turn was blocking the UI thread. Both calls now run
  inside `withContext(Dispatchers.IO)`. Neither function's own semantics
  changed (facts still commit synchronously *relative to that IO call*;
  turns still persist via async `.apply()`).
- `readTurns()`'s redundant reparse (Bug 2) was already fixed before this
  pass — left untouched.

## Phase 3 — Architecture

- **Persisted visible chat history** — new `ai/ChatHistoryStore.kt`. The
  audit's exact complaint (§3, §11, §18) was that `messages` lived only in
  `remember { }` and reset to the welcome message on every relaunch. It now
  loads from/saves to `ChatHistoryStore` on every change.
  - **Deliberately did not add Room/SQLite + KSP.** The roadmap suggested
    Room, but this project has no annotation-processor toolchain, no Gradle
    wrapper, and builds via a bare `gradle` command in Termux (audit §1).
    Adding Room+KSP is a real build-toolchain change I could not verify
    compiles in this environment, and a broken build is worse than a
    SharedPreferences-based store. `ChatHistoryStore` reuses the exact
    JSON-blob-in-SharedPreferences pattern `ConversationMemory` already uses
    successfully in this codebase — same risk profile as code that's known
    to work here, capped at 400 messages, async writes.
  - `HistoryScreen` also now shows assistant replies, not just user
    messages (a separate gap the audit flagged in the same section).
- **Command registry** — new `commands/DeviceCommands.kt`. Replaces the
  inline `wantsTorchOn`/`wantsTime`/etc. if/else chain that lived in
  `onSend` with a `DeviceCommand` interface + `DeviceCommandRegistry`. Adding
  a new device skill is now "add one object to the registry," not "extend a
  growing if/else in the UI layer."
  - Preserved exactly, per audit §19 ("must not break"): device commands
    still execute without ever calling the AI model when the message is
    just the command; multiple commands can still fire from one message.
  - **Also fixed the two concrete false-positive risks the audit named in
    §10**, since the registry made them easy to isolate and test:
    - `"torch off nahi karo"` no longer flips the torch — any negation word
      (`nahi`/`mat`/`na`) near a torch trigger now skips that action
      entirely rather than risk acting against the user's intent.
    - Plain `.contains("time")` (which misread `"thoda time do sochne ke
      liye"` as a clock request) now requires either a real query cue
      (`kya`/`batao`/`abhi`/`hua`) or a short, command-shaped message.
      Same treatment applied to `DateCommand`.

## Phase 4 — AI capabilities

- **Streaming**: the free `llama-android:0.1.1` API exposes full-response
  `Llama.complete()` but not token streaming. The UI therefore reveals the
  completed answer progressively as a visual effect; this is explicitly not
  real model streaming.

## Phase 5 — Memory

- Fact update-in-place (Bug 5) and the gendered-possessive relationship fix
  (Bug 4) were **already fixed** before this pass (confirmed by the
  `// Fix (Bug 5)` / `// Fix (Bug 4)` comments already in
  `QwenModelEngine.kt`). Nothing further was needed here.

## Phase 6 — Voice

- Manual-speak-button decoupling (Bug 6) and the TTS `onInit` timing fix
  (Bug 7) were **already fixed** before this pass. What this pass added on
  top:
  - A real `OrbState.SPEAKING` visual wired to
    `TextToSpeech.setOnUtteranceProgressListener()`'s actual
    `onStart`/`onDone`/`onError` callbacks — not a guessed timer. See Phase
    UI notes below.

## Phase 7 — Future skills

Implemented as new `DeviceCommand`s (all in `commands/DeviceCommands.kt`),
scoped to what's honestly buildable without a paid API key or a risky
permission:

- **Battery status** — real percentage + charging state via `BatteryManager`.
- **Wi-Fi status** — real on/off read via `WifiManager.isWifiEnabled`.
  Toggling opens the system Wi-Fi panel instead of pretending to flip it —
  Android 10+ (API 29+) blocks non-system apps from programmatic Wi-Fi
  toggling, so faking it would violate the audit's own "don't fake device
  capabilities" instruction.
- **Alarm** — parses a spoken hour/minute and opens the system clock app via
  `AlarmClock.ACTION_SET_ALARM`, prefilled; user confirms in the clock app.
- **Call** — opens the dialer prefilled via `ACTION_DIAL` (deliberately not
  `ACTION_CALL`), so no `CALL_PHONE` runtime permission is ever requested
  and MJ never silently places a call.
- **Calendar reminder** — opens the calendar app's event-creation screen via
  `ACTION_INSERT`, prefilled with the message text; user confirms/saves.

**Not implemented, with reasons:**
- **Weather** — needs a paid/keyed third-party API; nothing in this repo
  configures one, and inventing a hardcoded key would be worse than not
  shipping the feature.
- **SMS/WhatsApp/Instagram automation** — sending messages on the user's
  behalf without an explicit per-message confirmation step is a real misuse
  surface (spam, impersonation); out of scope for a same-pass addition
  without a UX review.
- **File operations / coding workspace / general skills-plugins** — the
  audit's own roadmap (§20, Phase 7) says these should each be evaluated
  individually once the registry exists; the registry now exists, so this
  is deliberately left as the next incremental step rather than guessed at
  in bulk.

## UI gaps (repeatedly flagged in §11, not tied to one phase number)

- **Settings gear icon** was a dead `onClick = {}` — now opens a real
  `SettingsScreen` (voice toggle, clear visible chat, clear long-term
  memory).
- **`ConversationMemory.clearMemory()`** existed in code but was never
  called from any UI — now wired to the Settings screen's "Clear MJ's
  long-term memory" button (separate from "Clear visible chat", since
  they're genuinely separate stores).
- **Wi-Fi / Bluetooth / Mobile Data / Battery / Time / Date rows in
  `ActionsScreen`** had no `.clickable` at all — now open the matching real
  system panel or show a live value.
- **Orb state differentiation** — `MessageOrb` now takes an `OrbState`
  (`IDLE`/`THINKING`/`SPEAKING`/`ERROR`) instead of zero parameters.
  `THINKING` drives the existing `ThinkingRow`; `SPEAKING` is wired to real
  TTS callbacks; `ERROR` reflects a real `ModelState.error`. `DynamicMJOrb`
  (the splash-screen orb) was deliberately left untouched — its rotation
  mechanic is called out in the audit (§19) as something to extend, not
  replace, and extending it wasn't needed for any of the above.

## Manifest change

Added `android.permission.ACCESS_WIFI_STATE` (normal-protection, no runtime
prompt) for the new Wi-Fi status read. No other new permissions were added —
Alarm/Call/Calendar all use implicit intents that hand off to a system app
rather than requesting a dangerous permission directly.
