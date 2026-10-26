# Device sport simulations

## On the emulator

An Android 16 (SDK 36) emulator, `scoreline_test`, lives in the standard SDK folder (`%LOCALAPPDATA%\Android\Sdk`). Start it with `ANDROID_SDK_ROOT` set to that folder, then `export ANDROID_SERIAL=emulator-5554` so `tools/ui.py` and `tools/fake_live.py` drive it instead of the phone. It shows stock Android's notification, not the phone's own island.

- `-e hold SOCCER` (any sport name; `OFF` clears) posts that sport's live notification and leaves it up for inspecting the shade.
- `python -I tools/fake_live.py` (emulator only, needs `adb root`) rewrites one cached game per followed league as live, so the app's live cards and match screens can be checked when nothing is on. `--undo` clears the cache.

`device/build.ps1` builds a separate instrumentation APK against the latest Scoreline build. It is not bundled into Scoreline and does not alter follows, pins or settings.

Run with the selected device serial:

```
adb -s SERIAL install -r PATH_TO_SportsTests.apk
adb -s SERIAL shell am instrument -w app.scoreline.tests/app.scoreline.live.SportsSimulation
```

Optional `-e preview true` temporarily stops the live tracking service and posts one clearly labelled synthetic notification per sport for 6.5 seconds. It captures only the top 220 pixels in `Android/data/app.scoreline/files/simulations/`. Remove those captures and uninstall `app.scoreline.tests` after inspection, then reopen Scoreline to restore its normal foreground service. Instrumentation restarts the target process, so do not run it during a match if uninterrupted real tracking is required.

Coverage includes:
- All seven sport categories in upcoming, live, finished and cancelled states.
- Compact and moment notification construction, shade-card separation and missing-logo handling.
- NBA/WNBA overtime, NHL/NFL regular-season versus postseason overtime, hockey shootouts.
- Baseball mid/end innings and football stoppage time/end-of-regulation breaks.
- Cricket invalid overs, missing innings and unknown batting IDs.
- F1 session identities and final versus live status; stale-data labels.

These are deterministic synthetic fixtures. They validate app calculations and Android rendering, not ESPN coverage, score timeliness, automatic kickoff reliability or real battery drain. Visual previews use a test glyph rather than downloading team logos.

Rules referenced for season-dependent overtime:
- https://operations.nfl.com/the-rules/nfl-rulebook
- https://media.nhl.com/site/asset/public/ext/2025-26/2025-26Rules.pdf

Latest run, 2026-10-26 on the emulator: 345 / 0 after the shared status words. Earlier, 2026-10-24: initial device suite 343 checks / 13 failures; after fixes and a compact-width regression case 344 / 0. Host TimelineTest and HttpTest also pass.
