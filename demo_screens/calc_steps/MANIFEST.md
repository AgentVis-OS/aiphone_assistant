# Calculator Demo — Per-Step Conversation Captures

- Conversation: "open the calculator app and calculate 123 plus 456" (state: done)
- Device time = the clock shown in the video's status bar (PDT); video time = the
  matching timestamp in `demo_calculator.mp4` (video zero ≈ device 10:21:25, ±2s)
- Each capture is what the model saw **before** executing the action (its decision
  input); the pill at the top center is the interrupt bar

| # | Device time | Video | Action | Capture |
|---|-------------|-------|--------|---------|
| 0 | 10:22:02 | 0:37 | Task sent (user message + task start) | — |
| 1 | 10:22:12 | 0:47 | Tap (885,1378) (stray tap on the list page, opened Contacts) | step01_102212_v0m47_tap_885_1378.jpg |
| 2 | 10:22:20 | 0:55 | Key HOME | step02_102220_v0m55_key_HOME.jpg |
| 3 | 10:22:26 | 1:01 | Tap (538,2198) (opened Contacts) | step03_102226_v1m01_tap_538_2198.jpg |
| 4–11 | 10:22:33–10:24:52 | 1:08–3:27 | Repeated attempts on the permission dialog (never hits a button) | step04–step11 |
| 12 | 10:25:02 | 3:37 | Key HOME (self-recovery) | step12_102502_v3m37_key_HOME.jpg |
| 13 | 10:25:09 | 3:44 | Swipe up to open the app drawer | step13_102509_v3m44_swipe_538_2226_→_538_703.jpg |
| 14 | 10:25:16 | 3:51 | Tap the Calculator icon in the drawer | step14_102516_v3m51_tap_110_745.jpg |
| 15 | 10:25:22 | 3:57 | Enter **1** | step15_102522_v3m57_tap_149_1985.jpg |
| 16 | 10:25:42 | 4:17 | Enter **2** | step16_102542_v4m17_tap_409_1985.jpg |
| 17 | 10:26:02 | 4:37 | Enter **3** → shows 123 | step17_102602_v4m37_tap_670_1987.jpg |
| 18 | 10:26:24 | 4:59 | Enter **+** | step18_102624_v4m59_tap_925_1982.jpg |
| 19 | 10:26:41 | 5:16 | Enter **4** | step19_102641_v5m16_tap_152_1767.jpg |
| 20 | 10:27:09 | 5:44 | Enter **5** | step20_102709_v5m44_tap_409_1767.jpg |
| 21 | 10:27:22 | 5:57 | Enter **6** → live preview shows 579 | step21_102722_v5m57_tap_662_1767.jpg |
| 22 | 10:27:43 | 6:18 | Task done: 123 + 456 = 579, service auto-deactivates | step22_102743_v6m18_done…jpg |

Suggested cut points: 0 / 0:37 / 1:08 / **3:37** (recovery) / 3:44 / 3:51 /
**4:37** (123) / 4:59 (+) / **6:18** (579 + done).
Note: the model never presses "=" — the AOSP calculator's live preview line shows
579 as soon as the expression is complete, and the model concludes from that.
