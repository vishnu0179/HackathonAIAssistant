# Team plan

## What we're building

A voice-only assistant for Android. The user speaks, and the assistant replies and asks
follow-up questions out loud. It completes tasks inside *any* app by reading the
accessibility tree and acting on it. All inference runs on-device (Snapdragon 8 Elite
Gen 5 / SM8850, 12 GB). No cloud LLM.

## Architecture

```
 mic ──► :voice (STT) ──► Assistant (:app) ──► :llm Planner.route()
                               │                     │
                               │        ┌────────────┴─────────────┐
                               │   UseSkill(id,args)          Navigate(goal)
                               │        │                          │
                               │   :actions Skill.execute()   loop: :perception capture()
                               │   (deep links / intents)          → toPrompt() → Planner.nextStep()
                               │        │ fails? ──────────────►   → UiController.perform()
                               ▼
 speaker ◄── :voice (TTS) ◄── result / clarifying question / confirmation
```

**Why skills come first.** A small on-device model is bad at driving raw UI trees and
good at choosing from a short menu. So most requests become typed skill calls with
slots (for example `call_contact(name)` or `set_alarm(time)`), run through
intents and deep links. UI navigation is the fallback for everything else. It sees a
**translated** screen: a numbered list of meaningful elements, not the raw tree.

**Contracts** live in `:core` (`Screen.kt`, `UiAction.kt`, `Skill.kt`, `Voice.kt`, `Planner.kt`).
They are frozen. To change one, post in the team chat first, keep the change small, and
push it on its own.

## Ownership (one module each, so there are no merge conflicts)

| Who | Modules | Delivers |
|---|---|---|
| **Vishnu (+Claude)** | `:core`, `:app`, `:llm` | Orchestrator, LiteRT-LM on GPU/NPU, routing/step prompts, JSON parsing, model selection, dev console UI, demo script |
| **Teammate B** | `:perception` | Accessibility tree → `ScreenState` translation layer, `toPrompt()`, `UiController` (tap/type/scroll/back), `awaitIdle()` |
| **Teammate C** | `:voice`, `:actions` | Offline STT + TTS, barge-in, yes/no confirm; skill library (deep links/intents) |

### Teammate B: `:perception` (hand this section to your agent)

1. `AccessibilityScreenReader.capture()`: walk `AssistantAccessibilityService.instance.rootInActiveWindow`
   (and `windows` for dialogs/IME). Keep only nodes that are visible and that are either
   clickable/editable/scrollable/checkable or carry text or a content description. Collapse
   a clickable parent with its text-only children into ONE element whose label is the
   children's text joined together. Drop elements that are off-screen or have zero size.
   Assign ids 1..N in reading order (top to bottom, left to right). Keep a map from id to
   `AccessibilityNodeInfo` for the controller.
2. `toPrompt()`: one line per element, e.g. `[7] button "Send"`, `[3] input "Search" (focused)`,
   `[12] list (scrollable)`. Header line: app label and package. Target: < 1,500 chars
   for typical screens. Truncate long labels to 60 chars.
3. `AccessibilityUiController.perform()`: resolve id → node. Tap = `ACTION_CLICK` on the node or its
   nearest clickable ancestor, falling back to a `dispatchGesture` tap at the bounds center.
   Type = `ACTION_SET_TEXT`. Scroll = `ACTION_SCROLL_FORWARD/BACKWARD`, falling back to a swipe
   gesture. Back/Home/Notifications = `performGlobalAction`.
4. `awaitIdle()`: return once no content-changed events have arrived for 300 ms, or on timeout.
5. **Test without the LLM:** add a debug broadcast (or a unit test with fake nodes) that dumps
   `toPrompt()` for the current screen to logcat. Check it on WhatsApp, YouTube, Settings,
   Chrome, Maps and the Play Store. Commit sample dumps to `docs/screens/` so the prompt
   work can use them.
6. Stretch goal: per-app adapters (`AppAdapter` for WhatsApp/YouTube) that rename cryptic
   elements, e.g. `id/send` → "Send".

### Teammate C: `:voice` + `:actions` (hand this section to your agent)

**Voice** (`AndroidVoiceIO`):
1. `speak()`: `TextToSpeech`. Suspend until `onDone`. Prefer an offline voice (check
   `voice.isNetworkConnectionRequired`).
2. `listen()`: `SpeechRecognizer` on the main thread with
   `RecognizerIntent.EXTRA_PREFER_OFFLINE = true`. If
   `SpeechRecognizer.isOnDeviceRecognitionAvailable()`, use `createOnDeviceSpeechRecognizer`.
   Update `state` (LISTENING/SPEAKING/IDLE).
3. `confirm()`: ask, then match yes/yeah/sure/go ahead/haan and no/cancel/stop/nahi.
4. `stop()`: cancel both, for barge-in.
5. Earcons: short start/stop listening beeps (`ToneGenerator`).
6. Stretch goal: offline wake word ("Hey …") with sherpa-onnx KWS, and Whisper/sherpa-onnx STT
   if the built-in recognizer is weak on Indian English.

**Skills** (`:actions`; copy the `OpenAppSkill` pattern and register each one in `DefaultSkillRegistry`):
`call_contact`, `send_sms`, `send_whatsapp` (`https://wa.me/<num>?text=` or `ACTION_SEND` +
`setPackage("com.whatsapp")`; `Risk.CONFIRM`), `set_alarm` / `set_timer` (`AlarmClock`),
`navigate_to` (`google.navigation:q=`), `web_search`, `play_youtube` (search deep link),
`open_settings_panel` (wifi/bluetooth/volume `Settings.Panel`), `toggle_flashlight`
(`CameraManager.setTorchMode`), `take_photo` / `take_selfie`, `create_calendar_event`,
`read_notifications`, `battery_status`, `what_time`. Contact lookup (name → number) goes in
a shared helper with fuzzy matching. Every skill ships 3+ `examples`. Those examples are
also our routing eval set.

### Vishnu + Claude: `:llm` + `:app`
- `LiteRtLlm`: load `.litertlm` from `getExternalFilesDir("models")` (push it with adb). Try
  the NPU, then the GPU, then the CPU. Candidate models: Gemma 3n E2B/E4B and Qwen3 1.7B/4B.
- `LlmPlanner`: routing prompt = skill menu (id, description, slots, 1 example) + utterance
  → strict JSON. The step prompt uses the `toPrompt()` output. Use a tolerant JSON extractor
  and retry once on a parse failure.
- Fast path: a rule-based matcher for exact phrasings ("open X", "call X") skips the LLM.
- Dev console screen: live transcript, current `ScreenState`, the last prompt and response,
  and latency. Useful during Red Light and a strong visual for judges.

## Scoring: where the points are
- **End product 30%**: 8–10 flows that work reliably beat 50 flaky ones. Build an eval list early.
- **Novelty 20%**: skills-first plus a translated screen, and voice-only clarification
  and confirmation. Stretch goal: "teach mode" (the user demonstrates a task once and we
  record the accessibility events as a new skill).
- **Creative phone use 15%**: voice, on-device NPU, camera skill.
- **Technical depth 15%**: the clean module boundaries and the NPU are the talking points.
- **Office Kit 10%**: see Red Light below.
- **Demo 10%**: scripted 3–5 minute run on the phone, mirrored through Office Kit.

## Git rules
- Pull often: `git pull --rebase`. Commit small and push often. Only touch your own module.
- Commit message format: `<module>: <what>` (for example `perception: collapse clickable parents`).
- Don't commit model files (`*.litertlm`, `*.task`), APKs, or `local.properties`.
- Before you push: `./gradlew :app:assembleDebug` passes.

## Red Light (phone-only, via Office Kit)
Phase work so Red Light hours are for **testing and tuning on the phone**, not writing code:
- Before Red Light starts: push, build, and install the latest APK. Push the model file.
- During Red Light: run the eval list by voice across real apps. Log failures on the phone,
  in a shared note or the dev console. Use Office Kit screen mirror, remote control and
  file transfer to move logs, screen dumps and APKs. Record demo takes.
- Keep prompts and skill examples editable from the dev console so tuning can happen on
  the phone.
- Ask at the Saturday teach-in which Office Kit actions HackTracker counts, and plan
  around the answer.

## Testing on device
```
./gradlew :app:installDebug
adb shell settings put secure enabled_accessibility_services com.hackathon.assistant/com.hackathon.assistant.perception.AssistantAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell am broadcast -a com.hackathon.assistant.COMMAND -p com.hackathon.assistant --es text "open youtube"
adb logcat -s Assistant
```
Note: `adb install` clears the accessibility setting. Re-run the two `settings` lines after every install.
