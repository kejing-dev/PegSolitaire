"""Real pointer actions on the control window (pico-cli interact, no debug shortcut for the action itself) for the
ui_gates evidence: [重开] mid-game (newgame-confirm), a start-board tile mid-game, the settings first screen, quitting.

  DEV=emulator-5564 python3 tools/realtap.py OUTDIR

Debug broadcast is only used to set the stage (a game with a move on the board, the selection for the key test).
"""
import json, os, subprocess, sys, time
from PIL import Image

DEV = os.environ.get("DEV", "emulator-5564")
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PKG = "io.embodit.pegsolitaire"
OUT = sys.argv[1]
CROP = tuple(int(v) for v in os.environ.get("CROP", "0,0,99999,99999").split(","))
log = open(os.path.join(OUT, "realtap.txt"), "a", encoding="utf-8")


def note(s):
    line = time.strftime("%H:%M:%S ") + s
    print(line, flush=True); log.write(line + "\n"); log.flush()


def cmd(c, a=""):
    subprocess.run([ADB, "-s", DEV, "shell", "am", "broadcast", "-a", PKG + ".DEBUG", "-p", PKG, "--es", "cmd", c, "--es", "a", '"%s"' % a], capture_output=True)


def query():
    r = subprocess.run(["pico-cli", "interact", "query", "-d", DEV, "--package", PKG, "--compact"], capture_output=True, text=True)
    try:
        return json.loads(r.stdout)
    except ValueError:
        return {}


def click(name_prefix):
    q = query()
    for c in q.get("controls", []):
        if c.get("name", "").startswith(name_prefix):
            r = subprocess.run(["pico-cli", "interact", "perform", "-d", DEV, "--package", PKG, "--node", c["node"], "--action-id", "16"], capture_output=True, text=True)
            note(f"click '{c['name']}' -> {r.stdout.strip()[:120]}")
            return True
    note(f"NOT FOUND '{name_prefix}' among {[c.get('name') for c in q.get('controls', [])]}")
    return False


def shot(name):
    raw = os.path.join(OUT, "raw.png")
    subprocess.run(["pico-cli", "capture", "screenshot", "-d", DEV, "-o", raw, "--replace"], capture_output=True)
    im = Image.open(raw); im.crop((CROP[0], CROP[1], min(CROP[2], im.width), min(CROP[3], im.height))).save(os.path.join(OUT, name))
    os.remove(raw)
    note("shot " + name)


if not os.environ.get("KEEPCAM"):
    subprocess.run(["pico-cli", "camera", "reset", "-d", DEV], capture_output=True)
if os.environ.get("CAM"):
    subprocess.run(["pico-cli", "camera", "move", "-d", DEV, "--text", os.environ["CAM"]], capture_output=True)
time.sleep(2)
cmd("lang", "zh"); cmd("freeze", "off")
# stage: a fresh board and 3 hops through the debug jump (same GameFlow path as pinches)
cmd("new", "standard"); time.sleep(1.0)
for f, t in (("d6", "d4"), ("f5", "d5"), ("c5", "e5")):
    cmd("jump", f + " " + t); time.sleep(0.8)
# restart over a game in progress: the real [重开] key on the game page
if not click("对局"):
    cmd("page", "play")
time.sleep(1.0)
click("重开"); time.sleep(1.2)
shot("newgame-confirm.png")
click("算了"); time.sleep(0.8)
# another start board mid-game: the real 「钻石」 tile asks too
click("钻石"); time.sleep(1.2)
shot("change-start-confirm.png")
click("算了"); time.sleep(0.8)
# settings first screen
if not click("设置"):
    cmd("page", "settings")
time.sleep(1.2)
shot("settings-firstscreen.png")
# quit for real
click("退出应用"); time.sleep(1.0)
shot("exit-confirm.png")
click("退出"); time.sleep(4.0)
r = subprocess.run([ADB, "-s", DEV, "shell", "dumpsys", "activity", "recents"], capture_output=True, text=True)
open(os.path.join(OUT, "exit-recents.txt"), "w").write(r.stdout)
left = [l for l in r.stdout.splitlines() if "Recent #" in l and PKG in l]
ps = subprocess.run([ADB, "-s", DEV, "shell", "pidof", PKG], capture_output=True, text=True).stdout.strip()
note(f"recents rows with {PKG}: {len(left)}; process: '{ps or 'gone'}'")
