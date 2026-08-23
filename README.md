# MJ Assistant — Full Project

This is the complete MJ project, including the original project files and the latest UI/interaction fixes.

## Latest UI behavior
- Chat is text-first; MJ assistant replies do **not** use a visible Claude-style rectangular card.
- A small purple/pink dynamic MJ orb appears only beside the **current/latest assistant reply**.
- When a newer MJ reply arrives, the previous reply's orb disappears.
- The orb is intentionally small and continuously glows/rotates.
- Each MJ reply has a tiny `MJ` label and a small tappable speaker/listen icon.
- The top-right voice-output control is small and remains next to Settings.
- Voice output can be enabled/disabled; typed messages can produce both text and TTS.
- New assistant text enters from the side and has a short translucent glass/shimmer pass over the text area only.
- The glass pass stops after the response settles; there is no permanent moving card effect.
- User messages remain compact purple bubbles.
- The initial listening orb is smaller and no longer fills the screen.
- Chat has extra bottom scroll space so the newest message can move above the composer.
- Sending a message hides the keyboard and triggers the conversation list to reveal the newest item.
- Composer remains usable when the keyboard is open and supports multiple lines.
- Bottom navigation is hidden while the IME is open so it cannot be pushed under the keyboard.
- History, Actions and Profile are scrollable.
- Torch chat cards are tappable and have a switch for quick ON/OFF control.

## Conversation personality
The basic offline engine uses short, varied Hindi/Hinglish responses and avoids repeating the exact same greeting/fallback on every turn. The personality prompt is prepared for an Indian Muslim female assistant style: respectful, natural, concise, and conversational.

## Build configuration
- Android Gradle Plugin: 8.6.1
- Kotlin: 2.0.21
- Compose compiler plugin: 2.0.21
- Compose BOM: 2024.12.01
- Java/Kotlin JVM target: 17
- compileSdk: 35
- targetSdk: 35
- minSdk: 26

`build-termux.sh` uses the Gradle installation available in Termux. A Gradle wrapper binary is not invented/added because the original project did not contain one.

## Real modules currently present
- Basic local AI chat engine
- MJ personality foundation
- Android Text-to-Speech
- Torch hardware control with runtime camera permission
- Chat/history/actions/profile UI

## Not yet integrated
- Vosk speech-to-text runtime
- Speaker verification model
- Qwen `.task` inference runtime
- Always-on microphone foreground service
- Wake-word pipeline
- True interruption/barge-in audio pipeline

Those are kept separate so model/runtime integration does not destabilize the UI.
