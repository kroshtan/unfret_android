"""
Drives the emulator's screen through adb for the store screenshots (store/play/README.md).

  python3 ui.py tap TEXT [TIMEOUT]    tap the first view whose text or description contains TEXT, scrolling down to it
  python3 ui.py wait TEXT [TIMEOUT]   wait until a view with TEXT is on screen
  python3 ui.py top TEXT [Y]          scroll until the view with TEXT is at about Y px from the top (default 330)

Exits non-zero, with the texts that were on screen, when TEXT doesn't turn up.
"""

import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def adb(*args: str) -> str:
    return subprocess.run(["adb", *args], check=True, capture_output=True, text=True).stdout


def nodes() -> list[tuple[str, tuple[int, int, int, int]]]:
    for _ in range(5):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        raw = adb("exec-out", "cat", "/sdcard/ui.xml")
        if raw.strip().endswith("</hierarchy>"):
            break
        time.sleep(0.5)
    found = []
    for node in ET.fromstring(raw[raw.index("<?xml") :]).iter("node"):
        label = node.get("text") or node.get("content-desc") or ""
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "[0,0][0,0]")))
        if label:
            found.append((label, (x1, y1, x2, y2)))
    return found


def find(text: str) -> tuple[int, int, int, int] | None:
    for label, bounds in nodes():
        if text.lower() in label.lower() and bounds[3] > bounds[1]:
            return bounds
    return None


def swipe_up(distance: int = 700) -> None:
    adb("shell", "input", "swipe", "540", "1400", "540", str(1400 - distance), "600")
    time.sleep(0.8)


def wait(text: str, timeout: float, scroll: bool) -> tuple[int, int, int, int]:
    end = time.time() + timeout
    while True:
        bounds = find(text)
        # A view at the very bottom may sit under the gesture bar: scroll it up a bit first.
        if bounds and bounds[3] < 1780:
            return bounds
        if time.time() > end:
            sys.exit(f"not found: {text!r}; on screen: {[label for label, _ in nodes()]}")
        if scroll:
            swipe_up(500 if bounds else 700)
        else:
            time.sleep(0.5)


def main() -> None:
    command, text = sys.argv[1], sys.argv[2]
    if command == "tap":
        x1, y1, x2, y2 = wait(text, float(sys.argv[3]) if len(sys.argv) > 3 else 20, scroll=True)
        adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        time.sleep(0.8)
    elif command == "wait":
        wait(text, float(sys.argv[3]) if len(sys.argv) > 3 else 30, scroll=False)
    elif command == "top":
        target = int(sys.argv[3]) if len(sys.argv) > 3 else 330
        for _ in range(4):
            y = wait(text, 20, scroll=True)[1]
            if abs(y - target) < 30:
                break
            # A slow drag by the difference (slow: no fling), inside the scrolling area (y 300 to 1750).
            delta = max(-1300, min(1300, y - target))
            start = 1650 if delta > 0 else 350
            adb("shell", "input", "swipe", "540", str(start), "540", str(start - delta), "2000")
            time.sleep(1.0)
    else:
        sys.exit(__doc__)


main()
