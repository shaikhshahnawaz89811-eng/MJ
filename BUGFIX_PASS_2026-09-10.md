# Bug-fix pass — 2026-09-10

Triggered by a screen recording + a Hinglish bug report describing: no visible
option to add a web-search API key, Settings not scrolling, Settings looking
worse than Profile, Profile missing options that Settings has, picking any
photo auto-sending it through the scan/PDF pipeline with no choice, and the
"MJ soch rahi hai…" indicator never showing what MJ was actually doing.

Root-caused each complaint against the actual source (not just the video)
before changing anything. Two of the nine fixes below were found only by
reading the code — they weren't named in the report, but they're the same
family of bug as what was reported.

## Fixed

1. **Settings dialog had no scroll.** The Tavily search-key field was already
   in the code (Web Search section) — it was just rendered off the bottom of
   an unscrollable `Dialog`/`Column` on any normal phone screen, with no way
   to reach it. Added `.verticalScroll()` + a `heightIn(max = screenHeight *
   0.86f)` bound on the dialog's `Surface` (a `Dialog` wraps content height by
   default, so scroll alone does nothing without a max height).
2. **Settings redesigned to match Profile's look.** Extracted
   `VoiceSettingCard` / `DataSettingCard` / `BrainSettingCard` /
   `SearchSettingCard` as shared composables in Profile's own card style
   (rounded `Surface`, icon header) instead of a flat `Column` of dividers.
3. **Same cards added to the Profile tab.** Data/Brain/Search are now
   reachable from both the gear-icon dialog and Profile — same callbacks,
   same underlying state, two entry points.
4. **Photo picker always auto-ran OCR → PDF → send, no choice, no preview.**
   Added a plain "Gallery se photo bhejo" path: picking a photo now stages it
   (`pendingPlainImage`) and shows a preview strip above the composer;
   nothing sends until the user presses ▲. The existing scan paths (camera /
   gallery-scan) are unchanged. The plain-attach reply is honest that MJ can
   save the photo but has no vision model to describe it.
5. **"MJ soch rahi hai…" was a hardcoded constant.** `ThinkingRow` now takes
   a label, set per branch: OCR ("Photo padh rahi hai…"), web search, sketch
   generation, PDF export, plain-photo save — falling back to the original
   generic line for a normal chat reply.
6. **Torch card could show OFF right under "Ji, torch on kar di."** The card
   was reading the live `torchOn` variable, which every Torch card in the
   whole scrollback shared — not the specific outcome of the message it was
   attached to. It now renders `msg.action.enabled`, and tapping it updates
   that message's own state.
7. **Found while reading, not in the report — echo bug.** `isEchoingUser()`
   in all three engines (Qwen/1.7B/2.5) skipped its own check entirely for
   any input under 10 characters, so a one-word command like "karo" got a
   byte-for-byte echo of itself waved through as a valid answer. Added an
   exact-match check that runs regardless of length.
8. **Found while reading — a bad retry could still reach the user.** All
   three engines re-generate once when the first answer looks bad, but never
   re-checked the retry's own output — a retry that echoed just as badly (the
   likely outcome for something as bare as "karo", which gives the model
   nothing new to say) still got shown verbatim. Now re-validated; if still
   bad, falls through to the existing honest "couldn't answer" message.
9. **Found while reading — onSend's try block had no catch at all.** Any
   unexpected exception (this is almost certainly what happened to "car ka
   sketch banao" in the recording, which got no reply at all) skipped
   straight to `finally`, clearing the thinking indicator with nothing ever
   added to chat. Added a catch with an honest fallback message. Same gap
   existed in `runScan`; fixed there too.

## Also, smaller

- OCR replies sometimes started with a bare "Reply" line — the small model
  prefacing its cleaned-up answer with a label despite the prompt already
  asking it not to. Tightened the prompt and added `stripLeakedPreamble()` as
  a defensive strip.

## Touched

`MainActivity.kt`, `ai/ChatHistoryStore.kt` (new `userImagePath` field on
persisted messages), `ai/QwenModelEngine.kt`, `ai/Qwen17BModelEngine.kt`,
`ai/Qwen25ModelEngine.kt`, `scan/DocumentScanner.kt`. No existing feature
removed; no new Gradle dependency added.

## Not done in this pass

- The Local AI Brain / Web Search features still require the user's own
  Groq/Tavily-style API key — no key was provided, so nothing about that
  changed.
- Model-generation quality in Hindi/Hinglish (beyond the two specific bugs
  above) is a small-on-device-model limitation, not something a code fix can
  resolve.
