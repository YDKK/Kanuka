# Kanuka App Specification (Current)

## 1. Scope
Kanuka is an offline Android speech translation app for Japanese/English/Chinese.

This document reflects the current implementation only.

- Reinstall is assumed.
- No backward compatibility or migration behavior is required.

## 2. Platform
- Android API 36 (minSdk/targetSdk/compileSdk = 36)
- Target device class: Snapdragon 8 Elite
- Fully offline runtime

## 3. Supported Translation Engines
Only these two modes are supported:

1. `Gemma 3n E4B + SpeechRecognizer`
2. `Gemma 3n E4B Direct Audio`

Both modes use the same LiteRT-LM model file.

## 4. Model Requirements
- Model is not bundled in APK.
- User selects a single file via SAF (`ACTION_OPEN_DOCUMENT`).
- Required filename:
  - `gemma-3n-E4B-it-int4.litertlm`

## 5. Main Screen UI
- Top bar:
  - `Clear all cards` button
  - `Settings` button
- Translation engine selector is on the main screen (dropdown from the engine label row).
- Engine label format:
  - `Engine: <mode label> / <backend label>`
- Status text is always shown.
- Main screen includes a `Use conversation context` switch.
- Model load progress card is shown only while model loading is active.
- History is card-based and scrollable.
- Swipe left/right on a card deletes it.
  - Deletion threshold hint is shown (`Swipe to delete` -> `Release to delete`).
  - Haptic feedback is triggered when threshold is reached.
- Bulk clear runs exit animation before final list clear.

## 6. Push-to-Talk Behavior
- Source/Target controls are two side-by-side push-to-talk buttons at the bottom.
- Each control combines:
  - Hold-to-talk button
  - Language dropdown
- Translation starts when the button is released.
- Source and target button presses flip translation direction based on selected languages.

## 7. Speech Capture and Translation Flow
### 7.1 SpeechRecognizer mode
- While pressed:
  - Partial/final recognition segments are accumulated into one transcript.
- On release:
  - Aggregated transcript is finalized and translated as one request.

### 7.2 Direct Audio mode
- While pressed:
  - Raw mic PCM is recorded and converted to WAV.
- On release:
  - Captured WAV is sent to Gemma 3n direct-audio inference.

### 7.3 Queueing
- If translation is running, new requests are queued.
- A placeholder card (`Queued...`) is inserted immediately at queue time.
- Queued jobs execute FIFO after the current translation finishes.

## 8. Cards and Editing
- One live card transitions through:
  - `Recognizing` -> `Recognized` -> `Translating`
- After completion, result is stored in history cards.
- Recognized text is always editable in both live/history cards.
- Retranslate is triggered by refresh icon next to recognized text.
- Retranslate on an existing card updates that same card/message (replace in place).
- Translation area has a speaker button to read translated text via Android TTS.

## 9. Cancellation and Removal
- Removing the actively translating card cancels inference.
- Clearing history cancels active inference and clears queued requests.
- Cancel path must not crash app.

## 10. Silent Input Handling
- Nearly silent captured audio is detected and skipped.
- Direct-audio and speech paths both avoid running inference for silent/no-meaning input.
- Direct-audio silence detection sensitivity is configurable in Settings.
- Optional prompt rule for direct audio can force immediate skip behavior when no intelligible speech is heard.

## 11. Runtime
- Inference library: `com.google.ai.edge.litertlm:litertlm-android`
- Engine backend plan:
  - `GPU / vision=GPU / audio=CPU`
- CPU fallback path is disabled.
- Runtime/backend label is shown in UI as simplified display (`GPU` or `Not initialized`).
- Model is auto-preloaded:
  - on app start
  - after returning from settings
  - after engine change (if model file is configured)

## 12. Settings Screen
- Runtime backend display
- Fixed model variant display: `Gemma 3n E4B`
- Model file select/clear controls
- Direct audio controls:
  - silence detection sensitivity slider
  - `skip quickly when no speech is heard` prompt option
- SpeechRecognizer language settings entry point:
  - opens a separate page
  - lists the fixed 12 languages:
    - Japanese / English / Chinese / Korean / Thai / Vietnamese / Filipino / German / Spanish / French / Italian / Hindi
  - allows selecting which languages appear in main dropdowns
- Conversation context turn count slider
- Links/buttons to open available system speech input and TTS settings pages
- OSS licenses page entry point
- About section:
  - `Kanuka Translator by YDKK`
  - version label (release build is derived from GitHub version tag)
  - GitHub link: `https://github.com/YDKK/Kanuka`

## 13. Conversation Context Translation
- When enabled, recent completed translation turns are appended to prompt context.
- Number of turns is configurable in Settings.
- Context is used for disambiguation only; output still follows strict translation format.

## 14. SpeechRecognizer Stability
- `ERROR_SERVER_DISCONNECTED (11)` is handled by recreating recognizer instance and retrying with backoff.

## 16. Direct Audio Silence Diagnostics
- Direct-audio silence detection emits debug logs including:
  - configured thresholds
  - measured audio stats
  - branch/reason of silence decision

## 15. OSS Licenses
- App provides a dedicated in-app OSS licenses screen listing currently used major dependencies and licenses.
