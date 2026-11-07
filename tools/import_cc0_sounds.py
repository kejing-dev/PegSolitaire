"""Sounds for the malachite peg board (profile D51 / B20, ui_gates sound-source): every physical sound is a CC0 recording,
cleaned, crisp, no added reverb / thickening / compression (2026-11-06 rule); selecting / hovering is silent.

  python3 tools/import_cc0_sounds.py [~/Downloads/cc0-sounds]

  land(_2,_3)  a stone ball dropping into its ivory cup「嗒」: freesound 840425 Hongsungbock "baduk2" (CC0), three single stone hits
  tray(_2,_3)  the hopped ball landing on the others in the tray「叮」: freesound 628997 KaleidacousticsAudio "Marbles_Hitting each other" (CC0)
  illegal      a dull short knock: Kenney impact-sounds impactSoft_medium_000 (CC0), low-passed, quieter
  stuck        two low knocks: Kenney impact-sounds impactWood_heavy_000 (CC0) twice, 230 ms apart
  solved       a small three-note chime: Kenney impact-sounds impactBell_heavy_000 (CC0) at 1.5 / 1.89 / 2.25x, 170 ms apart
  one_left     one note of the same chime

Chain: macOS afconvert -> 44.1 kHz mono; FFT band-pass (120 Hz - 6.5 kHz, removes rumble and the harsh top); spectral
gate denoise from the quietest 100 ms of the file (freesound field takes only); cut 4 ms before the onset to where it decays to -42 dB; 3 ms fade-in,
short fade-out; peak normalised (-1 dBFS for land / tray / chime, -6 for illegal). Writes app/src/main/assets/sounds/*.wav,
tools/sound-sources.txt and tools/qa-sample.wav (the sounding segments back to back, the loudness evidence).
"""
import math
import os
import subprocess
import sys
import tempfile
import wave

import numpy as np

SRC = os.path.expanduser(sys.argv[1] if len(sys.argv) > 1 else "~/Downloads/cc0-sounds")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "sounds")
RATE = 44100
IMP = "kenney_impact-sounds/Audio/"
GO = "freesound-go-stones/840425_Hongsungbock_baduk2.mp3"
MARBLES = "freesound-marbles/marbles_628997.mp3"
SOURCES = {}


def decode(rel):
    with tempfile.TemporaryDirectory() as d:
        out = os.path.join(d, "x.wav")
        subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@%d" % RATE, "-c", "1", os.path.join(SRC, rel), out], check=True)
        with wave.open(out) as w:
            return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(float) / 32768


def bandpass(x, lo=120.0, hi=6500.0):
    n = len(x)
    f = np.fft.rfftfreq(n, 1 / RATE)
    X = np.fft.rfft(x)
    g = np.clip((f - lo * 0.7) / (lo * 0.3), 0, 1) * np.clip((hi * 1.25 - f) / (hi * 0.25), 0, 1)
    return np.fft.irfft(X * g, n)


def denoise(x, noise, frame=1024, k=1.6):
    """Spectral gate: subtract k x the noise magnitude per bin (overlap-add, Hann)."""
    hop = frame // 2
    win = np.hanning(frame)
    nm = np.mean([np.abs(np.fft.rfft(noise[i:i + frame] * win)) for i in range(0, max(1, len(noise) - frame), hop)] or [np.zeros(frame // 2 + 1)], axis=0)
    out = np.zeros(len(x) + frame)
    norm = np.zeros(len(x) + frame)
    xp = np.concatenate([x, np.zeros(frame)])
    for i in range(0, len(x), hop):
        S = np.fft.rfft(xp[i:i + frame] * win)
        mag = np.abs(S)
        gain = np.clip((mag - k * nm) / np.maximum(mag, 1e-9), 0, 1)
        out[i:i + frame] += np.fft.irfft(S * gain, frame) * win
        norm[i:i + frame] += win ** 2
    return (out / np.maximum(norm, 1e-6))[:len(x)]


def quietest(x, ms=100):
    n = int(ms / 1000 * RATE)
    best, at = 1e9, 0
    for i in range(0, len(x) - n, n // 2):
        e = np.mean(x[i:i + n] ** 2)
        if e < best:
            best, at = e, i
    return x[at:at + n]


def onsets(x, min_gap_s=0.25):
    """Sample indices where a hit starts (10 ms energy jumps 12 dB over the previous 30 ms)."""
    w = int(0.01 * RATE)
    e = np.array([np.mean(x[i:i + w] ** 2) + 1e-12 for i in range(0, len(x) - w, w)])
    db = 10 * np.log10(e)
    peak = db.max()
    out = []
    for i in range(3, len(db)):
        if db[i] > peak - 30 and db[i] - db[i - 3:i].max() > 12 and (not out or (i * w - out[-1]) > min_gap_s * RATE):
            out.append(i * w)
    return out


def cut(x, at, max_s=0.35, floor_db=-42):
    s = max(0, at - int(0.004 * RATE))
    seg = x[s:s + int(max_s * RATE)].copy()
    env = np.abs(seg)
    pk = env.max()
    idx = np.where(env > pk * 10 ** (floor_db / 20))[0]
    seg = seg[:idx[-1] + 1] if len(idx) else seg
    k = min(len(seg), int(0.003 * RATE)); seg[:k] *= np.linspace(0, 1, k)
    k = min(len(seg), int(0.025 * RATE)); seg[-k:] *= np.linspace(1, 0, k)
    return seg


def first_hit(x):
    """A studio one-shot: the first sample within 20 dB of its peak."""
    return int(np.argmax(np.abs(x) > np.abs(x).max() * 0.1))


def second_hit(seg):
    """Peak after 30 ms relative to the main peak (a clean single hit has none)."""
    j = int(0.03 * RATE)
    return np.abs(seg[j:]).max() / max(1e-9, np.abs(seg).max()) if len(seg) > j else 0.0


def norm(x, peak_db=-1.0):
    return x / max(1e-9, np.abs(x).max()) * 10 ** (peak_db / 20)


def resample(x, ratio):
    n = int(len(x) / ratio)
    return np.interp(np.arange(n) * ratio, np.arange(len(x)), x)


def write(name, x):
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())


def clean(rel, gate=True):
    """Band-pass, then a spectral gate from the file's quietest 100 ms (field recordings only: the Kenney studio takes are
    already clean, and their quietest stretch is the bell's own tail)."""
    x = bandpass(decode(rel))
    return denoise(x, quietest(x)) if gate else x


def best_hits(x, n, max_s, exclude=()):
    cands = []
    for at in onsets(x):
        seg = cut(x, at, max_s)
        if len(seg) < int(0.04 * RATE):
            continue
        cands.append((second_hit(seg), at, seg))
    cands.sort(key=lambda c: c[0])
    out = []
    for sh, at, seg in cands:
        if any(abs(at - o) < 0.2 * RATE for o, _ in out) or at in exclude:
            continue
        out.append((at, seg))
        if len(out) == n:
            break
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    for f in os.listdir(OUT):
        if f.endswith(".wav"):
            os.remove(os.path.join(OUT, f))
    made = {}
    go = clean(GO)
    # the three single stone hits five-stones v0.1.5 picked from the same take (no second stone, lowest floor)
    for i, t in enumerate((16.922, 15.280, 7.278)):
        name = "land" if i == 0 else f"land_{i + 1}"
        seg = norm(cut(go, int(t * RATE), 0.22))
        write(name, seg); made[name] = seg
        SOURCES[name] = f"CC0 录音 freesound https://freesound.org/people/Hongsungbock/sounds/840425/ （作者 Hongsungbock，baduk2）· 截自 {t:.3f} s · {len(seg) / RATE * 1000:.0f} ms · 石球落进象牙窝「嗒」"
    mar = clean(MARBLES)
    for i, (at, seg) in enumerate(best_hits(mar, 3, 0.25)):
        name = "tray" if i == 0 else f"tray_{i + 1}"
        seg = norm(seg)
        write(name, seg); made[name] = seg
        SOURCES[name] = f"CC0 录音 freesound https://freesound.org/people/KaleidacousticsAudio/sounds/628997/ （作者 KaleidacousticsAudio，Marbles_Hitting each other）· 截自 {at / RATE:.3f} s · {len(seg) / RATE * 1000:.0f} ms · 石球落进小碟碰到别的球「叮」"
    soft = clean(IMP + "impactSoft_medium_000.ogg", gate=False)
    il = norm(cut(soft, first_hit(soft), 0.18), -6.0)
    write("illegal", il); made["illegal"] = il
    SOURCES["illegal"] = "CC0 录音 Kenney kenney_impact-sounds / impactSoft_medium_000.ogg（闷敲，−6 dBFS，非法目标 / 跳不了）"
    wood = clean(IMP + "impactWood_heavy_000.ogg", gate=False)
    k1 = cut(wood, first_hit(wood), 0.25)
    st = np.zeros(int(0.23 * RATE) + len(k1))
    st[:len(k1)] += k1
    st[int(0.23 * RATE):int(0.23 * RATE) + len(k1)] += resample(k1, 0.94)[:len(k1)] * 0.9
    st = norm(st, -2.0)
    write("stuck", st); made["stuck"] = st
    SOURCES["stuck"] = "CC0 录音 Kenney kenney_impact-sounds / impactWood_heavy_000.ogg（两下低沉的敲击，第二下低 6 %，卡住）"
    bell = clean(IMP + "impactBell_heavy_000.ogg", gate=False)
    b1 = cut(bell, first_hit(bell), 1.2, -48)
    notes = [resample(b1, r) for r in (1.5, 1.89, 2.25)]
    gap = int(0.17 * RATE)
    ch = np.zeros(gap * 2 + max(len(n) for n in notes))
    for i, n in enumerate(notes):
        ch[i * gap:i * gap + len(n)] += n * (0.8 if i < 2 else 1.0)
    ch = norm(ch, -1.0)
    write("solved", ch); made["solved"] = ch
    SOURCES["solved"] = "CC0 录音 Kenney kenney_impact-sounds / impactBell_heavy_000.ogg（升调 1.5 / 1.89 / 2.25 倍三下，相隔 170 ms：小风铃三音，全解）"
    one = norm(notes[0], -3.0)
    write("one_left", one); made["one_left"] = one
    SOURCES["one_left"] = "CC0 录音 Kenney kenney_impact-sounds / impactBell_heavy_000.ogg（升调 1.5 倍一下：剩一颗不在正中）"

    order = ["land", "tray", "land_2", "tray_2", "land_3", "tray_3", "illegal", "stuck", "one_left", "solved"]
    seq = np.concatenate([np.concatenate([made[n], np.zeros(int(0.02 * RATE))]) for n in order])
    with wave.open(os.path.join(ROOT, "tools", "qa-sample.wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes((np.clip(seq, -1, 1) * 32767).astype("<i2").tobytes())
    with open(os.path.join(ROOT, "tools", "sound-sources.txt"), "w", encoding="utf-8") as f:
        for n in sorted(SOURCES):
            f.write(f"- {n}.wav：{SOURCES[n]}\n")
    for n in order:
        x = made[n]
        print("%-10s %4.0f ms  peak %5.1f dBFS  rms %5.1f dBFS" % (n, len(x) / RATE * 1000, 20 * math.log10(np.abs(x).max()), 20 * math.log10(math.sqrt(np.mean(x ** 2)))))


if __name__ == "__main__":
    main()
