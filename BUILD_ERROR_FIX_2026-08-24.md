# Build Error Fix — 2026-08-24

Fixed the three Kotlin compiler errors reported by the user's Termux build:

1. `Qwen17BModelEngine.kt`: `correctionPrompt` was referenced before its local declaration. The declaration now occurs before `fitGenerationBudget()`.
2. `QwenModelEngine.kt`: same `correctionPrompt` declaration-order bug fixed.
3. `QwenModelEngine.kt`: `needsDeepReasoning()` was referenced by `generationBudget()` but was missing. The helper is now defined in the class.

Also removed two unused local variables from the touched engines to keep the compiler output clean.

Verification:
- Both touched Kotlin engine files compile with `kotlinc` against local Android/llama/coroutines stubs: PASS.
- No remaining `correctionPrompt` use-before-declaration.
- Both engines contain `needsDeepReasoning()` where referenced.
- No active 0.6B/small-model reference in `app/src/main`.
- ZIP integrity checked after packaging.

Note: this environment does not contain the user's full Android SDK/Gradle/native Android dependency environment, so this is not claimed as a real `assembleDebug` run. The exact errors reported from the user's Gradle output were addressed and the affected Kotlin sources were compiler-checked with API stubs.
