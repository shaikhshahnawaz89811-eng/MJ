# MJ Assistant — Model Reply Fix — 2026-08-24

## User-reported symptoms
- Qwen3 1.7B was loaded but produced incorrect factual answers such as India -> Lucknow.
- Qwen3 4B Thinking loaded but often stayed in the thinking state / produced no visible reply.

## Root causes found in the supplied source
1. The previous performance pass reduced the 1.7B native context to 2048 and capped it at 2–3 CPU threads. That was too aggressive for the target phone and left less room for prompt + generation.
2. The 1.7B sampling configuration was relatively random for a small factual model (`temperature=0.7`, `topP=0.8`). It was changed to a lower-temperature factual configuration.
3. The 1.7B generation ceilings were reduced as low as 192 tokens. Although non-thinking mode is used, these ceilings were unnecessarily tight for a general assistant and made truncated/weak answers more likely.
4. The 4B Thinking model was limited to 1536–2048 output tokens while also using a 3072-token context. A thinking-only model can consume a substantial part of its generation budget before emitting the visible answer, so the previous ceiling could terminate inside `<think>`.

## Fixes applied
- 1.7B context: 2048 -> 3072.
- 1.7B CPU threads: 2–3 -> 3–4.
- 1.7B sampling: temperature 0.35, topP 0.9, topK 40.
- 1.7B simple generation: 192/256 -> 384/512; other task ceilings raised proportionally.
- 4B context: 3072 -> 4096.
- 4B CPU threads: 3–4 -> 4–6.
- 4B generation: restored enough headroom (3072–4096 before context fitting) for the hidden thinking phase and visible answer.
- Added a stronger factual anti-guess instruction to the shared MJ system prompt.

## Features deliberately preserved
No device command, voice input, TTS, chat/history, memory, model import/load/unload/delete, model selector, orb/thinking UI, or one-model-at-a-time safety behavior was removed.

## Verification
- Source tree was re-scanned after the patch.
- Changed Kotlin files retain balanced braces and the same overall method/block structure.
- Old 1.7B 2048 context / 2–3 thread settings are gone from the active engine.
- Old 4B 3072 context / 3–4 thread settings are gone from the active engine.
- The supplied project still cannot be truthfully device-tested in this environment because the Android SDK/Gradle/actual GGUF files are not available here. A real Android build and the user's exact GGUF files are required for final runtime verification.
