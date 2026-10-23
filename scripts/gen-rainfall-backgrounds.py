#!/usr/bin/env python3
# Copyright (C) 2026 Stephen de la Vega
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generate the three procedural Rainfall backgrounds as PNG (standard library only).

Usage: python3 -I scripts/gen-rainfall-backgrounds.py OUT_DIR
Then convert each PNG: cwebp -q 80 in.png -o out.webp
"""
import math
import os
import random
import struct
import sys
import zlib

W, H = 1200, 1920


def clamp01(x):
    return 0.0 if x < 0.0 else 1.0 if x > 1.0 else x


def smoothstep(a, b, x):
    t = clamp01((x - a) / (b - a))
    return t * t * (3.0 - 2.0 * t)


def lerp(a, b, t):
    return a + (b - a) * t


def ramp(stops, t):
    """Piecewise-linear colour ramp; stops is [(pos, (r, g, b)), ...] in 0..1."""
    t = clamp01(t)
    for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
        if t <= p1:
            u = 0.0 if p1 == p0 else (t - p0) / (p1 - p0)
            u = u * u * (3.0 - 2.0 * u)
            return tuple(lerp(c0[i], c1[i], u) for i in range(3))
    return stops[-1][1]


class Canvas:
    def __init__(self):
        self.px = [[0.0] * (W * 3) for _ in range(H)]

    def fill_rows(self, fn):
        for y in range(H):
            r, g, b = fn(y / (H - 1))
            row = self.px[y]
            for x in range(W):
                i = x * 3
                row[i], row[i + 1], row[i + 2] = r, g, b

    def blob(self, cx, cy, radius, color, strength, softness=0.55):
        """Additive soft disc (bokeh): flat core with a smooth falloff edge."""
        x0, x1 = max(0, int(cx - radius)), min(W, int(cx + radius) + 1)
        y0, y1 = max(0, int(cy - radius)), min(H, int(cy + radius) + 1)
        inner = radius * (1.0 - softness)
        for y in range(y0, y1):
            row = self.px[y]
            dy = y - cy
            for x in range(x0, x1):
                d = math.sqrt((x - cx) ** 2 + dy * dy)
                if d >= radius:
                    continue
                a = strength * (1.0 - smoothstep(inner, radius, d))
                i = x * 3
                row[i] += color[0] * a
                row[i + 1] += color[1] * a
                row[i + 2] += color[2] * a

    def vignette(self, amount):
        for y in range(H):
            row = self.px[y]
            ny = (y / (H - 1)) * 2 - 1
            for x in range(W):
                nx = (x / (W - 1)) * 2 - 1
                k = 1.0 - amount * (nx * nx * 0.6 + ny * ny * 0.4)
                i = x * 3
                row[i] *= k
                row[i + 1] *= k
                row[i + 2] *= k

    def write_png(self, path, seed):
        rng = random.Random(seed)
        raw = bytearray()
        for y in range(H):
            raw.append(0)
            row = self.px[y]
            for v in row:
                # Tiny dither to hide banding in the smooth gradients.
                q = int(v + rng.random() - 0.5 + 0.5)
                raw.append(0 if q < 0 else 255 if q > 255 else q)

        def chunk(tag, data):
            body = tag + data
            return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

        png = b"\x89PNG\r\n\x1a\n"
        png += chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 2, 0, 0, 0))
        png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        png += chunk(b"IEND", b"")
        with open(path, "wb") as f:
            f.write(png)


def dusk(path):
    c = Canvas()
    stops = [
        (0.00, (24, 22, 64)),
        (0.35, (82, 52, 116)),
        (0.62, (196, 92, 118)),
        (0.80, (246, 156, 98)),
        (1.00, (60, 36, 70)),
    ]
    c.fill_rows(lambda t: ramp(stops, t))
    rng = random.Random(7)
    palette = [(255, 190, 120), (255, 120, 150), (190, 140, 255), (255, 220, 170)]
    for _ in range(26):
        c.blob(rng.uniform(0, W), rng.uniform(H * 0.35, H * 0.95),
               rng.uniform(40, 150), rng.choice(palette), rng.uniform(0.10, 0.28))
    c.vignette(0.35)
    c.write_png(path, 101)


def night_city(path):
    c = Canvas()
    stops = [(0.0, (6, 10, 28)), (0.55, (14, 20, 46)), (1.0, (26, 18, 40))]
    c.fill_rows(lambda t: ramp(stops, t))
    rng = random.Random(21)
    palette = [(255, 200, 110), (255, 90, 120), (90, 200, 255), (140, 255, 190),
               (255, 255, 230), (200, 120, 255)]
    # Dense lights low in the frame, sparser higher up.
    for _ in range(70):
        y = H * (1.0 - rng.random() ** 1.8 * 0.7)
        c.blob(rng.uniform(0, W), y, rng.uniform(18, 80), rng.choice(palette),
               rng.uniform(0.16, 0.5), 0.35)
    for _ in range(8):
        c.blob(rng.uniform(0, W), rng.uniform(H * 0.5, H), rng.uniform(100, 190),
               rng.choice(palette), rng.uniform(0.05, 0.12), 0.7)
    c.vignette(0.45)
    c.write_png(path, 202)


def forest(path):
    c = Canvas()
    stops = [(0.0, (150, 172, 164)), (0.5, (118, 148, 134)), (1.0, (44, 66, 58))]
    c.fill_rows(lambda t: ramp(stops, t))
    rng = random.Random(33)
    mist = (206, 222, 214)
    layers = [(0.46, 0.80, (96, 126, 112)), (0.58, 0.62, (66, 98, 84)),
              (0.70, 0.40, (40, 70, 58)), (0.84, 0.18, (22, 44, 36))]
    for base, haze, col in layers:
        phases = [rng.uniform(0, 6.28) for _ in range(4)]
        # Jagged conifer-like ridge: low-frequency swell plus sharp tooth term.
        heights = []
        for x in range(W):
            swell = (math.sin(x * 0.004 + phases[0]) * 70 + math.sin(x * 0.011 + phases[1]) * 36)
            tooth = abs(((x * 0.045 + phases[2]) % 2.0) - 1.0) * 46 + \
                abs(((x * 0.113 + phases[3]) % 2.0) - 1.0) * 16
            heights.append(H * base + swell - tooth)
        for y in range(H):
            row = c.px[y]
            for x in range(W):
                a = smoothstep(-6.0, 6.0, y - heights[x])
                if a <= 0.0:
                    continue
                depth = (y - heights[x]) / (H * 0.25)
                h = clamp01(haze - depth * 0.5)
                i = x * 3
                for k in range(3):
                    tree = lerp(col[k], mist[k], h)
                    row[i + k] = lerp(row[i + k], tree, a)
    for _ in range(14):
        c.blob(rng.uniform(0, W), rng.uniform(H * 0.1, H * 0.9), rng.uniform(80, 220),
               mist, rng.uniform(0.04, 0.10), 0.9)
    c.vignette(0.3)
    c.write_png(path, 303)


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: gen-rainfall-backgrounds.py OUT_DIR")
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    for name, fn in (("dusk-bokeh", dusk), ("night-city", night_city), ("misty-forest", forest)):
        fn(os.path.join(out, name + ".png"))
        print("wrote", name + ".png")


if __name__ == "__main__":
    main()
