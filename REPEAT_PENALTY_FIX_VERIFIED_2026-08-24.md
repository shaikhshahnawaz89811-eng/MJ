# Repeat-penalty compile error — verified fix — 2026-08-24

## What broke the build
`Qwen17BModelEngine.kt:395-396` and `QwenModelEngine.kt:370-371` called
`LlamaConfig(..., repeatPenalty = 1.15f, repeatLastN = 64)`. Gradle failed with:

```
No parameter with name 'repeatPenalty' found.
No parameter with name 'repeatLastN' found.
```

## Why (now confirmed, not guessed)
The previous fix (see `REPLY_LOOP_LANGUAGE_THREAD_FIX_2026-08-24.md`) admitted
those two field names were borrowed from other llama.cpp Kotlin bindings and
were never checked against this project's actual `.aar`.

That check has now been done. Decompiling the real dependency —
`dev.ffmpegkit-maintained:llama-android:0.1.1` (`javap -p -l` on
`LlamaConfig.class`, extracted from the `.aar` in the Gradle cache) — shows
its full constructor:

```
LlamaConfig(contextSize, threads, gpuLayers, temperature, topP, topK, seed)
```

No `repeatPenalty`, no `repeatLastN`. A `strings` grep across every class in
`dev.ffmpegkit.llama.*` for `penalt|repeat|lastn|frequency|presence` also came
back empty — this library version does not expose *any* repetition control,
not just under a different name. It genuinely cannot be set at the sampler.

## Fix applied
1. Removed both invalid named arguments from both `LlamaConfig(...)` calls —
   this alone fixes the compile error.
2. Since the repetition-loop bug those two lines were trying to fix is real
   (small quantized models on greedy/low-temperature decoding can loop the
   same sentence forever) but can't be prevented at the library level here,
   added an **output-side** fix instead: `findRepetitionLoop()` /
   `stripRepetitionLoop()` in both engines scan the generated text for a
   sentence or 6-word chunk that repeats 3x in a row and cut the reply right
   before the loop starts. Wired into both the first generation pass and the
   bounded retry pass in `reply()`.
   - If the loop starts late (model looped after a real answer), the user
     just gets the clean answer with the looped tail removed.
   - If the loop starts immediately (the 4B "stuck" case, looping inside
     `<think>`), stripping it leaves the answer blank, which already trips
     the existing `needsCorrection` retry path — no new failure mode, reuses
     what's already there for blank/echoed/generic-deflection answers.

## Not changed
- `temperature`/`topP`/`topK` tuning and the thread-ceiling raise from the
  previous fix are untouched — unrelated to the compile error and still
  reasonable.
- `gpuLayers` stays at `0`, still unverified for this device's GPU backend.

## Verification
- No remaining `repeatPenalty`/`repeatLastN` usage anywhere in
  `app/src/main/java/` (only in comments explaining why they're gone).
- Brace balance checked on both edited files.
- No Android/Gradle/Kotlin toolchain is available in this environment, so
  `assembleDebug` itself was not re-run here — please run it in Termux and
  send back the exact error text if anything still fails.
