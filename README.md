# Scoreline

Live scores for the teams you follow, shown in your phone's island and in Android 16 Live Updates.

Football, cricket, NBA, NFL, NHL, MLB and Formula 1. The APK is about 150 KB, with no ads, no account and no analytics.

> **Built by Claude (Claude Code):** the app, the Gradle-less build, the emulator setup and this README. One round of fixes and the first device test suite came from a session in Codex.

## Why it exists

My phone has great hardware, but its stock software was weighing it down: preinstalled apps sending notifications non-stop, ads inside system apps, and background services waking the phone. Claude debloated it over adb, with no root and a restore script for everything it removed.

The debloat cost me one thing I cared about: live sports scores in the island. The phone's built-in sports cards only worked with one of the apps that had to go. So Claude worked out how the island picks up notifications and wrote this app to put the scores back.

## What it does

- **Island and Live Updates:** the match you're following sits in the island and the status-bar chip, showing the score and the clock.
- **Moments:** goals, touchdowns, wickets, kick-off and full time each get a 5-second expanded card with the team crest.
- **Up to three matches at once:** one sits in the island and the others stay as quiet cards in the shade. One tap swaps which match is in the island.
- **Match progress you can read:** one segment per half, quarter, period or inning, a marker for where play is now, and labelled goals.
- **Same wording across sports:** HT, FT and Final, End of Q3, Top 5th, ET, Pens, Shootout.
- **Score-by-period tables** for basketball, NFL, hockey and baseball.
- **Notifications that show the match:** team crests and a matchup image instead of an app logo.
- **Light on battery:** between matches it only runs a check every 15 minutes, plus an alarm just before kick-off. Fast polling runs only while a followed match is live.

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

The first build creates a local signing key, which is ignored by git.

Requirements: min SDK 31, target SDK 36. The island features need Android 16.

## Tests

- **Host tests:** `tests/TimelineTest.java`, `tests/StatusTest.java` and `tests/HttpTest.java`.
- **Device simulation:** `tests/device/` covers every sport in upcoming, live, finished and cancelled states. Last run: 345 checks, 0 failures on an Android 16 emulator.
- **Live data on the emulator:** `tools/fake_live.py` makes cached games look live, so you can check the screens when no match is on.

See [`tests/README.md`](tests/README.md).

## Data

Scores come from ESPN's public JSON endpoints. They are unofficial, they can change, and Scoreline is not affiliated with ESPN.
