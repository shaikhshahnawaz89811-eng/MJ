# Empty-Reply Fix (post Phase 2–7)

## What was actually broken

Every AI-model reply — including a plain "hello" — came back as "Hmm, model
ne empty jawab diya. Dobara poochho ji." Device commands (time/date/torch)
worked fine, because they never touch the model.

## Root cause

`Qwen3-4B-Thinking-2507` is a "thinking" variant: it always writes a hidden
`<think>...</think>` reasoning block before any visible answer, on every
message, unconditionally. That's a property of the model, not a prompt
choice — there's no "skip thinking" switch for this variant.

The `PERFORMANCE_FIX_REPORT.md` pass (correctly) identified that sending a
flat `maxTokens = 1536` for every message, including "hello", was wasteful
and slow — and fixed that by scaling the budget down for simple messages
(as low as **256** tokens for a greeting, 640 for a short question). That
part of the reasoning was right, but the numbers didn't leave room for the
model's mandatory thinking pass, so generation was cut off mid-`<think>` on
nearly every turn. `QwenModelEngine.cleanThinkingOutput()` correctly detects
an unterminated `<think>`/`<analysis>` tag and refuses to leak raw reasoning
into the chat — but the practical effect was that almost every reply
resolved to the "empty jawab" fallback, and re-asking with the same budget
hit the identical wall again.

## Fix

In `QwenModelEngine.kt`:

- **`generationBudget()`**: raised all tiers (simple 256→1024, coding
  768→2048, short 640→1536, medium 1024→2048, long 1536→2560) so a normal
  thinking pass + answer fits on the first attempt for most messages.
- **`reply()`**: added a one-time automatic retry with a doubled token
  budget (capped by `MAX_RETRY_TOKENS = 4096`) if the first attempt still
  comes back with an unterminated think block. This replaces a guaranteed
  repeat failure with a real second chance before showing the user any
  "try again" message.
- **`load()`**: keeps `contextSize = 4096` for the phone build. Prompt history is
  already bounded, so a larger KV cache would add RAM pressure without fixing the
  actual reply path.

## Trade-off, stated plainly

The phone build deliberately keeps `contextSize = 4096` to avoid unnecessary KV-cache
RAM usage. If a lower-RAM device still struggles, the next knobs to reduce are the
generation budgets/retry ceiling, not a larger context.

## Not verified by compiling

Same standing limitation as every prior phase in this repo: this was
written and checked by hand (brace/paren balance confirmed) in a sandbox
with no Android SDK, no Gradle, and no device — build and test on a real
Android toolchain before relying on it. If replies are still empty after
this fix on your device, the most useful next data point is whatever
tokens/sec and token-count the status line under the chat shows after a
failed reply (it now reports `maxTokens` used) — that tells us whether it's
still running out of budget or failing for a different reason (e.g. a
corrupt/incompatible GGUF, or the AAR's `Llama.complete()` throwing).
