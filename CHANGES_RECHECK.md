# Changes — Final Recheck

- Reworked local inference prompt construction for low-RAM phone use.
- Replaced the large MJ system contract with a compact equivalent contract.
- Removed unnecessary old-context injection for normal questions.
- Added cached long-term fact reads.
- The earlier 1.7B KV-context/thread reduction was found too aggressive on the target phone; 1.7B is now 3072 context with 3–4 threads.
- Added fast non-thinking planning path for 1.7B.
- Restored safe fast-path output ceilings (384–512 for simple turns) so factual answers are not prematurely truncated.
- Kept 4B for deep reasoning and restored a 4096 context with 3072–4096 output ceilings so <think> can finish.
- Restricted expensive retry behavior.
- Added approximate token/speed/retry diagnostics to model status.
- Kept wake-word out of scope as requested.
- Kept existing voice input, TTS sanitization, device actions, chat history, memory, model import/load/unload/delete and UI glass-thinking indicator intact.
