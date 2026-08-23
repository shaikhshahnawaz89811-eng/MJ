# MJ Assistant — Stage 1 UI + Basic AI Chat

## Current stage
- Chat-first Android UI
- Animated MJ orb: glow, pulse, rotating marks and moving glass/mirror band
- Animated MJ replies, waveform and thinking indicator
- History / Actions / Profile
- Scrolling where content grows
- `AIEngine` abstraction + `LocalAIEngine` basic offline chat
- MJ personality foundation
- Time/date/greeting/basic conversational replies
- Torch command recognition (confirmation only; real hardware control is next)

## Build on phone
Use AndroidIDE or a Termux Gradle environment. `build-termux.sh` runs `gradle --no-daemon assembleDebug` and prints the APK path.

The project intentionally does not bundle a Gradle wrapper JAR, Android SDK, or model files; those are environment/toolchain assets and should not be duplicated inside the source ZIP.

## Planned real pipeline
Microphone -> Speaker Verification -> Vosk STT -> Conversation State -> Qwen AIEngine -> Action Router / Answer -> TTS

Qwen `.task`, `model.safetensors`, and Vosk will be integrated only after their exact runtime/format is verified. This avoids wiring an incompatible model into the APK.
