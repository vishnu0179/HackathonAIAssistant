# Status

_Last updated: Sat 26 Sep, ~13:15 IST. Kept current with every push._

## Done
- Six modules with `:core` interfaces; the app builds, installs and launches on the iQOO (SM8850).
- Orchestrator (`app/.../Assistant.kt`): route to a skill → ask for missing slots by voice →
  confirm risky skills → run. Falls back to step-by-step screen navigation.
- LiteRT-LM runtime (`llm/.../LiteRtLlm.kt`): GPU backend, greedy sampling, thinking off,
  **schema-constrained JSON output**.
- Planner prompts (`llm/.../Prompts.kt`) for routing and navigation steps.
- adb hooks: `--es text "<command>"` runs a command, `--es bench <model>` benchmarks a model.
- Skill: `open_app`.

## Model decision: Gemma 4 E4B (GPU)
`gemma-4-E4B-it-gpu.litertlm`, 3 GB. Load takes 5 s; routing ~3 s and each navigation step ~3.8 s.
**8/8 correct** on the benchmark: skill choice with slots, direct answer, clarify, and
tapping the right chat. Gemma 4 12B (6 GB) did not finish a single benchmark in 10 minutes
and made the phone unresponsive over adb, so it's not usable for a step loop.

## In progress (Vishnu + Claude)
- [ ] `:perception`: accessibility tree → translated screen, UI controller
- [ ] `:voice`: offline STT + TTS
- [ ] `:actions`: call, WhatsApp, SMS, alarm, timer, maps, YouTube, flashlight, search

## Open questions
- Red Light schedule and which Office Kit actions HackTracker counts (ask at the teach-in).
- Is there a working NPU model for SM8850? (Teammate B, model research)

## Models on the test phone
`/sdcard/Download/models/` (outside the app, so uninstalling does not delete them). Push with
`adb push model.litertlm /sdcard/Download/models/`. The app needs All files access: `tools/install.sh` grants it.
