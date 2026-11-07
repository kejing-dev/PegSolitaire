"""Self-test / QA screenshot driver (debug builds): debug-broadcast steps (the same GameFlow calls as pinches and keys),
then a capture of the board ("board": camera reset, 2.3 m forward, 0.15 m down) or of the control window ("win": camera reset,
0.55 m right, 1.5 m forward).

  DEV=emulator-5564 python3 tools/shots.py OUTDIR [board|win|all] [zh|en|both]
"""
import os, subprocess, sys, time
from PIL import Image

DEV = os.environ.get("DEV", "emulator-5564")
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PKG = "io.embodit.pegsolitaire"
OUT = sys.argv[1]
WHAT = sys.argv[2] if len(sys.argv) > 2 else "all"
LANGS = {"both": ["zh", "en"]}.get(sys.argv[3] if len(sys.argv) > 3 else "zh", [sys.argv[3] if len(sys.argv) > 3 else "zh"])
os.makedirs(OUT, exist_ok=True)


def cmd(c, a="", wait=0.8):
    subprocess.run([ADB, "-s", DEV, "shell", "am", "broadcast", "-a", PKG + ".DEBUG", "-p", PKG, "--es", "cmd", c, "--es", "a", '"%s"' % a], capture_output=True)
    time.sleep(wait)


def cam(text):
    if os.environ.get("KEEPCAM"):
        return
    subprocess.run(["pico-cli", "camera", "reset", "-d", DEV], capture_output=True)
    time.sleep(2.0)
    if text:
        subprocess.run(["pico-cli", "camera", "move", "-d", DEV, "--format", "plain", "--text", text], capture_output=True)
    time.sleep(1.5)


def shot(name, crop=None):
    raw = os.path.join(OUT, "_raw.png")
    subprocess.run(["pico-cli", "capture", "screenshot", "-d", DEV, "-o", raw, "--replace"], capture_output=True)
    im = Image.open(raw)
    if crop:
        im = im.crop(crop)
    im.save(os.path.join(OUT, name + ".png"))
    print("shot", name, flush=True)


BOARD_CROP = None
WIN_CROP = tuple(int(v) for v in os.environ.get("WINCROP", "1060,860,1820,1840").split(","))

BOARD = [
    ("S03", [("goto", "S03")]),
    ("S04", [("goto", "S04")]),
    ("S05", [("goto", "S05 130")]),
    ("S05-tray", [("goto", "S05 330")]),
    ("S06-hint-sure", [("goto", "S06", 3.0)]),
    ("S09-full", [("goto", "S09 full", 24.0)]),
    ("S09-one", [("goto", "S09 one", 24.0)]),
    ("S09-stuck", [("goto", "S09 stuck", 6.0)]),
    ("tilt0", [("goto", "S04"), ("tilt", "0", 1.5)]),
    ("tilt45", [("tilt", "45", 1.5)]),
    ("tilt60", [("tilt", "60", 1.5)]),
    ("tilt90", [("tilt", "90", 1.5)]),
    ("tilt30", [("tilt", "30", 1.5)]),
]
WIN = [
    ("S01", [("goto", "S01", 1.5)]),
    ("S03", [("welcomedone", ""), ("page", "play"), ("goto", "S03")]),
    ("S04", [("goto", "S04")]),
    ("S06-hint-sure", [("goto", "S06", 3.0)]),
    ("S09-full", [("goto", "S09 full", 24.0)]),
    ("S09-stuck", [("goto", "S09 stuck", 6.0)]),
    ("S12-rules", [("goto", "S12")]),
    ("S12-history", [("history", "on")]),
    ("S11-settings", [("history", "off"), ("goto", "S11")]),
    ("S13-restart", [("goto", "S13 restart", 8.0)]),
    ("S13-change", [("no", ""), ("goto", "S13 change", 5.0)]),
    ("S13-exit", [("no", ""), ("goto", "S13 exit")]),
    ("S13-clear", [("no", ""), ("goto", "S13 clear")]),
]

for lang in LANGS:
    cmd("lang", lang)
    if WHAT in ("board", "all"):
        cam("move forward 2.3 meters then move down 0.15 meters")
        for name, steps in BOARD:
            for st in steps:
                cmd(st[0], st[1], st[2] if len(st) > 2 else 1.2)
            shot(f"board-{name}-{lang}", BOARD_CROP)
        cmd("freeze", "off"); cmd("tilt", "30")
    if WHAT in ("win", "all"):
        cam("move right 0.55 meters then move forward 1.5 meters")
        for name, steps in WIN:
            for st in steps:
                cmd(st[0], st[1], st[2] if len(st) > 2 else 1.2)
            shot(f"win-{name}-{lang}", WIN_CROP)
        cmd("no", "")
