"""Builds the malachite board, the tray and the six malachite balls as GLB, in Python + numpy + Pillow (no Blender here).

  python3 tools/build_models.py [--preview out.png]

Gate B (2026-11-05): the procedural classical set of the sample v3 after the user's reference design/ref-malachite.jpg
(design-package theme.materials, assets[0..1]); the texture algorithm is the sample's (stripes around a centre per inlay
tile, fbm wobble, deep ink green -> emerald, rare pale lines; ivory square cups with a shallow dimple; two pink-brown
four-petal flowers; gilt seams along the cross outline and a gilt frame round the centre cup), baked at 2048².

Writes app/src/main/assets/models/:
  board.glb   disc Ø 0.42 m, 3 cm thick, origin on the top centre (+Y up, +Z toward the player, row 0 of the cross at -Z);
              top = baked colour + normal map (dimples / seams), malachite side band, dark bottom, thin gilt rim
  tray.glb    malachite dish Ø 0.16 m, 1.5 cm high, gilt lip; origin on the floor centre
  ball_0..5   Ø 2.8 cm glossy malachite balls (equirectangular "eye" stripes round a random pole), origin at the centre
Numbers match domain/Layout.kt. All materials OPAQUE (profile D24 / B12).
"""
import io
import json
import math
import os
import struct
import sys

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "models")

P = 0.052
R_BOARD, T = 0.21, 0.03
CUP = 0.034
BALL_R = 0.014
TRAY_R, TRAY_H = 0.08, 0.015
HALF = 0.21
GILT = (0xD4, 0xA6, 0x4A)

# ---------------------------------------------------------------- noise (the sample's hash / value noise / fbm, vectorised)

def hash3(x, y, z):
    x = np.asarray(x, dtype=np.int64) & 0xFFFFFFFF
    y = np.asarray(y, dtype=np.int64) & 0xFFFFFFFF
    z = np.asarray(z, dtype=np.int64) & 0xFFFFFFFF
    h = (x * 374761393 + y * 668265263 + z * 1274126177) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    h = h ^ (h >> 16)
    return (h & 0xFFFFFFFF) / 4294967295.0


def vnoise(x, y, z):
    xi, yi, zi = np.floor(x), np.floor(y), np.floor(z)
    xf, yf, zf = x - xi, y - yi, z - zi
    u, w, q = xf * xf * (3 - 2 * xf), yf * yf * (3 - 2 * yf), zf * zf * (3 - 2 * zf)
    xi, yi, zi = xi.astype(np.int64), yi.astype(np.int64), zi.astype(np.int64)
    L = lambda a, b, t: a + (b - a) * t
    return L(L(L(hash3(xi, yi, zi), hash3(xi + 1, yi, zi), u), L(hash3(xi, yi + 1, zi), hash3(xi + 1, yi + 1, zi), u), w),
             L(L(hash3(xi, yi, zi + 1), hash3(xi + 1, yi, zi + 1), u), L(hash3(xi, yi + 1, zi + 1), hash3(xi + 1, yi + 1, zi + 1), u), w), q)


def fbm(x, y, z):
    return vnoise(x, y, z) * 0.6 + vnoise(x * 2.1, y * 2.1, z * 2.1) * 0.28 + vnoise(x * 4.3, y * 4.3, z * 4.3) * 0.12


MAL = np.array([[3, 34, 18], [9, 66, 36], [26, 116, 64], [84, 172, 116]], dtype=float)


def malachite(s, shade=1.0):
    t = 0.5 + 0.5 * np.sin(s)
    t2 = 0.5 + 0.5 * np.sin(s * 2.35 + 1.3)
    k = 0.62 * t ** 1.6 + 0.38 * t2 ** 2.6
    a = np.clip(k / 0.5, 0, 1)[..., None]
    b = np.clip((k - 0.5) / 0.5, 0, 1)[..., None]
    lo = MAL[0] + (MAL[1] - MAL[0]) * a
    hi = MAL[1] + (MAL[2] - MAL[1]) * b
    c = np.where((k < 0.5)[..., None], lo, hi)
    e = np.where((t > 0.982) & (t2 > 0.5), (t - 0.982) / 0.018 * 0.45 * t2, 0.0)[..., None]
    c = c + (MAL[3] - c) * e
    return c * (np.asarray(shade)[..., None] if np.ndim(shade) else shade)


def in_cross(cx, cz):
    return ((cx >= 2) & (cx <= 4) & (cz >= 0) & (cz <= 6)) | ((cz >= 2) & (cz <= 4) & (cx >= 0) & (cx <= 6))


# ---------------------------------------------------------------- board top texture

def flower(x, z):
    """(part, edge, along) arrays: part 0 = none, 1 = petal, 2 = centre (sample flowerHit, two flowers far-left / near-right)."""
    part = np.zeros(x.shape, np.int8); edge = np.zeros(x.shape); along = np.zeros(x.shape)
    for fx, fz in ((-2.55 * P, -2.55 * P), (2.55 * P, 2.55 * P)):
        dx, dz = x - fx, z - fz
        d = np.hypot(dx, dz)
        for k in range(4):
            th = math.pi / 4 + k * math.pi / 2
            ux, uz = math.cos(th), math.sin(th)
            al = dx * ux + dz * uz
            ac = -dx * uz + dz * ux
            L = 0.034
            wd = 0.0105 * np.sin(np.pi * np.clip(al / L, 0, 1)) * (1 + 0.25 * np.sin(al / L * np.pi * 2))
            hit = (al >= 0) & (al < L) & (np.abs(ac) < wd) & (part == 0)
            e = np.minimum(np.minimum((wd - np.abs(ac)) / 0.0012, (L - al) / 0.0012), 1)
            part = np.where(hit, 1, part); edge = np.where(hit, e, edge); along = np.where(hit, al / L, along)
        c = (d < 0.0045)
        part = np.where(c, 2, part); edge = np.where(c, d / 0.0045, edge)
    return part, edge, along


def board_textures(n=2048):
    px = 2 * HALF / n
    j, i = np.mgrid[0:n, 0:n]
    x = -HALF + (i + 0.5) * px
    z = -HALF + (j + 0.5) * px
    r = np.hypot(x, z)
    col = np.zeros((n, n, 3)); bump = np.full((n, n), 0.55); rough = np.full((n, n), 0.16)
    seam_w = 0.00035
    ccx = np.floor((x + 3.5 * P) / P).astype(int); ccz = np.floor((z + 3.5 * P) / P).astype(int)
    cross = in_cross(ccx, ccz)
    fx = (x + 3.5 * P) / P - ccx; fz = (z + 3.5 * P) / P - ccz
    near_x = np.minimum(fx, 1 - fx) * P < seam_w
    near_z = np.minimum(fz, 1 - fz) * P < seam_w
    # tile ids / stripe centres
    gx = np.floor((ccx + 10) / 2).astype(int); gz = np.floor((ccz + 10) / 2).astype(int)
    big = hash3(gx, gz, 5) < 0.5
    tid = np.where(cross, ccx * 13 + ccz * 7 + 1, np.where(big, 500 + gx * 31 + gz, 900 + ccx * 17 + ccz * 5))
    a = hash3(tid, 7, 3); b = hash3(tid, 11, 5); c = hash3(tid, 13, 9); d = hash3(tid, 17, 2)
    tcx, tcz, freq, sh, wv, ph = (a - 0.5) * 0.10, (b - 0.5) * 0.10, 520 + c * 560, 0.85 + d * 0.25, 2 + a * 3, b * 6.28
    single = cross | ~big
    ctr_x = np.where(single, (ccx - 3) * P + tcx, (2 * gx - 10 + 1 - 3.5) * P + tcx * 2.6)
    ctr_z = np.where(single, (ccz - 3) * P + tcz, (2 * gz - 10 + 1 - 3.5) * P + tcz * 2.6)
    dd = np.hypot(x - ctr_x, z - ctr_z); ang = np.arctan2(z - ctr_z, x - ctr_x)
    s2 = dd * freq + fbm(x * 16 + ph, z * 16, wv) * 15 + fbm(x * 90, z * 90, wv + 3) * 2.2 + np.sin(ang * 2 + ph) * 0.8 + ph
    col = malachite(s2, sh)
    # seams: dark between tiles, gilt along the cross outline
    ffx = ((x + 3.5 * P) / P + 10) / 2 - gx; ffz = ((z + 3.5 * P) / P + 10) / 2 - gz
    big_seam = (np.minimum(ffx, 1 - ffx) * 2 * P < seam_w) | (np.minimum(ffz, 1 - ffz) * 2 * P < seam_w)
    seam = np.where(cross, near_x | near_z, np.where(big, big_seam, near_x | near_z))
    seam = np.where(~cross & (r > 0.197), r < 0.1985, seam)
    nbx = np.where(fx < 0.5, ccx - 1, ccx + 1); nbz = np.where(fz < 0.5, ccz - 1, ccz + 1)
    brass = cross & ((near_x & ~in_cross(nbx, ccz)) | (near_z & ~in_cross(ccx, nbz)))
    # ivory cups
    hx = x - (ccx - 3) * P; hz = z - (ccz - 3) * P
    h = CUP / 2
    cup = cross & (np.abs(hx) < h) & (np.abs(hz) < h)
    grain = fbm(x * 300, z * 40, 3.3)
    ivory = np.stack([236 - grain * 16, 218 - grain * 18, 182 - grain * 22], -1)
    edge = np.minimum(h - np.abs(hx), h - np.abs(hz))
    dr = np.hypot(hx, hz)
    ivory = np.where((edge < 0.0006)[..., None], ivory * np.array([0.72, 0.68, 0.6]), ivory)
    k = np.clip(dr / 0.0105, 0, 1)
    dark = 0.80 + 0.20 * k
    in_dimple = dr < 0.0105
    ivory = np.where(in_dimple[..., None], ivory * np.stack([dark, dark, dark - 0.02], -1), ivory)
    ivory = np.where((in_dimple & (dr > 0.0098))[..., None], ivory * np.array([0.85, 0.82, 0.78]), ivory)
    centre_frame = cup & (ccx == 3) & (ccz == 3) & (edge < 0.0016)
    ivory = np.where(centre_frame[..., None], np.array([201, 160, 62.0]), ivory)
    col = np.where(cup[..., None], ivory, col)
    # height: dimples (2.5 mm deep spherical cap shading), cup edges, seams
    height = np.zeros((n, n))
    height = np.where(cup & in_dimple, -0.0025 * (1 - k * k), height)
    height = np.where(cup & (edge < 0.0006), height - 0.0003, height)
    rough = np.where(cup, 0.42, rough)
    rough = np.where(centre_frame, 0.25, rough)
    seam = seam & ~cup
    # flowers
    part, fedge, along = flower(x, z)
    sp = vnoise(x * 2600, z * 2600, 4); sp2 = vnoise(x * 700, z * 700, 8)
    petal = np.stack([204 - sp * 46 - (1 - along) * 20, 150 - sp * 50 - (1 - along) * 22, 122 - sp * 44 - (1 - along) * 10], -1)
    petal = np.where((sp > 0.8)[..., None], np.array([128, 74, 56.0]), petal)
    petal = np.where((sp2 < 0.13)[..., None], np.array([232, 212, 192.0]), petal)
    petal = np.where((fedge < 1)[..., None], petal * np.stack([0.55 + 0.45 * fedge, 0.5 + 0.5 * fedge, 0.45 + 0.55 * fedge], -1), petal)
    fcentre = np.where((fedge > 0.8)[..., None], np.array([120, 80, 50.0]), np.array([176, 120, 70.0]))
    col = np.where((part == 1)[..., None], petal, col)
    col = np.where((part == 2)[..., None], fcentre, col)
    seam = seam & (part == 0)
    rough = np.where(part > 0, 0.3, rough)
    col = np.where((seam & brass)[..., None], np.array([176, 140, 62.0]), col)
    col = np.where((seam & ~brass)[..., None], np.array([30, 40, 26.0]), col)
    height = np.where(seam, height - 0.0002, height)
    # the outer ring beyond the inlay: stripes running round the rim, a thin gilt line
    outer = r > 0.2005
    s_out = r * 900 + fbm(x * 60, z * 60, 1.7) * 5 + np.arctan2(z, x) * 2
    col = np.where(outer[..., None], malachite(s_out, 0.82), col)
    gl = outer & (r < 0.2015)
    col = np.where(gl[..., None], np.array([150, 118, 52.0]), col)
    col = np.clip(col, 0, 255).astype(np.uint8)
    # normal map from the height field (tangent space: +X = u, +Y = v up in the image = -Z on the board)
    gy, gxh = np.gradient(height, px)
    nx, ny = -gxh * 0.8, gy * 0.8
    nz = np.ones_like(nx)
    ln = np.sqrt(nx * nx + ny * ny + nz * nz)
    nrm = np.stack([nx / ln, ny / ln, nz / ln], -1)
    nrm = ((nrm * 0.5 + 0.5) * 255).astype(np.uint8)
    return Image.fromarray(col, "RGB"), Image.fromarray(nrm, "RGB")


def band_texture(w, h, fn, shade):
    j, i = np.mgrid[0:h, 0:w].astype(float)
    return Image.fromarray(np.clip(malachite(fn(i, j), shade), 0, 255).astype(np.uint8), "RGB")


def ball_texture(seed, w=512, h=256):
    """Equirectangular: concentric stripes round a random pole = a malachite ball's 'eye' (sample buildBallTexture)."""
    j, i = np.mgrid[0:h, 0:w].astype(float)
    th = (j + 0.5) / h * math.pi
    ph = (i + 0.5) / w * math.pi * 2
    dx, dy, dz = np.sin(th) * np.cos(ph), np.cos(th), np.sin(th) * np.sin(ph)
    px_, py_, pz_ = (float(hash3(seed, 1, 1)) - 0.5, float(hash3(seed, 2, 1)) - 0.5, float(hash3(seed, 3, 1)) - 0.5)
    pl = math.sqrt(px_ ** 2 + py_ ** 2 + pz_ ** 2) or 1
    px_, py_, pz_ = px_ / pl, py_ / pl, pz_ / pl
    f = 14 + float(hash3(seed, 4, 1)) * 10
    a = np.arccos(np.clip(dx * px_ + dy * py_ + dz * pz_, -1, 1))
    s = a * f * 1.15 + fbm(dx * 1.6 + seed, dy * 1.6, dz * 1.6) * 13 + fbm(dx * 5, dy * 5, dz * 5 + seed) * 3.5
    col = malachite(s, 0.95 + float(hash3(seed, 5, 1)) * 0.2)
    return Image.fromarray(np.clip(col, 0, 255).astype(np.uint8), "RGB")


# ---------------------------------------------------------------- GLB writer

def srgb_lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


class Glb:
    def __init__(self):
        self.bin = bytearray()
        self.views, self.accessors = [], []

    def _view(self, data, target=None):
        while len(self.bin) % 4:
            self.bin.append(0)
        off = len(self.bin)
        self.bin += data
        v = {"buffer": 0, "byteOffset": off, "byteLength": len(data)}
        if target:
            v["target"] = target
        self.views.append(v)
        return len(self.views) - 1

    def floats(self, arr, minmax=False):
        arr = np.asarray(arr, dtype="<f4")
        view = self._view(arr.tobytes(), 34962)
        acc = {"bufferView": view, "componentType": 5126, "count": int(arr.shape[0]), "type": {2: "VEC2", 3: "VEC3", 4: "VEC4"}[arr.shape[1]]}
        if minmax:
            acc["min"] = arr.min(0).tolist(); acc["max"] = arr.max(0).tolist()
        self.accessors.append(acc)
        return len(self.accessors) - 1

    def indices(self, idx):
        view = self._view(np.asarray(idx, dtype="<u4").tobytes(), 34963)
        self.accessors.append({"bufferView": view, "componentType": 5125, "count": len(idx), "type": "SCALAR"})
        return len(self.accessors) - 1

    def image(self, im, jpeg=False):
        b = io.BytesIO()
        if jpeg:
            im.convert("RGB").save(b, "JPEG", quality=90)
        else:
            im.convert("RGB").save(b, "PNG", optimize=True)
        return self._view(b.getvalue()), ("image/jpeg" if jpeg else "image/png")

    def write(self, path, gltf):
        gltf["asset"] = {"version": "2.0", "generator": "peg-solitaire tools/build_models.py"}
        gltf["buffers"] = [{"byteLength": len(self.bin)}]
        gltf["bufferViews"] = self.views
        gltf["accessors"] = self.accessors
        js = json.dumps(gltf, separators=(",", ":")).encode()
        while len(js) % 4:
            js += b" "
        while len(self.bin) % 4:
            self.bin.append(0)
        total = 12 + 8 + len(js) + 8 + len(self.bin)
        with open(path, "wb") as f:
            f.write(struct.pack("<III", 0x46546C67, 2, total))
            f.write(struct.pack("<II", len(js), 0x4E4F534A) + js)
            f.write(struct.pack("<II", len(self.bin), 0x004E4942) + bytes(self.bin))


class Mesh:
    def __init__(self):
        self.pos, self.nor, self.uv, self.tan = [], [], [], []
        self.prims = {}

    def add(self, mat, verts, norms, uvs, idx, tangents=None):
        base = len(self.pos)
        self.pos += [tuple(v) for v in verts]
        self.nor += [tuple(n) for n in norms]
        self.uv += [tuple(t) for t in uvs]
        self.tan += [tuple(t) for t in (tangents or [(1.0, 0.0, 0.0, 1.0)] * len(verts))]
        self.prims.setdefault(mat, []).extend(base + i for i in idx)

    def write(self, name, materials, images=(), jpeg=()):
        g = Glb()
        pa = g.floats(self.pos, minmax=True); na = g.floats(self.nor); ta = g.floats(self.uv); tg = g.floats(self.tan)
        prims = [{"attributes": {"POSITION": pa, "NORMAL": na, "TEXCOORD_0": ta, "TANGENT": tg}, "indices": g.indices(idx), "material": m}
                 for m, idx in sorted(self.prims.items())]
        gltf = {"scene": 0, "scenes": [{"nodes": [0]}], "nodes": [{"name": name, "mesh": 0}], "meshes": [{"name": name, "primitives": prims}], "materials": materials}
        if images:
            ims = []
            for k, im in enumerate(images):
                view, mime = g.image(im, jpeg=k in jpeg)
                ims.append({"bufferView": view, "mimeType": mime})
            gltf["images"] = ims
            gltf["textures"] = [{"source": i, "sampler": 0} for i in range(len(images))]
            gltf["samplers"] = [{"magFilter": 9729, "minFilter": 9987, "wrapS": 10497, "wrapT": 10497}]
        os.makedirs(OUT, exist_ok=True)
        path = os.path.join(OUT, name + ".glb")
        g.write(path, gltf)
        tris = sum(len(i) for i in self.prims.values()) // 3
        print("%-14s %6d triangles %9d bytes" % (name + ".glb", tris, os.path.getsize(path)))
        return tris


def mat(name, color=None, rough=0.6, tex=None, metal=0.0, normal=None):
    pbr = {"metallicFactor": metal, "roughnessFactor": rough}
    if tex is not None:
        pbr["baseColorTexture"] = {"index": tex}
    if color is not None:
        pbr["baseColorFactor"] = [srgb_lin(c) for c in color] + [1.0]
    m = {"name": name, "pbrMetallicRoughness": pbr, "alphaMode": "OPAQUE", "doubleSided": False}
    if normal is not None:
        m["normalTexture"] = {"index": normal, "scale": 1.0}
    return m


def torus(m, mi, R, r, y, seg=160, sides=10):
    verts, norms, uvs, idx = [], [], [], []
    for i in range(seg + 1):
        a = 2 * math.pi * i / seg
        for j in range(sides + 1):
            b = 2 * math.pi * j / sides
            n = (math.cos(b) * math.cos(a), math.sin(b), math.cos(b) * math.sin(a))
            verts.append(((R + r * math.cos(b)) * math.cos(a), y + r * math.sin(b), (R + r * math.cos(b)) * math.sin(a)))
            norms.append(n); uvs.append((i / seg, j / sides))
    for i in range(seg):
        for j in range(sides):
            p = i * (sides + 1) + j; q = p + sides + 1
            idx += [p, p + 1, q, p + 1, q + 1, q]
    m.add(mi, verts, norms, uvs, idx)


def lathe(m, mi, profile, seg=96, v_scale=1.0):
    """Revolve (r, y) points round +Y; normals from the profile segments (outward side = left of the walk)."""
    verts, norms, uvs, idx = [], [], [], []
    n = len(profile)
    pn = []
    for k in range(n):
        a = profile[max(k - 1, 0)]; b = profile[min(k + 1, n - 1)]
        dr, dy = b[0] - a[0], b[1] - a[1]
        l = math.hypot(dr, dy) or 1
        pn.append((dy / l, -dr / l))
    for i in range(seg + 1):
        a = 2 * math.pi * i / seg
        ca, sa = math.cos(a), math.sin(a)
        for k, (r, y) in enumerate(profile):
            verts.append((r * ca, y, r * sa)); norms.append((pn[k][0] * ca, pn[k][1], pn[k][0] * sa)); uvs.append((i / seg, k / (n - 1) * v_scale))
    for i in range(seg):
        for k in range(n - 1):
            p = i * n + k; q = p + n
            idx += [p, q, p + 1, p + 1, q, q + 1]
    m.add(mi, verts, norms, uvs, idx)


def build_board():
    top, nrm = board_textures()
    m = Mesh()
    seg = 192
    # top disc (fan), UVs map x / z across the 2 HALF square: u = (x + HALF) / 2 HALF, v = (z + HALF) / 2 HALF
    verts = [(0.0, 0.0, 0.0)]; uvs = [(0.5, 0.5)]
    for i in range(seg + 1):
        a = 2 * math.pi * i / seg
        x, z = R_BOARD * math.cos(a), R_BOARD * math.sin(a)
        verts.append((x, 0.0, z)); uvs.append(((x + HALF) / (2 * HALF), (z + HALF) / (2 * HALF)))
    idx = []
    for i in range(1, seg + 1):
        idx += [0, i + 1, i]   # counter-clockwise seen from +Y
    m.add(0, verts, [(0, 1, 0)] * len(verts), uvs, idx, [(1.0, 0.0, 0.0, 1.0)] * len(verts))
    # side band (slightly chamfered), malachite stripes running round
    lathe(m, 1, [(R_BOARD - 0.0005, 0.0), (R_BOARD, -0.0015), (R_BOARD, -T + 0.0015), (R_BOARD - 0.002, -T)], seg=seg, v_scale=1.0)
    # bottom
    verts = [(0.0, -T, 0.0)] + [((R_BOARD - 0.002) * math.cos(2 * math.pi * i / 64), -T, (R_BOARD - 0.002) * math.sin(2 * math.pi * i / 64)) for i in range(65)]
    idx = []
    for i in range(1, 65):
        idx += [0, i, i + 1]
    m.add(2, verts, [(0, -1, 0)] * len(verts), [(0.5, 0.5)] * len(verts), idx)
    # gilt rim line on the top edge
    torus(m, 3, R_BOARD - 0.0012, 0.0011, 0.0002, seg=seg, sides=8)
    side = band_texture(2048, 64, lambda i, j: i * 0.09 + fbm(i * 0.02, j * 0.08, 2) * 6 + j * 0.05, 0.75)
    tris = m.write("board", [
        mat("BoardTop", tex=0, rough=0.22, normal=1),
        mat("BoardSide", tex=2, rough=0.3),
        mat("BoardBottom", color=(0x1A, 0x2A, 0x1C), rough=0.8),
        mat("Gilt", color=GILT, rough=0.28, metal=1.0),
    ], [top, nrm, side], jpeg=(0, 2))
    assert tris <= 20000, tris
    return top


def build_tray():
    m = Mesh()
    r = TRAY_R
    prof = [(0.0, 0.003), (r - 0.007, 0.004), (r - 0.006, TRAY_H), (r, TRAY_H), (r, 0.004), (r - 0.004, 0.0), (0.0, 0.0)]
    lathe(m, 0, prof, seg=96)
    torus(m, 1, r - 0.003, 0.0022, TRAY_H + 0.0002, seg=96, sides=10)
    tex = band_texture(1024, 256, lambda i, j: j * 0.32 + fbm(i * 0.012, j * 0.02, 6) * 7, 0.85)
    m.write("tray", [mat("TrayMalachite", tex=0, rough=0.25), mat("Gilt", color=GILT, rough=0.28, metal=1.0)], [tex], jpeg=(0,))


def build_ball(k):
    m = Mesh()
    SEG, RINGS = 40, 20
    verts, norms, uvs, idx, tans = [], [], [], [], []
    for i in range(RINGS + 1):
        th = math.pi * i / RINGS
        for j in range(SEG + 1):
            ph = 2 * math.pi * j / SEG
            n = (math.sin(th) * math.cos(ph), math.cos(th), math.sin(th) * math.sin(ph))
            verts.append(tuple(BALL_R * c for c in n)); norms.append(n); uvs.append((j / SEG, i / RINGS))
            tans.append((-math.sin(ph), 0.0, math.cos(ph), 1.0))
    for i in range(RINGS):
        for j in range(SEG):
            a = i * (SEG + 1) + j; b = a + SEG + 1
            idx += [a, a + 1, b, a + 1, b + 1, b]
    m.add(0, verts, norms, uvs, idx, tans)
    return m.write("ball_%d" % k, [mat("Malachite%d" % k, tex=0, rough=0.12)], [ball_texture(k * 7 + 3)], jpeg=(0,))


def main():
    top = build_board()
    build_tray()
    for k in range(6):
        build_ball(k)
    if "--preview" in sys.argv:
        top.resize((1024, 1024), Image.LANCZOS).save(sys.argv[sys.argv.index("--preview") + 1])


if __name__ == "__main__":
    main()
