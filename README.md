# Scoreline

Scores, fixtures, results and standings, with team and competition hubs and optional live tracking in Android notifications and supported phones' islands.

Football, cricket, NBA/WNBA, NFL, NHL, MLB and Formula 1. Native Java and platform views, with no ads, no account and no analytics.

> **Built by Claude (Claude Code):** the app, the Gradle-less build, the emulator setup and this README. One round of fixes and the first device test suite came from a session in Codex.

## Why it exists

My phone has great hardware, but its stock software was weighing it down: preinstalled apps sending notifications non-stop, ads inside system apps, and background services waking the phone. Claude debloated it over adb, with no root and a restore script for everything it removed.

The debloat cost me one thing I cared about: live sports scores in the island. The phone's built-in sports cards only worked with one of the apps that had to go. So Claude worked out how the island picks up notifications and wrote this app to put the scores back.

## What it does

- **Scores without setup:** browse a sport and local calendar date, filter live matches, and open competition groups. Refresh keeps useful cached scores visible and labels delayed or partially unavailable data.
- **Following:** a personal live/today/upcoming/results feed, team and competition filters, and a separate list for managing follows.
- **Explore:** search teams and competitions, then open their hubs without following them first.
- **Competition and team hubs:** dated fixtures/results, grouped standings with horizontally scrollable provider columns, team lists and schedules where ESPN provides them.
- **Independent match centers:** open a match outside your followed feed, including from a notification after the app process restarts. See available statistics, period scores, venue, source links and sharing.
- **Browse-only tracking:** pin an upcoming or live match without following its team. A persisted match descriptor lets background checks resolve it later.
- **Appearance:** system, light and dark themes; onboarding can be skipped into browsing.
- **Sports spotlights:** an in-app event card and resizable launcher widget share sport-specific court/pitch/rink/flag artwork, prominent team crests and score typography. The launcher can cycle matches independently per widget instance; add it from Settings or the launcher's widget picker.
- **Event motion:** the app briefly animates fresh observed goals, scoring, wickets and race changes. Initial loading, stale data, corrections and returning to the screen stay quiet; disabled system animators are respected. Launcher widgets use static event-aware composition, not a continuous animation or live clock.
- **Native Material-style UI:** Android 12+ system palette, platform typography and controls, labeled icon navigation and 48dp primary touch targets. The app uses platform views rather than the Material 3 component library; it does not claim formal accessibility certification.
- **Island and Live Updates:** the match you're following sits in the island and the status-bar chip, showing the score and the clock.
- **Moments:** goals, touchdowns, wickets, kick-off and full time each get a 5-second expanded card with the team crest.
- **F1 like a timing screen:** the leader and lap x/y in the island, then the top five with their gaps and a lap bar below.
- **Up to three matches at once:** the first sits in the island and the second in the small side island, on islands that have one (elsewhere it's a second Live Update). A third stays as a quiet card in the shade. One tap swaps which match is the big one.
- **Match progress you can read:** one segment per half, quarter, period or inning, a marker for where play is now, and labelled goals.
- **Same wording across sports:** HT, FT and Final, End of Q3, Top 5th, ET, Pens, Shootout.
- **Score-by-period tables** for basketball, NFL, hockey and baseball.
- **Notifications that show the match:** team crests and a matchup image instead of an app logo.
- **Background tracking:** between matches it runs a check every 15 minutes, plus an alarm before kick-off. Foreground screens refresh relevant scores; live tracking polls while a selected match is on.

The launcher widget reuses those existing feed/background checks and live-tracking publications. It adds no fast polling service and does not require notification permission merely to display scores. When live tracking is inactive, use Refresh for an immediate request; Android controls background job delivery. Saved data remains available after process restart and is labeled delayed when old. Widget images load asynchronously through the existing image cache, with a bold monogram fallback when a crest is unavailable.

## How the island part works

Every phone gets a standard Android 16 `Notification.ProgressStyle` notification with a short critical text, which is what Live Updates are built from.

Some Android skins draw their own island from that same notification. Some also read a few extra keys for a richer layout: an icon and text on the left and a coloured capsule on the right. Scoreline sets those keys only for 5-second moments, because the skin drops the rich layout after five seconds. In between it shows the steady layout. Systems that don't know the keys ignore them. See [`IslandRenderer.java`](src/app/scoreline/live/IslandRenderer.java).

## Build

No Gradle. `build.sh` calls the SDK tools directly: aapt2, javac, d8, zipalign and apksigner.

You need:
- JDK 21 (Microsoft OpenJDK on Windows; run the script in Git Bash)
- Android build-tools 36 and platform 36, unzipped under `$SDK`

```bash
SDK=/path/to/sdk ./build.sh
```

On this Windows workspace, the restored SDK is in `.tools/sdk`; the script detects it when the old temporary SDK is absent. Paths containing spaces are supported. `OUT` selects the parent build directory, for example `OUT="$(pwd)/.tools/build"`.

The first build creates a local signing key, which is ignored by git.

Requirements: Android 12 or later (min SDK 31, target SDK 36). Live Updates and the island need Android 16; older versions get an ordinary live notification with a progress bar.

## Tests

- **Host tests:** Timeline, Status, Http and Browse fixtures cover sport progress/status, concurrent/offline HTTP caching, date/DST boundaries, malformed responses, grouped standings, independent identities and request-scoped freshness. Run `powershell -File tests/run.ps1` after a build; supply `-JsonJar` if the test-only `org.json` jar is elsewhere.
- **Device simulation:** the 2026-11-15 Android 16 emulator run passed 522 sport/notification checks, 15 browsing/pin integration checks and 128 match-screen checks across all seven sports. These are deterministic fixtures, not verification of live provider coverage.
- **Visual review:** the emulator-only `ScreenReview` runner captures the main journeys with real provider data, including dark and narrow/large-font layouts. Screenshots are local artifacts, not bundled into the APK.
- **Widget tests:** 86 host event-transition checks, 93 host snapshot/selection checks, 220 native launcher checks and 53 in-app event-motion checks pass. Matching day/night and narrow large-font captures use clearly labeled scenarios with real identity assets. These test presentation and lifecycle behavior, not live coverage or score timeliness.
- **Live data on the emulator:** `tools/fake_live.py` makes cached games look live, so you can check the screens when no match is on.

See [`tests/README.md`](tests/README.md).

## Data

Scores come from ESPN's public JSON endpoints. They are unofficial, can change, and may be delayed. Scoreline is not affiliated with ESPN.

Football browsing covers the catalog's competitions. Cricket date/team coverage is limited to series discoverable from ESPN's current ticker and supported series scoreboards; a complete global historical cricket schedule is unavailable. Cricket standings and F1 season standings/team lists are not supplied by these feeds; F1 still provides session schedules and running orders. Optional match statistics and recaps can be absent without preventing the scoreboard from working.

No schema migration is required: follow and match keys remain compatible, and theme/pin descriptors are additive preferences. Stock emulator checks do not verify a manufacturer's island layout, automatic kickoff delivery, battery drain or provider timeliness.
