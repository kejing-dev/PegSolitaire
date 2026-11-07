"""Peg Solitaire icon (design-package appIcon, v3 malachite set): a deep malachite-teal #0B5B52 disc; a malachite-green
#1A7440 board disc with a thin gilt #D4A64A rim; 32 ivory #ECDAB6 dots in the English cross with the centre a slightly
larger gilt dot (the empty centre hole a full solve ends in). No text.

  python3 tools/build_icon.py [--icon512 out.png] [--preview out.png]

Writes res/drawable/icon_3d_layer_{0,1,2}.png + icon_3d_sdf_{0,1,2}.png (PICO layered icon), mipmap-xxxhdpi/
ic_spatial_launcher.png and the adaptive foreground vector res/drawable/ic_launcher_fg.xml (same shapes).
"""
import math
import os
import sys
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S, SS = 1024, 4
BG = (0x0B, 0x5B, 0x52)
MALACHITE = (0x1A, 0x74, 0x40)
GILT = (0xD4, 0xA6, 0x4A)
IVORY = (0xEC, 0xDA, 0xB6)


def circle(cx, cy, r, n=48):
    return [(cx + r * math.cos(2 * math.pi * i / n), cy + r * math.sin(2 * math.pi * i / n)) for i in range(n)]


C = (54.0, 54.0)
BOARD_OUT, BOARD_IN = circle(C[0], C[1], 36.0, 96), circle(C[0], C[1], 34.4, 96)
PITCH = 8.2
DOTS = []
for r in range(7):
    for c in range(7):
        if (2 <= c <= 4) or (2 <= r <= 4):
            if (c, r) != (3, 3):
                DOTS.append(circle(C[0] + (c - 3) * PITCH, C[1] + (r - 3) * PITCH, 2.7, 24))
CENTRE = circle(C[0], C[1], 3.4, 32)


def big(draw_fn):
    im = Image.new("RGBA", (S * SS, S * SS), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(im), S * SS / 108.0)
    return im.resize((S, S), Image.LANCZOS)


def sc(pts, k):
    return [(x * k, y * k) for x, y in pts]


def layer_bg():
    return big(lambda dr, k: dr.ellipse((0, 0, 108 * k - 1, 108 * k - 1), fill=BG + (255,)))


def layer_board():
    def d(dr, k):
        dr.polygon(sc(BOARD_OUT, k), fill=GILT + (255,))
        dr.polygon(sc(BOARD_IN, k), fill=MALACHITE + (255,))
    return big(d)


def layer_marbles():
    def d(dr, k):
        for p in DOTS:
            dr.polygon(sc(p, k), fill=IVORY + (255,))
        dr.polygon(sc(CENTRE, k), fill=GILT + (255,))
    return big(d)


def sdf(layer):
    inside = layer.getchannel("A").point(lambda v: 255 if v >= 128 else 0)
    dist = Image.new("L", inside.size, 0)
    cur = inside
    for i in range(1, 33):
        dist.paste(min(255, i * 8), mask=cur)
        cur = cur.filter(ImageFilter.MinFilter(3) if i % 2 else ImageFilter.Kernel((3, 3), (0, 1, 0, 1, 1, 1, 0, 1, 0), 5))
        cur = cur.point(lambda v: 255 if v >= 255 else 0)
    dist = Image.composite(dist, Image.new("L", inside.size, 0), inside)
    return Image.merge("RGBA", (dist, dist, dist, dist))


def path(pts):
    return "M" + "L".join("%.2f,%.2f" % p for p in pts) + "Z"


def vector():
    hexc = lambda c: "#FF%02X%02X%02X" % c
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<!-- Peg Solitaire launcher foreground (tools/build_icon.py draws the same artwork for the PICO layered icon). -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">',
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(GILT), path(BOARD_OUT)),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(MALACHITE), path(BOARD_IN)),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(IVORY), "".join(path(p) for p in DOTS)),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(GILT), path(CENTRE)),
        '</vector>',
    ]
    open(os.path.join(RES, "drawable", "ic_launcher_fg.xml"), "w").write("\n".join(lines) + "\n")


def main():
    layers = [layer_bg(), layer_board(), layer_marbles()]
    for i, layer in enumerate(layers):
        layer.save(os.path.join(RES, "drawable", f"icon_3d_layer_{i}.png"))
        sdf(layer).save(os.path.join(RES, "drawable", f"icon_3d_sdf_{i}.png"))
    flat = Image.new("RGBA", layers[0].size, (0, 0, 0, 0))
    for l in layers:
        flat.alpha_composite(l)
    flat.save(os.path.join(RES, "mipmap-xxxhdpi", "ic_spatial_launcher.png"))
    vector()
    if "--icon512" in sys.argv:
        flat.resize((512, 512), Image.LANCZOS).save(sys.argv[sys.argv.index("--icon512") + 1])
    if "--preview" in sys.argv:
        w = 256
        sheet = Image.new("RGB", (w * 4, w), (60, 64, 70))
        for i, t in enumerate(layers + [flat]):
            r = t.resize((w, w), Image.LANCZOS)
            sheet.paste(r, (i * w, 0), r)
        sheet.save(sys.argv[sys.argv.index("--preview") + 1])
    print("ICON ok")


main()
