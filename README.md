# AI Phone Assistant - System Application

A privileged system application for LineageOS/AOSP (built and tested against
LineageOS 22.2 / Android 15) that watches the screen, asks a vision-capable
LLM what to do next, and injects touch/key input — like a minimal,
self-hosted "AI phone agent".

**It is task-driven and off by default.** The assistant only runs when you
enable it in Settings *and* set a task; otherwise nothing is captured and no
network call is made.

## How it works

```
┌──────────────────────────────────────────────────────────────┐
│                    AIPhoneAssistantService                    │
│                                                              │
│  capture loop (background thread, every N ms)                │
│      │                                                       │
│      ▼                                                       │
│  ScreenCaptureService ──► WindowManager.captureDisplay()     │
│      │                (fallback: mirror VirtualDisplay)      │
│      ▼                                                       │
│  FrameChangeDetector ── only escalate on settled,            │
│      │                    meaningful screen changes          │
│      ▼                                                       │
│  LLMClientService ──► OpenAI-compatible vision API           │
│      │                 (screenshot + user task)              │
│      ▼                                                       │
│  InputInjectionService ──► InputManager.injectInputEvent()   │
│                                                              │
│  OverlayController shows "thinking"/"acting" effects         │
└──────────────────────────────────────────────────────────────┘
```

- **Capture**: uses the platform capture API (`IWindowManager.captureDisplay`,
  requires `READ_FRAME_BUFFER`), with a mirror `VirtualDisplay` +
  `ImageReader` fallback (requires `CAPTURE_VIDEO_OUTPUT`). Works on Android
  13–15; the old `SurfaceControl.screenshot()` reflection path was removed
  from the framework in Android 12 and never worked here.
- **Gating**: a frame is only sent to the LLM when the screen settled after a
  transition and changed meaningfully vs. the last analyzed frame, with rate
  limiting and a post-action cooldown to avoid feedback loops.
- **Actions**: `tap`, `swipe`, `long_press`, `scroll`, `key_press`, `text_input`,
  `wait`, `none`. The model sees a downscaled screenshot; returned coordinates
  are rescaled to real screen pixels before injection.
- **Task**: configured in Settings. Included in every prompt; the model is
  explicitly allowed (and asked) to answer `none` when no action helps or the
  task is done.

## Setup (first run)

1. Flash a build containing the app (see Build below), then open
   **"AI Assistant Settings"** from the launcher.
2. Set **LLM Endpoint** — any OpenAI-compatible `/v1/chat/completions` URL:
   - OpenAI: `https://api.openai.com/v1/chat/completions` (model `gpt-4o`)
   - Zhipu: `https://open.bigmodel.cn/api/paas/v4/chat/completions` (model `glm-4v-plus`)
   - Local: `http://192.168.1.100:11434/v1/chat/completions` (Ollama, vLLM, ...)
3. Set **Model** and **API Key** (leave the key empty for local servers).
4. Tap **Test Connection** — it sends a text-only ping and toasts the result.
5. Set a **Task**, e.g. *“打开设置并调高亮度”* or *"open the browser and search
   for the weather"*.
6. Flip **Enable AI Assistant**. A foreground-service notification appears;
   the assistant now acts whenever the screen changes meaningfully.

Everything is stored in the app's `SharedPreferences`
(`/data/user/0/com.android.aiphoneassistant/shared_prefs/ai_phone_assistant.xml`
when running as system uid) and is re-read on every request, so you can change
the task or endpoint while it runs. Clearing the task makes the assistant idle.

## LLM response format

The model must answer with a single JSON object:

```json
{"action": "tap", "x": 540, "y": 1200}
{"action": "swipe", "x": 540, "y": 1600, "endX": 540, "endY": 400, "durationMs": 300}
{"action": "long_press", "x": 540, "y": 1200, "durationMs": 800}
{"action": "scroll", "x": 540, "y": 1200, "deltaY": -400}
{"action": "key_press", "key": "BACK"}
{"action": "text_input", "text": "hello world"}
{"action": "wait", "durationMs": 1000}
{"action": "none"}
```

- Coordinates are in the pixel space of the image the model received (stated
  in the prompt) and are rescaled to screen coordinates automatically.
- Key names: `BACK`, `HOME`, `APP_SWITCH`, `ENTER`, `DEL`, `SPACE`, `TAB`,
  `DPAD_UP/DOWN/LEFT/RIGHT`, `VOLUME_UP/DOWN/MUTE`, `POWER`, ...
- Malformed answers or actions with missing coordinates are ignored — the
  app never guesses a default tap position.

## Required permissions (signature-level)

| Permission | Purpose |
|------------|---------|
| `android.permission.READ_FRAME_BUFFER` | `WindowManager.captureDisplay()` |
| `android.permission.CAPTURE_VIDEO_OUTPUT` | mirror `VirtualDisplay` fallback |
| `android.permission.CAPTURE_SECURE_VIDEO_OUTPUT` | not used by default (secure layers stay black) |
| `android.permission.INJECT_EVENTS` | `InputManager.injectInputEvent()` |

Granted via `privapp-permissions-aiphoneassistant.xml`; the app must be
platform-signed (`certificate: "platform"` in Android.bp) and installed in
`/system/system_ext/priv-app/`.

## Build

One-click for lmi (POCO F2 Pro), syncs source + SELinux into the tree:

```bash
sudo bash /home/inoki/aiphone_assistant/build_lmi.sh

cd /home/inoki/Builds/LineageOS/lineage-22_2-poco-f2
source build/envsetup.sh
lunch lineage_lmi-userdebug
m AIPhoneAssistant -j$(nproc)
```

Quick deploy of just the APK to a rooted test device (needs `adb root`):

```bash
bash /home/inoki/aiphone_assistant/push.sh
```

SELinux rules (`sepolicy/private|vendor/aiphoneassistant.te`) are compiled
into the image by `build_lmi.sh`; they cannot be pushed at runtime, so after
changing them do a full image build + flash.

## Known limitations

- **text_input types keyboard events**: it works for ASCII/Latin text where a
  text field is focused. Chinese/emoji input would need an IME-level
  integration and is not supported.
- **Secure (FLAG_SECURE) screens capture as black** by design — banking apps,
  DRM video etc. appear black to the model. To include them, set
  `setCaptureSecureLayers(true)` in `ScreenCaptureService.captureFrame()`
  (the app already holds `CAPTURE_SECURE_VIDEO_OUTPUT`), but be aware the
  content is then sent to your LLM endpoint.
- **One action per analysis round**; complex flows take several
  capture→analyze→act cycles (typically a few seconds each).
- The overlay ("thinking" tint + "acting" ripple) needs
  `SYSTEM_ALERT_WINDOW`, which preinstalled apps get automatically.

## Security considerations

⚠️ **This app has full device control**: it can read everything on screen and
inject any input, as the system uid. Treat it as a powerful automation agent:

- Run it only on a device you control, ideally a test device.
- Prefer a **LAN or self-hosted LLM endpoint** — every analyzed screenshot is
  sent to the configured endpoint (JPEG, ≤1024px).
- The API key sits in plain-text SharedPreferences (system-uid readable).
- It is disabled by default and only starts on boot if you enabled it.

## Troubleshooting

```bash
# Follow what the assistant is doing
adb logcat -s AIPhoneAssistant AIPhoneAssistant.ScreenCap AIPhoneAssistant.Input \
               AIPhoneAssistant.LLM AIPhoneAssistant.Boot

# Check the signature/priv permissions actually got granted
adb shell dumpsys package com.android.aiphoneassistant | grep -E "READ_FRAME_BUFFER|INJECT_EVENTS|CAPTURE_VIDEO"

# SELinux denials (capture/input silently failing on enforcing builds)
adb shell dmesg | grep avc | grep system_app
# then generate rules with audit2allow as described in SEPOLICY.md
```

- **Captures return null** → check `READ_FRAME_BUFFER` grant and SELinux.
- **No LLM calls** → endpoint or task not set (the loop idles until both are
  configured); check for `LLM analysis error` in logcat.
- **Taps land in the wrong place** → make sure your model returns coordinates
  in image pixel space; if a model ignores the stated image size, try a
  stronger vision model.

## License

Copyright (C) 2024 The LineageOS Project

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. The full license text is in the
[LICENSE](LICENSE) file.
