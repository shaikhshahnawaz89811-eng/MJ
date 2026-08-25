# Reply loop / 4B stuck / language / thread fix — 2026-08-24

## Reported symptoms
1. Qwen3 1.7B Fast repeats the same sentence over and over and never gives the actual answer (screenshot: "india ka capital kya hai" looped a Hindi sentence about "rajdhani" without ever saying "Delhi").
2. Qwen3 4B Thinking appears completely stuck — no reply, looks frozen/looping.
3. Model replies in Devanagari script even when the user typed in Roman/English letters (Hinglish), though the intent is Roman Hindi/Hinglish out.
4. Requested: raise CPU thread usage toward 6 / use GPU if possible.

## Root causes found in code

**1. No repetition penalty was ever set on inference (both engines).**
`Qwen17BModelEngine.load()` and `QwenModelEngine.load()` built `LlamaConfig` with
`temperature`, `topP`, `topK` only — no `repeatPenalty` / `repeatLastN`. Small
quantized models decoding without any repetition control are prone to getting
stuck in an n-gram loop, which is exactly the "same sentence forever" behavior
in the screenshot.

**2. Qwen3-4B-Thinking-2507 "stuck" was the same bug, worse.** This model is
thinking-only — every reply first generates a hidden `<think>...</think>` block
before the visible answer, and `generationBudget()` already grants up to 3072
tokens even for a simple greeting because of that. With no repeat penalty, the
thinking phase can loop indefinitely inside `<think>`, burning the entire
token budget on a CPU-only phone (slow to begin with) and never reaching
`</think>` — so `cleanThinkingOutput()` returns blank and it *looks* frozen.
It is not literally hung; it's spending minutes looping tokens it will never
show you.

**3. Language/script mismatch was never checked after generation.**
`MJPersonality.SYSTEM_PROMPT` does say "Roman Hindi/Hinglish -> Roman
Hindi/Hinglish," but a 1.7B/4B model doesn't reliably obey a script
instruction inside a system prompt — there was no code-level check catching
this, so a Devanagari reply to a Roman-script question went straight to the
user.

**4. Threads were capped low.** 1.7B was `coerceIn(3, 4)`; 4B was already
`coerceIn(4, 6)`. `gpuLayers` is hardcoded to `0` in both — this `.aar`
(`dev.ffmpegkit-maintained:llama-android:0.1.1`) is not confirmed to ship a
working GPU (Vulkan/OpenCL) backend for arbitrary Android devices, so forcing
`gpuLayers > 0` blind could crash the native loader instead of speeding
things up.

## Fixes applied
- Added `repeatPenalty = 1.15f`, `repeatLastN = 64` to both `LlamaConfig`
  calls (1.7B and 4B). This directly targets root causes #1 and #2.
- Added `isScriptMismatch(input, answer)` to both engines: if the user's
  message has Latin letters and no Devanagari, but the model's answer
  contains Devanagari, that now counts as `needsCorrection` (same bounded
  retry path already used for blank/echoed/generic answers) and the retry
  prompt explicitly tells the model to answer in Roman letters only.
- Raised 1.7B thread ceiling from `(3, 4)` to `(3, 6)`, matching the 4B
  engine's existing `(4, 6)`.
- Left `gpuLayers = 0` in both, with a code comment explaining why (unverified
  GPU backend support in this library build) instead of guessing.

## Not changed / needs on-device verification
- `repeatPenalty` / `repeatLastN` field names are based on the same fields
  used by comparable llama.cpp Android/Kotlin bindings (e.g. Llamatik,
  flutter_llama) — this project's exact `LlamaConfig` (from
  `dev.ffmpegkit-maintained:llama-android:0.1.1`) was not independently
  confirmed field-by-field (no network access available to fetch its
  source/docs in this session). If the build fails on those two named
  arguments, that error will show the actual accepted parameter names for
  this library version — tell me the compiler error and I'll match it exactly.
- GPU offload (`gpuLayers`) was deliberately left untouched rather than
  guessed at, to avoid trading "slow" for "crashes on load."
