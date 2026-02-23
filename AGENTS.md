# Instructions for AI Coding Agent: Kanuka Project (Current)

## Goal
Implement and maintain the current Kanuka app described in `SPECIFICATION.md`.

## Current Architecture (must keep)
- UI: Jetpack Compose single-activity app (`MainActivity`)
- State: `KanukaViewModel` + `KanukaUiState`
- Runtime bridge: `LiteRtLmRuntimeBridge`
- Engines (only two):
  - `Gemma 3n E4B + SpeechRecognizer`
  - `Gemma 3n E4B Direct Audio`

## Model Contract (strict)
- Model is selected as a single SAF file (`ACTION_OPEN_DOCUMENT`).
- Required filename: `gemma-3n-E4B-it-int4.litertlm`
- No model manifest (`kanuka-models.json`) and no directory-based model lookup.

## Interaction Model (must keep)
- Push-to-talk buttons for source/target at bottom.
- Translation starts on button release.
- Live card flow: `Recognizing -> Recognized -> Translating`.
- Cards support:
  - inline recognized-text editing
  - retranslate from edited text
  - swipe-to-delete with threshold feedback/haptics
  - bulk clear animation
- Concurrent requests are queued (FIFO) with placeholder cards.

## Runtime / Backend Rules
- LiteRT-LM only (`com.google.ai.edge.litertlm:litertlm-android`).
- Fixed backend plan: `GPU / vision=GPU / audio=CPU` (no CPU fallback path).
- Auto-preload is expected after app start, settings return, and engine change.

## Change Policy
- Assume reinstall baseline: removing legacy compatibility is acceptable.
- Keep docs (`SPECIFICATION.md`, `MODEL_SETUP.md`) in sync with code changes.
- Build the app and fix any build errors or warnings that occur.
- Keep OSS licenses information in sync with dependency changes.
