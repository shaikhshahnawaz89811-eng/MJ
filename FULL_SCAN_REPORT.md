# MJ Assistant — Full Source Scan / Final Packaging Report

## Scope

Scanned the complete source archive before packaging:

- Gradle/settings/properties and Termux build script
- Android manifest and theme
- Main Compose UI / navigation / chat send path
- Qwen model import/load/reply/unload/delete lifecycle
- Conversation memory and persistent chat history
- Device command registry and device-action UI
- Voice input / TTS lifecycle
- Thinking/response animations
- Existing QA/change reports for contradictory documentation

## Findings fixed in this pass

### 1. Non-torch action cards could control the torch
`ChatMessageRow` passed every `ActionState` to `DeviceActionCard`, even for Battery,
Wi-Fi, Alarm, Call, and Calendar outcomes. `DeviceActionCard` is a real torch
control, so tapping a Battery/Wi-Fi/etc. card could toggle the camera torch.

**Fix:** only render `DeviceActionCard` when `action.type == "Torch"`.

### 2. Thinking indicator had no glass treatment
The `MJ soch rahi hai…` row only had the orb and bouncing dots.

**Fix:** added a lightweight glass capsule with:
- translucent layered background
- thin glass border
- moving white shimmer
- soft purple inner glow
- subtle pulse
- existing thinking orb and dots preserved

No model/inference code is coupled to this animation.

### 3. Qwen context documentation was contradictory
The code actually loaded `contextSize = 4096`, while an older report claimed it had
been raised to 8192.

**Fix:** documentation now reflects the actual 4096 phone configuration.

## Existing checks re-run

- ZIP extraction: PASS
- Kotlin delimiter balance (`{}`, `()`, `[]`): PASS for all Kotlin files
- Targeted regression assertions: PASS
- No `DeviceActionCard(msg.action.type, ...)` generic wiring remains
- `DeviceActionCard` is only rendered for Torch actions
- `contextSize = 4096` is the actual load setting
- No 8192-context claim remains in the current engine code
- Manifest, Gradle files, source files and resources are all present
- No patch-only archive was created; this is a complete source project

## Important build limitation

A real Android `assembleDebug` build was not possible in this sandbox because the
Android SDK/Gradle toolchain is not installed here. The project therefore has not
been falsely marked as device-build verified.

The llama-android dependency is real and currently published as
`dev.ffmpegkit-maintained:llama-android:0.1.1`; its documented free API uses
`Llama.complete()` and CPU/NEON, while token streaming is listed as a Pro feature.

## Runtime expectation

The glass animation is UI-only and should not increase Qwen inference work.
The 4B Thinking model itself remains a CPU-heavy thinking model; the official model
card notes that this 2507 Thinking variant has increased thinking length and is
intended especially for complex reasoning.


## Small-model module added in this revision

- Added `Qwen17BModelEngine.kt` for a separate Qwen3-1.7B GGUF slot.
- Added `ModelRuntimeCoordinator.kt` so only one native GGUF model can be loaded at a time.
- Profile now exposes separate lifecycle controls for 4B and 1.7B.
- Active-model selector routes normal AI chat to the selected engine.
- Existing device-command fast path is unchanged and remains model-independent.
- Existing chat history, conversation memory, personality, voice output, empty-reply cleanup and glass thinking UI remain shared.
- 1.7B model uses a 2048 context and fewer CPU threads to keep the test module lighter than the 4B slot.

### Important limitation
The archive was statically checked in this environment, but an Android SDK + Gradle build and a real Qwen GGUF phone inference run were not available here. Therefore this package does not claim a device-side build or latency benchmark pass.
