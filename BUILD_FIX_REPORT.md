# Build Fix Report

## Reported failure
`MainActivity.kt:271:30 Unresolved reference: sanitizeForSpeech`

## Root cause
`speak()` was a local function inside `MJApp()` and referenced another local function declared later in the same local scope. Kotlin local function declarations are not available for that forward reference in this context, so the compiler reported `Unresolved reference`.

## Fix
Moved `sanitizeForSpeech()` to file scope above `MJApp()`. No behavior was changed: it still removes emoji/code-point presentation characters from the copy sent to Android TTS while keeping the original response unchanged for the chat UI.

## Verification
- `sanitizeForSpeech` has exactly one definition and one call site.
- Kotlin source delimiter scan: PASS.
- AndroidManifest/resources XML parse: PASS.
- No changes to model engines, command registry, memory/history, or model-selection behavior for this build-only fix.
- The `Unable to strip ... lib*.so` messages in the supplied log are packaging warnings; they are not the cause of `compileDebugKotlin` failure.

## Limitation
A real Android Gradle build was not run in this environment because the Android SDK/Gradle toolchain is not installed here. The reported compiler error was fixed at source level and the project was rescanned.
