# Device sport simulations

## On the emulator

The isolated Android 16 (SDK 36) emulator `scoreline_polish` uses the restored `.tools/sdk` and `.tools/avd` directories. The original standard SDK directory is absent; an old AVD definition alone is not a runnable SDK. Set `ANDROID_SDK_ROOT`/`ANDROID_HOME` to `.tools/sdk`, `ANDROID_AVD_HOME` to `.tools/avd`, and `ANDROID_USER_HOME` to `.tools/android-user`. Launch on port 5556, then set `ANDROID_SERIAL=emulator-5556` so helper scripts target only that emulator. It shows stock Android notifications, not a manufacturer's island.

- `-e hold SOCCER` (any sport name; `OFF` clears) posts that sport's live notification and leaves it up for inspecting the shade.
- `python -I tools/fake_live.py` (emulator only, needs `adb root`) rewrites one cached game per followed league as live, so the app's live cards and match screens can be checked when nothing is on. `--undo` clears the cache.

`device/build.ps1` builds a separate instrumentation APK against the latest Scoreline build. It is not bundled into Scoreline and does not alter follows, pins or settings.

The build script accepts `-Sdk` and `-TargetBuild`; by default it detects `.tools/sdk` and the latest `.tools/build/run-*` output, falling back to the old temporary paths. Build the app first.

Run with the selected device serial:

```
adb -s SERIAL install -r PATH_TO_SportsTests.apk
adb -s SERIAL shell am instrument -w app.scoreline.tests/app.scoreline.live.SportsSimulation
adb -s SERIAL shell am instrument -w app.scoreline.tests/app.scoreline.live.ExperienceSimulation
adb -s SERIAL shell am instrument -w app.scoreline.tests/app.scoreline.live.MatchDetailSimulation
adb -s SERIAL shell am instrument -w app.scoreline.tests/app.scoreline.widgets.WidgetSimulation
adb -s SERIAL shell am instrument -w app.scoreline.tests/app.scoreline.ui.SpotlightSimulation
```

`ExperienceSimulation` uses isolated test preferences and clearly labeled temporary cached fixtures. It checks persistent follows/pin descriptors, zero-follow pin resolution through Feed and Tracker, hiding, and key-only match navigation outside both in-memory feeds. It removes its cache fixtures afterward.

`MatchDetailSimulation` opens all seven actual match screens with labeled source-shaped fixtures. Its 501 checks cover side identities, away-first statistics parsing, geometrically aligned team headers/value columns, six head-to-head missing-stat variants, distinct cricket innings scores and F1 driver order without invented head-to-head statistics. It captures each sport under `Android/data/app.scoreline/files/detail-tests/` and removes temporary cached fixture responses. This tests app rendering/parsing, not live data availability for every sport.

`WidgetSimulation` has 220 native checks for persisted snapshots, independent widget selections, source freshness/restart, hidden-island versus followed-score semantics, overdue fixtures, actual RemoteViews/AppWidgetHost rendering across all seven sports, day/night/compact/expanded variants, 1.5x font containment and cached crest fallback. Four real identity assets (India/Australia and Lakers/Celtics) loaded in the verified emulator run. Bitmap updates replace the full RemoteViews; receiver rendering does not load images synchronously.

`SpotlightSimulation` has 53 native checks across all seven event cards, including genuine Animator execution, quiet initial/repeated/stale bindings, pause/detach cancellation and the actual system animator-off preference. It restores that system preference after testing. Host `SportsEventTest` adds 86 conservative transition checks, including empty pre-match scores, explicit goal moments, cricket innings resets, F1 lap/leader changes, source ordering and correction recovery. `WidgetTest` adds 93 codec/selection/freshness checks.

`WidgetVisualReview` captures matching in-app and native launcher-host compositions with real team crests, light/dark themes and a narrow 1.35x-font case. The scores/order are explicitly labeled TEST scenarios, never published into the user's widget repository:

```
adb -s emulator-5556 shell am instrument -w app.scoreline.tests/app.scoreline.ui.WidgetVisualReview
adb -s emulator-5556 pull /sdcard/Android/data/app.scoreline/files/widget-pairs .tools/widget-review
```

For screenshots on the isolated emulator only:

```
adb -s emulator-5556 shell am instrument -w app.scoreline.tests/app.scoreline.live.ScreenReview
adb -s emulator-5556 pull /sdcard/Android/data/app.scoreline/files/review .tools
```

`ScreenReview` temporarily changes follows, theme, font scale and emulator viewport, then restores them. Do not run it on a personal device. It is a visual walkthrough, not an assertion that all provider data or every UI edge case is correct.

## Host regression tests

Run `powershell -File tests/run.ps1` after resource generation/build. The script uses the current source, generated `R.java`, JDK 21 and a real host `org.json` runtime (`.tools/json.jar` by default, or `-JsonJar PATH`). Android's stub `android.jar` is not a usable JSON runtime. No test jar is bundled into the app.

Browse fixtures cover local-midnight and DST date scopes, multi-day cricket boundaries, F1 session identity, malformed/missing fields, grouped/conference standings, stat-side alignment, bounded cache ordering, legitimate source corrections and concurrent per-request freshness. Http checks include retaining original fetch timestamps when reading cached bytes. Widget/event host tests require no Android runtime; they run against the same current source and host JSON jar.

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

Latest run, 2026-11-15 on the isolated Android 16 emulator: SportsSimulation 522 checks / 0 failures; ExperienceSimulation 15 / 0; MatchDetailSimulation 501 / 0; WidgetSimulation 234 / 0 (four of four real crest assets loaded); SpotlightSimulation 53 / 0. All host suites pass: TimelineTest (15), StatusTest, HttpTest, BrowseTest (126), SportsEventTest (86) and WidgetTest (93). ExperienceSimulation was also rerun twice earlier to verify fixture cleanup.

The Settings pin action was accepted through Pixel Launcher's real confirmation and placed a populated widget on its home screen. Next, Refresh and the correct match-detail tap were exercised there. Persisted snapshot and independent repository recreation are covered by the native fixtures; an ordinary OS process-kill test was inconclusive because the app process remained alive. These checks do not establish behavior on every third-party launcher. The signed APK passes apksigner verification and zip alignment; `Scoreline.zip` contains the current `Scoreline.apk` only, with its embedded bytes checked against the standalone APK.

The original 2026-10-26 suite had 345 / 0 after the shared status words. Earlier, 2026-10-24: initial suite 343 checks / 13 failures; after fixes and a compact-width regression case 344 / 0.

Review follow-up, 2026-11-15: pre-match comparison fixtures now cover soccer, basketball, NFL, hockey and baseball. They check the season heading, preserved source values, winner emphasis and bar direction. Host checks cover W/L streak order, last-ten records, decimal batting averages, unknown compound values and categorized baseball parsing with missing sides. Archived ESPN MLB summary 401809299 (2025-10-27) exposes nested batting/pitching/fielding statistics; selected metrics retain those category identities so batting strikeouts prefer fewer and pitching strikeouts prefer more. Current upcoming NBA/NFL/NHL responses were also inspected. No current pre-match MLB response was available, so its pre-match coverage is fixture-based. Device validation used only emulator-5556; no phone was connected or installed.

Visual-audit fixes, 2026-11-15: signed comparisons use directional labels; ordinary bars show actual amounts and bold indicates the better result, with lower-is-better and conversion-rate hints. Full-width labels and separate cricket innings/overs slots were rechecked in 42 scenarios (seven sports, live/pre, normal light/dark and 320dp-wide light at 1.35 font scale). All 42 were personally visually reviewed; native layout inspection reported no clipped text. MatchDetailSimulation adds laid-out text and actual chart/card contrast checks. WidgetSimulation verifies that the live dot does not auto-animate; its event transitions remain. Final APK: MatchDetail501, Sports522, Experience15, Widget234, Spotlight53: 1325 checks, zero failures. Host suites pass, including Browse126, Events86, Widgets93 and Timeline15. The final crash buffer is empty; package signature/alignment and APK-in-ZIP identity verify.

Battery review: the live service retains its 30s screen-on and 75s screen-off polling, failure backoff and existing stop rules. No battery-optimization settings were changed. F1 timing adds up to four driver-gap fetches per live cycle. A continuous 900ms launcher live-dot loop was removed; bounded event motion remains. No physical-device battery drain or guaranteed OS-kill recovery was established. The service is START_NOT_STICKY; scheduled jobs/alarms are later recovery opportunities, subject to Android/manufacturer restrictions. See artifacts/visual-audit-2026-11-15/Scoreline-visual-verification.html for the visual and battery review with embedded captures.
