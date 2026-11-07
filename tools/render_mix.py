"""Renders the app's sound timing at the default volume (gain 1.0) to check the mix: five quick taps of each kind and a
fast hop sequence (a hop every 300 ms: land at 260 ms, tray at 460 ms, so the next land overlaps the last tray; the ending sound 220 ms after the last tray), with the
SoundPlayer rules (one instance per event: a new play cuts the previous one; ±5 % rate on land / tray). Rendered, not
recorded (no audio loopback from the emulator on kj-mbp-m5).

  python3 tools/render_mix.py [OUT.wav]      prints the peak of each scenario
"""
import math, os, random, sys, wave
import numpy as np

SND = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "assets", "sounds")
RATE = 44100


def load(n):
    with wave.open(os.path.join(SND, n + ".wav")) as w:
        return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(float) / 32768


def variants(e):
    return [load(n) for n in (e, e + "_2", e + "_3") if os.path.exists(os.path.join(SND, n + ".wav"))]


def render(events, length_s):
    """events: (t_s, name); one stream per name, a new play stops the previous one of that name."""
    out = np.zeros(int(length_s * RATE))
    rnd = random.Random(7)
    last_end = {}
    plays = []
    for t, name in sorted(events):
        vs = variants(name)
        x = vs[rnd.randrange(len(vs))]
        if name in ("land", "tray"):
            r = 0.95 + rnd.random() * 0.1
            x = np.interp(np.arange(int(len(x) / r)) * r, np.arange(len(x)), x)
        s = int(t * RATE)
        if name in last_end:
            ps, px = last_end[name]
            cut = s - ps
            if 0 < cut < len(px):
                plays[[i for i, p in enumerate(plays) if p[0] == ps and p[2] == name][-1]] = (ps, px[:cut], name)
        plays.append((s, x, name)); last_end[name] = (s, x)
    for s, x, _ in plays:
        e = min(len(out), s + len(x)); out[s:e] += x[:e - s]
    return out


def peak_db(x):
    return 20 * math.log10(max(1e-9, np.abs(x).max()))


scen = {
    "5 quick illegal taps (150 ms)": [(i * 0.15, "illegal") for i in range(5)],
    "5 quick hops (300 ms each)": [e for i in range(5) for e in ((i * 0.3 + 0.26, "land"), (i * 0.3 + 0.46, "tray"))],
    "last hop + full solve": [(0.26, "land"), (0.46, "tray"), (0.68, "solved")],
    "last hop + stuck": [(0.26, "land"), (0.46, "tray"), (0.68, "stuck")],
}
mix = []
for name, ev in scen.items():
    x = render(ev, 2.5)
    print("%-32s peak %5.1f dBFS" % (name, peak_db(x)))
    mix.append(np.clip(x, -1, 1))
if len(sys.argv) > 1:
    seq = np.concatenate(mix)
    with wave.open(sys.argv[1], "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes((seq * 32767).astype("<i2").tobytes())
