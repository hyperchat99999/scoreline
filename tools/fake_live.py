"""Emulator-only: turns one game per league in the app's ESPN cache into a live game, so the live
layouts can be checked when nothing is on. Needs a rooted emulator (adb root) and Scoreline run once.

    python -I tools/fake_live.py            # patch and push
    python -I tools/fake_live.py --undo     # delete the cache so the app refetches real data

Patched files get a far-future timestamp, which the app's cache treats as fresh.
"""
import datetime
import json
import os
import subprocess
import sys
import tempfile

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
SERIAL = os.environ.get("ANDROID_SERIAL", "emulator-5554")
CACHE = "/data/data/app.scoreline/cache/http"
ENV = dict(os.environ, MSYS_NO_PATHCONV="1")


def adb(*args, check=True):
    r = subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, env=ENV)
    if check and r.returncode != 0:
        raise SystemExit(r.stderr.decode(errors="replace"))
    return r.stdout


def status(period, clock_secs, display, detail):
    return {"clock": clock_secs, "displayClock": display, "period": period,
            "type": {"id": "2", "name": "STATUS_IN_PROGRESS", "state": "in", "completed": False,
                     "description": "In Progress", "detail": detail, "shortDetail": detail}}


# league slug -> how to make its first game live: (status, home score, away score, home lines, away lines)
LIVE = {
    "nba": (status(3, 312, "5:12", "5:12 - 3rd"), "81", "77", ["28", "31", "22"], ["25", "27", "25"]),
    "nfl": (status(4, 80, "1:20", "1:20 - 4th"), "24", "21", ["7", "10", "0", "7"], ["3", "7", "8", "3"]),
    "nhl": (status(2, 760, "12:40", "12:40 - 2nd"), "2", "1", ["1", "1"], ["1", "0"]),
    "mlb": (status(5, 0, "0:00", "Top 5th"), "3", "2", ["1", "0", "2", "0"], ["0", "0", "1", "1", "0"]),
    "eng.1": (status(2, 4020, "67'", "67'"), "2", "1", [], []),
}


def patch_event(league, e):
    st, hs, as_, hl, al = LIVE[league]
    for holder in (e, *(e.get("competitions") or [])):
        if "status" in holder:
            holder["status"] = json.loads(json.dumps(st))
    comp = e["competitions"][0]
    for c in comp["competitors"]:
        home = c.get("homeAway") == "home"
        c["score"] = hs if home else as_
        c["winner"] = False
        c["linescores"] = [{"value": float(v), "displayValue": v, "period": i + 1} for i, v in enumerate(hl if home else al)]
    if league == "eng.1":
        ids = {c["homeAway"]: c["team"]["id"] for c in comp["competitors"]}
        def goal(minute, team, name, pen=False):
            return {"scoringPlay": True, "redCard": False, "ownGoal": False, "penaltyKick": pen, "shootout": False,
                    "clock": {"displayValue": f"{minute}'"}, "team": {"id": ids[team]},
                    "athletesInvolved": [{"shortName": name, "displayName": name}]}
        comp["details"] = [goal(23, "home", "M. Salah"), goal(41, "away", "D. Welbeck"), goal(54, "home", "A. Isak", True)]


def main():
    adb("root")
    if "--undo" in sys.argv:
        adb("shell", f"rm -rf {CACHE}/*")
        adb("shell", "am force-stop app.scoreline")
        print("Cache cleared; Scoreline will refetch real data.")
        return
    work = tempfile.mkdtemp(prefix="scoreline-cache-")
    adb("pull", CACHE, work)
    local = os.path.join(work, "http")
    boards = []
    for name in sorted(os.listdir(local)):
        path = os.path.join(local, name)
        try:
            data = json.loads(open(path, "rb").read())
        except (ValueError, UnicodeDecodeError):
            continue
        events = data.get("events") if isinstance(data, dict) else None
        league = ((data.get("leagues") or [{}])[0].get("slug") if isinstance(data, dict) else None)
        if events and league in LIVE:
            boards.append((league, name, path, data))
    # Per league, the game nearest to now, so it lands in today's view.
    now = datetime.datetime.now(datetime.timezone.utc)
    chosen = {}
    for league, _, _, data in boards:
        for e in data["events"]:
            gap = abs((datetime.datetime.fromisoformat(e["date"].replace("Z", "+00:00")) - now).total_seconds())
            if league not in chosen or gap < chosen[league][0]:
                chosen[league] = (gap, e["id"])
    patched = []
    for league, name, path, data in boards:
        # The same game in every cached board (today's and the month's), so the merge can't undo it.
        hit = [e for e in data["events"] if e["id"] == chosen[league][1]]
        if not hit:
            continue
        patch_event(league, hit[0])
        open(path, "w", encoding="utf-8").write(json.dumps(data))
        patched.append((league, name, hit[0].get("shortName", "")))
    uid = adb("shell", "stat", "-c", "%U:%G", CACHE).decode().strip()
    for league, name, game in patched:
        adb("push", os.path.join(local, name), f"{CACHE}/{name}")
        adb("shell", f"chown {uid} {CACHE}/{name} && touch -m -d 2030-01-01 {CACHE}/{name}")
        print(f"{league:6} {game:16} {name}")
    adb("shell", "am force-stop app.scoreline")


if __name__ == "__main__":
    main()
