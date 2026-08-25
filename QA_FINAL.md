# MJ Assistant — Final QA / Build-Fix Record

## User-reported build failure fixed

The reported Kotlin errors in `QwenModelEngine.kt` (`KEY_NAME` unresolved, `companion` unexpected, constants not allowed, missing `}`) were caused by one missing closing brace at the end of `delete()`. The nested `withContext` block was closed, but the surrounding `operationLock.withLock` block was not. Kotlin therefore parsed the following `companion object` as if it were still inside the function.

### Fix

The `delete()` function now closes both nested blocks before `close()`, `captureUsefulMemory()`, and `companion object`. No patch file was added; the complete source file is included in this ZIP.

## Additional cleanup found during full source audit

- Removed a duplicate `DisposableEffect(modelEngine)` in `MainActivity.kt`. The duplicate could call `close()` twice during disposal.
- Kept the existing single lifecycle cleanup.
- No existing feature was intentionally removed.
- Qwen GGUF import/load/unload/delete state flow remains intact.
- Chat remains blocked during model import/load/unload/delete.
- Loaded model cannot be deleted until unloaded.
- Native model release remains in lifecycle cleanup.

## Static checks performed

- ZIP extraction test
- Kotlin source brace/parenthesis/bracket balance audit
- Duplicate model lifecycle cleanup audit
- `KEY_NAME` / companion placement audit
- QwenModelEngine method/block nesting audit
- Gradle settings/dependency file presence audit
- Android manifest/source/resource presence audit
- No patch files introduced

## Build limitation

The execution environment used for this packaging pass does not provide the Android SDK/Gradle executable, so an independent `assembleDebug` compiler run could not be executed here. The user-provided Gradle compiler output was used to identify and fix the exact Kotlin parser error. The project is packaged as a complete source tree for the user's existing Gradle/Android environment.


## Termux AAPT2 fix
The project now explicitly overrides AGP's bundled AAPT2 with the native Termux `aapt2` binary at `/data/data/com.termux/files/usr/bin/aapt2`. This addresses ARM64 Termux AAPT2 daemon startup failures. `build-termux.sh` verifies that AAPT2 exists and can start before invoking Gradle.
