# Status

_Last updated: Sat 26 Sep, ~16:05 IST. Kept current with every push._

## Done
- **ReAct agent loop** (`app/.../Assistant.kt`). Each step: capture screen → transformer →
  model returns `{screen, thought, tool, args, final}` → run tool → **wait until the UI has
  reacted and settled** (`ScreenReader.awaitSettled`) → observation → next step.
- **Tools**: 16 skills (intents/deep links), screen actions (tap/type/enter/scroll/back/home),
  `ask_user`, `finish`. The tool name is a schema enum and `id` is an integer, so the model
  cannot invent tools or ids.
- **Transformer** (`perception/.../ScreenTranslator.kt`): the active window plus dialogs, one
  element per actionable node, labels from children or overlay text. The service is declared an
  accessibility tool, so it can see "data-sensitive" views such as sign-in and payment buttons.
- Voice: on-device speech recognition (en-IN with en-US fallback), offline Indian-English TTS.
- Model: Gemma 4 E4B on GPU via LiteRT-LM (see below).
- SMS reader skill (Bansi).

## Debug tools
- `tools/install.sh`: build, install, grant permissions, re-enable accessibility (keeps HackTracker on).
- `tools/say.sh <command>`: run a command as if spoken.
- `tools/trace.sh <command>`: full trace (every prompt, model output, action, observation) → `traces/`.
- `tools/dump_screen.sh`: the translated screen as the model sees it. `--ez raw true`: the raw tree.

## Known issues / next
- Latency is 5–7 s per step, because the ~2.5k-character tool list is re-read every step. Next: reuse the cached prefix.
- The test phone's Play Store is **not signed in**; app installs need a Google account.
- Not yet tested on device: call, SMS send, Maps, camera, settings, volume, Spotify.

## Model decision: Gemma 4 E4B (GPU)
`gemma-4-E4B-it-gpu.litertlm`, 3 GB. Load takes 5 s; routing ~3 s and each navigation step ~3.8 s.
**8/8 correct** on the benchmark: skill choice with slots, direct answer, clarify, and
tapping the right chat. Gemma 4 12B (6 GB) did not finish a single benchmark in 10 minutes
and made the phone unresponsive over adb, so it's not usable for a step loop.

## Open questions
- Red Light schedule and which Office Kit actions HackTracker counts (ask at the teach-in).
- Is there a working NPU model for SM8850? (Teammate B, model research)

## Models on the test phone
`/sdcard/Download/models/` (outside the app, so uninstalling does not delete them). Push with
`adb push model.litertlm /sdcard/Download/models/`. The app needs All files access: `tools/install.sh` grants it.
