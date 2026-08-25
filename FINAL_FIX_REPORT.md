# MJ Assistant — Final Dual-Model Fix Report

## What was fixed

### Qwen3 1.7B (fast model)
- Uses the same `MJPersonality.SYSTEM_PROMPT` as the 4B model.
- Added an explicit direct-answer instruction so the model answers the current question instead of falling back to generic English greetings.
- Uses Qwen3 `/no_think` mode for fast everyday replies.
- Reduced prompt/history size for phone inference.
- Reduced generation budgets for the fast non-thinking path.
- Added a bounded correction pass when output is empty or clearly a generic assistant deflection.
- Keeps real local inference; no canned/fake answer fallback was added.

### Qwen3 4B Thinking
- Keeps the real Thinking model path.
- Removed the overly small 640-token simple-question cap that could end inside `<think>` and then be correctly discarded as an empty answer.
- Increased bounded simple/normal generation room without returning to the old 4096-token blanket budget.
- Added the same direct-answer/language rules and a bounded correction pass for empty/generic output.
- Prompt/history size remains tightly bounded for phone use.

### Voice / TTS
- UI emojis stay visible in chat.
- Emoji/symbol code points are removed from the text sent to Android TTS, so the voice does not read the emojis themselves.
- Both automatic command replies and model replies use the same sanitizer.

### Thinking UI
- Removed the glass/reveal animation from settled MJ answers.
- The glass effect now exists only on the compact `MJ soch rahi hai…` indicator.
- MJ thinking orb stays outside the glass capsule.
- Glass capsule wraps only the animated dots + thinking text, with a subtle shimmer and pulse.
- Effect size is content-sized instead of full-width.

## Structural / regression checks
- Kotlin delimiter balance checked across all source files.
- No remaining `animateGlass`, `glassActive`, or assistant-reply glass code.
- Direct `TextToSpeech.speak()` usage is centralized through the sanitizer helper.
- 4B and 1.7B both route through the same MJ personality contract.
- 1.7B explicitly uses `/no_think`; 4B remains the Thinking slot.
- One-native-model-at-a-time coordinator remains intact.
- Torch action remains gated to actual Torch outcomes only.
- Previous `Any` model-state type issue is still removed; chat UI uses `ChatModelState`.

## Build verification note
The supplied environment has Kotlin available for syntax parsing but does not contain the Android SDK/Gradle dependency environment needed for a real Android `assembleDebug`. Therefore this report does **not** claim a full APK build pass here.
