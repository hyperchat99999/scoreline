"""Drives the app over adb for testing: dump the screen, tap by text, tap the Follow button in a row.

    python tools/ui.py tap "Get started"
    python tools/ui.py follow "Chelsea"          # scrolls until the row is visible
    python tools/ui.py texts                      # list visible texts
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

# The only connected device by default; set ANDROID_SERIAL to pick one, such as the emulator.
ADB = ["adb"] + (["-s", os.environ["ANDROID_SERIAL"]] if os.environ.get("ANDROID_SERIAL") else [])


def adb(*args):
    return subprocess.run(ADB + list(args), capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    root = ET.fromstring(xml[xml.index("<"):])
    out = []
    for n in root.iter("node"):
        b = [int(x) for x in re.findall(r"\d+", n.get("bounds", "[0,0][0,0]"))]
        out.append((n.get("text", ""), n.get("content-desc", ""), b))
    return out


def center(b):
    return (b[0] + b[2]) // 2, (b[1] + b[3]) // 2


def tap_xy(x, y):
    adb("shell", "input", "tap", str(x), str(y))


def tap(text):
    for t, d, b in nodes():
        if t == text or d == text:
            tap_xy(*center(b))
            return True
    return False


def follow(title, attempts=8, unfollow=False):
    want = "Following" if unfollow else "Follow"
    for _ in range(attempts):
        ns = nodes()
        # The same text can be a chip, a subtitle ("NBA" under a team) or a row title. A row title
        # sits in the upper half of its row, i.e. above its Follow button's centre.
        for row in [b for t, d, b in ns if t == title]:
            y = center(row)[1]
            for t, d, b in ns:
                if t in ("Follow", "Following") and b[1] <= y <= center(b)[1] + 5:
                    if t == want:
                        tap_xy(*center(b))
                    return t
        adb("shell", "input", "swipe", "720", "2300", "720", "1300", "300")
        time.sleep(0.6)
    return None


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "tap":
        print(tap(sys.argv[2]))
    elif cmd == "follow":
        print(follow(sys.argv[2]))
    elif cmd == "unfollow":
        print(follow(sys.argv[2], unfollow=True))
    elif cmd == "texts":
        for t, d, b in nodes():
            if t or d:
                print(t or d, b)
