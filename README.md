# MJ Assistant — Qwen3 4B + 1.7B

Private on-device Android assistant using GGUF models through llama-android.

## Models
- **Qwen3 1.7B Fast** — default everyday model. Uses non-thinking mode for normal chat, facts, short coding and planning; thinking is reserved for genuinely deeper requests.
- **Qwen2.5 3B** — third, extra-fast slot (`Qwen25ModelEngine`). Import a `qwen2.5-3b-instruct` GGUF (Q4_K_M recommended) from Profile → this model card, same as the other two. Tuned like the 1.7B slot (3072 context, 3–6 threads) for phone/Termux speed. Unlike the Qwen3 slots it never appends "/think" or "/no_think" to the prompt — Qwen2.5-Instruct has no thinking mode and doesn't understand that control syntax.
- **Qwen3 4B Thinking** — manual deep/reasoning slot.
- Only one native GGUF model is loaded at a time (enforced by `ModelRuntimeCoordinator`, whichever of the three slots is active).

## Phone-performance design
- Compact system prompt.
- Conversation history is injected only for follow-up-like requests.
- Long-term facts are cached instead of reparsed on every turn.
- 1.7B uses a 3072-token native context and 3–4 CPU threads.
- 4B uses a 4096-token native context and 4–6 CPU threads.
- Generation budgets are bounded but leave enough room for Qwen3 reasoning; every request is fitted to the remaining context window.
- Expensive retry generation is restricted and bounded.
- Profile model status shows approximate input/output token counts, speed, mode and retry count.

## Voice
Voice input is explicit Android Speech Recognizer from the mic button. No wake-word listener is included in this build.

## Build
Use `build-termux.sh` inside a Termux environment with Gradle and native AAPT2 installed.

See `PERFORMANCE_AUDIT_RECHECK.md`, `QA_RECHECK.md` and `CHANGES_RECHECK.md` for the latest audit.
