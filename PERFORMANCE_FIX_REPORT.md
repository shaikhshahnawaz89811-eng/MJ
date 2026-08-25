# MJ Assistant — Qwen3 Reply-Latency / Stability Fix

## Problems found in the supplied build

1. Every chat turn waited an artificial 520 ms before inference.
2. Every LLM request used `maxTokens = 1536`, including tiny messages such as `hello`.
3. Every request injected up to 10 recent turns and 12 facts, creating unnecessarily large phone-side prompts.
4. Native inference was not explicitly moved off the Compose/Main coroutine dispatcher.
5. Recent conversation was synchronously rewritten to SharedPreferences after every reply.
6. Activity disposal could call native model release while a generation was still using the model.
7. Cancellation could be swallowed by broad `Throwable` catches.
8. Model CPU threads were capped at 4 even on devices with more cores.
9. The send coroutine did not have a `finally` around its UI `replying` state, so an unexpected exception could leave the composer stuck.

## Fixes

- Removed the artificial 520 ms delay.
- Added task-aware generation budgets: 256 for simple chat, 640–768 for short questions/coding, 1024 for medium tasks, 1536 for large tasks.
- Reduced normal prompt context to a bounded size and only adds older relevant turns for non-trivial requests.
- Explicitly runs `Llama.complete()` on `Dispatchers.Default`.
- Added an in-memory recent-turn cache and asynchronous persistence for short-term conversation history.
- Kept explicit long-term memory writes synchronous.
- Added lifecycle-safe deferred native release when Activity/Compose is disposed during generation.
- Re-throws `CancellationException` instead of converting cancellation into a fake AI error.
- Increased CPU thread selection to 4–6 based on available processors.
- Added `try/finally` around the chat send coroutine so `replying` always resets.

## Verification performed here

- ZIP extraction/integrity: PASS (`unzip -t`).
- Kotlin parser/syntax check: no syntax-parser errors detected. Android/Compose dependencies are unavailable in this isolated compiler environment, so unresolved Android/Compose references are expected there.
- Checked that the old 520 ms delay is gone.
- Checked that the old fixed 1536-token setting is gone from the reply path.
- Checked explicit `Dispatchers.Default` around native completion.
- Checked asynchronous recent-turn persistence.
- Checked cancellation guards and lifecycle release logic.

## Runtime limitation

A real Qwen3 GGUF model and an Android device are required to measure actual first-token latency/tokens-per-second. This environment does not have the user's Android runtime/model, so this package does not claim a phone-side performance benchmark.
