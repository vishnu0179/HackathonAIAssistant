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

## In progress (Vishnu + Claude)
- [ ] Benchmark Gemma 4 E4B (downloaded) vs 12B (downloading) on the phone
- [ ] `:perception`: accessibility tree → translated screen, UI controller
- [ ] `:voice`: offline STT + TTS
- [ ] `:actions`: call, WhatsApp, SMS, alarm, timer, maps, YouTube, flashlight, search

## Open questions
- Red Light schedule and which Office Kit actions HackTracker counts (ask at the teach-in).
- Is there a working NPU model for SM8850? (Teammate B, model research)

## Models on the test phone
`/sdcard/Download/models/` (outside the app, so uninstalling does not delete them). Push with
`adb push model.litertlm /sdcard/Download/models/`. The app needs All files access: `tools/install.sh` grants it.
