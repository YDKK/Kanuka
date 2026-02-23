# Kanuka Model Setup (Current)

Kanuka uses only one model format and one model variant.

## 1. Required Model File

Select this file in `Settings > Model File`:

- `gemma-3n-E4B-it-int4.litertlm`

The app validates filename and extension.

## 2. How to Configure

1. Open Settings.
2. Tap `Select` in the `Model File` card.
3. Pick `gemma-3n-E4B-it-int4.litertlm` from SAF.
4. Return to the main screen.

The app will auto-preload model/runtime when possible.

## 3. Runtime Notes

- Both translation engines use the same `.litertlm` file:
  - `Gemma 3n E4B + SpeechRecognizer`
  - `Gemma 3n E4B Direct Audio`
- Backend execution is LiteRT-LM on `GPU / vision=GPU / audio=CPU` (CPU fallback disabled).

## 4. Direct Audio Tuning

In `Settings`, direct-audio behavior can be tuned:

- `Silence detection sensitivity`
- `Skip quickly when no speech is heard` (prompt rule)

When the no-speech option is enabled, model prompt instructs early termination behavior if no intelligible speech is detected.

## 5. Context-Aware Translation

You can enable conversation-context translation on the main screen and configure context turn count in `Settings`.
