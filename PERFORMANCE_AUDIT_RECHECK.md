# MJ Performance Audit — Recheck

## Scope
- Qwen3 1.7B Fast
- Qwen3 4B Thinking
- prompt/context construction
- generation budgets
- retry behavior
- native model residency
- memory persistence overhead
- device-command fast path
- wake-word scope

## Changes in this recheck
1. Compact MJ system prompt to reduce input/prefill work on every inference.
2. Conversation context is now opt-in for follow-up-like requests instead of being attached to ordinary facts/coding/chat.
3. Conversation facts are cached; repeated JSON parsing of the full facts list is avoided.
4. 1.7B context is kept at 3072: the earlier 2048 reduction was too aggressive for factual/coding turns.
5. 1.7B CPU threads use 3–4 on capable phones.
6. 1.7B uses `/no_think` by default. Planning requests also stay in fast non-thinking mode.
7. 1.7B generation budgets were restored to practical ceilings: short chat/facts 384–512, short code 768, planning 1024, deeper work 1536–2048.
8. 4B remains the deep model and is not loaded simultaneously with 1.7B.
9. 4B uses a 4096-token context and practical 3072–4096 generation ceilings so the thinking model is not cut off before its visible answer.
10. Automatic second generation is restricted: 1.7B only retries its fast non-thinking tier; 4B keeps only the existing bounded correction path for small generations.
11. Profile model status now reports approximate input/output token counts, generation speed, mode and retry count. These are estimates because llama-android 0.1.1 exposes speed but not exact token counters.
12. No wake-word feature was added or changed. Voice remains Android Speech Recognizer on explicit mic action.

## Native memory rule
`ModelRuntimeCoordinator` allows only one native GGUF owner at a time. Loading one model while the other is loaded is rejected.

## Token flow
- System prompt + user/context prompt are input/prefill tokens.
- Qwen3 1.7B normal path uses `/no_think`, so no deliberate hidden reasoning pass is requested.
- 1.7B deep path may use `/think` unless the request is a planning request; planning stays non-thinking for speed.
- 4B is the thinking/deep slot.
- A retry is a second complete generation and therefore can roughly double generation work for that request; the retry is deliberately bounded and restricted.
- The UI estimate is derived from generated text length (~4 characters/token), not an exact tokenizer count.
