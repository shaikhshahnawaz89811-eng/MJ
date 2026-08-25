# Changes Applied — Phase 1/2/5/6 Bug Fixes

This pass fixes every bug that `MJ_Assistant_Audit_Report.md` traced to an exact
file/class/function with a concrete recommended fix (§12, Bugs 1–8). Nothing else
was touched — no formatting changes, no refactors, no renamed symbols beyond what
each fix required. Brace/paren balance was re-verified after editing (still exactly
matched in all three edited files, same as the audit's own check in §13).

## Fixed

- **Bug 1 (HIGH) — stuck "Listening…" on mic-permission denial.**
  `MainActivity.kt`, `audioPermission` launcher callback now sets `listening = false`
  on denial, instead of relying solely on the speech-recognition result callback
  (which never runs if permission is denied).

- **Bug 2 (MEDIUM) — `readTurns()` re-parsed the whole cache on every call.**
  `ConversationMemory.kt`, the cached fast-path now returns the cached list
  directly instead of stringify+re-parsing every item on every read.

- **Bug 3 (MEDIUM-HIGH) — unterminated `<think>` block could leak into the visible answer.**
  `QwenModelEngine.kt`, `cleanThinkingOutput()` now detects an opening `<think>`/
  `<analysis>` tag with no matching closing tag (i.e. generation was cut off by
  `maxTokens` mid-reasoning) and returns blank, which routes through `reply()`'s
  existing "model ne empty jawab diya, dobara poochho" retry message instead of
  showing raw partial reasoning.

- **Bug 4 (LOW) — gendered Hindi possessive not covered in relationship capture.**
  `QwenModelEngine.kt`, `captureUsefulMemory()` now checks each relation word
  against all three possessive forms (mera/meri/mere) instead of only the fixed
  string `"meri $it"`.

- **Bug 5 (MEDIUM) — "yaad rakhna" facts could only accumulate, never update.**
  `QwenModelEngine.kt`, `captureUsefulMemory()` now derives a stable key from the
  fact's own content (first three non-numeric significant words) instead of a
  `System.currentTimeMillis()` timestamp, so restating/correcting a fact updates
  the existing entry via `ConversationMemory.remember()`'s dedup-by-key logic
  instead of creating a new, possibly-contradictory entry. This is a heuristic,
  not full NLP — it can still miss updates if the leading words of a restated
  fact change too.

- **Bug 6 (LOW-MEDIUM) — manual "🔊 Listen to MJ" button silently no-op'd when the
  global voice toggle was off.** `MainActivity.kt`, `speak()` gained a `force`
  parameter; the manual per-message tap now passes `force = true` so it plays
  regardless of the global toggle, while automatic reply narration still respects
  it (unchanged, `force` defaults to `false`).

- **Bug 7 (LOW) — TTS `language`/`setSpeechRate` were set before `onInit` confirmed
  success.** `MainActivity.kt`, these calls now run in a `LaunchedEffect(ttsReady)`
  gated on `status == TextToSpeech.SUCCESS`, not unconditionally at construction.

- **Bug 8 (LOW) — `closeRequested` was only consumed by `reply()`/`load()`.**
  `QwenModelEngine.kt`, `unload()`, `delete()`, and `importModel()` now consume
  `closeRequested` symmetrically on their way out, matching the pattern already
  used by `reply()`/`load()`.

## Deliberately NOT touched in this pass

Per §19 of the audit ("what is already good and must not be broken"), the
five-way `Mutex` serialization, the `busy`/`try-finally` guard around `reply()`,
`CancellationException` re-throwing, the device-command/AI separation, GGUF
magic-byte validation, the full-orb rotation, off-main-thread inference, and
task-aware token budgeting were all left exactly as they were.

The following are real, audit-identified gaps but are **architecture-level work**,
not bug fixes to an existing code path — each would add new files/screens/state
rather than change existing logic in place, so they were not attempted here:

- **Persisted chat/History (Phase 3)** — needs a real store (Room/SQLite or at
  minimum `rememberSaveable` + a repository) behind `MainActivity.kt`'s `messages`
  list, which today is `remember`-only and resets on process death.
- **Device-command registry (Phase 3)** — replacing the inline keyword `if/else`
  chain in `onSend` with a real registry, as a prerequisite for wiring up the six
  currently-dead `ActionCard` rows (Wi-Fi/Bluetooth/Mobile Data/Battery/Time/Date)
  and adding new commands without repeating the substring-matching pattern.
- **True streaming output / native tool-calling (Phase 4)** — the free
  `llama-android:0.1.1` API returns a complete `Llama.complete()` result; token
  streaming is documented as a Pro feature. The UI uses a client-side reveal
  animation instead of pretending it is native streaming.
- **Settings screen** — implemented and wired from the gear icon; it exposes voice,
  visible chat clearing, and long-term memory clearing.
- **`ConversationMemory.clearMemory()` wiring** — wired to the Settings screen's
  explicit "Clear MJ's long-term memory" confirmation.
- **Gradle Wrapper** — still not bundled; `build-termux.sh` still relies on
  Termux's system `gradle` matching AGP 8.6.1 / Kotlin 2.0.21's requirements.

## Still the #1 open risk, unchanged by this pass

**Whether `dev.ffmpegkit-maintained:llama-android:0.1.1` actually exists on Maven
Central with the exact API surface the code calls** (`Llama.complete`,
`Llama.loadModel`, `Llama.releaseModel`, `LlamaConfig(...)`) was NOT VERIFIED FROM
SOURCE in the audit and remains unverified here — this sandbox still has no
network access and no Android SDK, so `gradle :app:dependencies` could not be run.
Check `https://central.sonatype.com/artifact/dev.ffmpegkit-maintained/llama-android`
on a machine with network access before relying on anything downstream of it.

No `gradle`/Android build was run against this project in this pass either, for
the same reason. Brace/paren balance was checked manually as a lightweight sanity
check; it is not a substitute for a real compile.
