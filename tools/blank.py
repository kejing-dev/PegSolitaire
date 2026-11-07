"""Blank of the control window in a screenshot, by the factory rule (2026-11-05 统一口径): (content top - window top +
window bottom - content bottom) / window height <= 25 %, plus the largest blank band inside the content (<= 20 %).

  python3 tools/blank.py SHOT.png [...]

The window is the big light, low-saturation rounded panel (found column / row wise); a row is "content" when its luminance
spread across the inner 90 % of the window width is above a small threshold (text, a control edge, a card)."""
import sys
import numpy as np
from PIL import Image


def analyse(path):
    a = np.asarray(Image.open(path).convert("RGB")).astype(float)
    lum = a.mean(axis=2)
    sat = a.max(axis=2) - a.min(axis=2)
    panel = (lum > 175) & (sat < 30)
    rows = np.where(panel.mean(axis=1) > 0.35)[0]
    cols = np.where(panel.mean(axis=0) > 0.35)[0]
    if len(rows) < 10 or len(cols) < 10:
        return None
    top, bot, left, right = rows[0], rows[-1], cols[0], cols[-1]
    h = bot - top
    inset = int(0.05 * (right - left))
    inner = lum[top + int(0.03 * h):bot - int(0.03 * h), left + inset:right - inset]
    spread = inner.max(axis=1) - inner.min(axis=1)
    content = np.where(spread > 40)[0]
    if len(content) == 0:
        return None
    c0, c1 = content[0] + int(0.03 * h), content[-1] + int(0.03 * h)
    outer = (c0 + (h - c1)) / h
    gaps = np.diff(content)
    band = (gaps.max() if len(gaps) else 0) / h
    return outer, band


for p in sys.argv[1:]:
    r = analyse(p)
    print("%-48s %s" % (p.split("/")[-1], "n/a" if r is None else "outer %4.1f %%  inner band %4.1f %%" % (r[0] * 100, r[1] * 100)))
