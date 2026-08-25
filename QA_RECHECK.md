# QA Recheck

## Static checks performed
- Source tree extracted from the supplied ZIP and rescanned.
- Legacy small-model references are absent from the active source.
- `sanitizeForSpeech` is defined before use.
- Both Qwen engines use the shared `ModelRuntimeCoordinator`.
- No wake-word implementation was added.
- Only one GGUF model may be native-loaded at once.
- 1.7B default active selector remains enabled in `MainActivity`.
- Direct device commands bypass the LLM when the message is command-only.
- LLM is used for the remaining natural-language portion of mixed requests.
- Qwen3 chat calls use the runtime's system/user prompt API without fake `User:` role markers.
- 1.7B simple factual/casual messages receive no old conversation context.
- Memory writes run on `Dispatchers.IO`.
- 1.7B model import validates `.gguf` and a Qwen3 1.7B filename.
- Model unload/delete lifecycle releases the native coordinator owner.

## Compiler limitation
This sandbox does not contain the Android SDK/Gradle project dependencies or the user's Termux Gradle/AAPT2 environment, so a real `:app:assembleDebug` cannot honestly be reported as executed here. `kotlinc` syntax checking was attempted; its diagnostics are dependency-resolution errors for Android/AndroidX/llama classes, not Kotlin parser failures.

## Device validation still required
After extracting this ZIP in Termux:
1. `gradle --stop`
2. `gradle --no-daemon clean`
3. `gradle --no-daemon :app:assembleDebug --stacktrace`
4. Install the new APK after uninstalling the old APK.
5. Load only the 1.7B model first.
6. Test: `hello`, `India ka capital kya hai`, `time kya hua hai`, `create 2 line python code`, a 5–10 step plan, and a follow-up using `ye/wahi/continue`.
7. Then unload 1.7B, load 4B, and test one hard coding/reasoning request.
