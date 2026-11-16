#!/usr/bin/env python3
"""Writes the launcher icon (an adaptive icon: background, foreground, monochrome) as vector drawables, from the
Mac app's design (~/Projects/keepass-bar scripts/make-icon.swift): the menu bar's PW key inside a ring with two
devices on it, on a blue gradient. Unlike the Mac's, the key leans 22.5° clockwise with the devices across it (the
owner, 2026-11-16). Re-run only when the design changes:  tools/make-icon.py"""
import math
from pathlib import Path

RES = Path(__file__).resolve().parent.parent / "app/src/main/res"

# The Mac draws in a 1024 space (y up) on an 824-wide squircle; here the squircle is the launcher's mask, about
# 72 of the 108 dp of an adaptive icon, so the ring (radius 322 + its devices) stays inside the 66 dp safe zone.
S = 72 / 824


def dp(x, y):
    """A point of the Mac's design space (y up, centre 512) in the 108 dp foreground (y down, centre 54)."""
    return 54 + (x - 512) * S, 54 - (y - 512) * S


def f(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


# The key on the menu bar icon's 18-point grid (y down), as the Mac's drawKey: a quarter turn, scaled by 28,
# then centred on the icon by the bounds of its stroked outline.
def key_point(gx, gy):
    return 28 * gy, 28 * gx  # the Mac's rotation and flip: grid (x, y) -> design (28y, 28x)


PW_WIDTH, BIT_WIDTH = 1.8 * 28, 1.35 * 28
# Bounds of the stroked key in design space: grid x 1.6..16.4, grid y 5.8..12.3 (the bow's arc reaches 9.2 + 3.1).
min_x, max_x = 28 * 5.8 - PW_WIDTH / 2, 28 * 12.3 + PW_WIDTH / 2
min_y, max_y = 28 * 1.6 - PW_WIDTH / 2, 28 * 16.4 + PW_WIDTH / 2
shift = (512 - (min_x + max_x) / 2, 512 - (min_y + max_y) / 2)


def kp(gx, gy):
    x, y = key_point(gx, gy)
    return dp(x + shift[0], y + shift[1])


def path_data(points, arc=None):
    out = []
    for i, (gx, gy) in enumerate(points):
        x, y = kp(gx, gy)
        out.append(("M" if i == 0 else "L") + f"{f(x)},{f(y)}")
    return " ".join(out)


# Shank (the P's stem), then the bow: down the left side, the half circle, up the right side.
shank = path_data([(1.6, 5.8), (16.4, 5.8)])
bow_start, bow_end = kp(10.2, 9.2), kp(16.4, 9.2)
radius = 3.1 * 28 * S
def make_bow():
    return (path_data([(10.2, 5.8), (10.2, 9.2)]) + f" A{f(radius)},{f(radius)} 0 0,{SWEEP} {f(bow_end[0])},{f(bow_end[1])}"
       + " " + "L" + ",".join(f(v) for v in kp(16.4, 5.8)))


bit = path_data([(2.3, 5.8), (3.7, 10.0), (5.1, 7.1), (6.5, 10.0), (7.9, 6.14)])

# The bow bulges away from the stem (to the right, as in a P). An SVG arc with sweep 1 turns clockwise (y down):
# from the bow's lower end to its upper end that bends left, into the stem; so sweep 0.
SWEEP = 0
mid = kp(13.3, 12.3)
assert mid[0] > max(bow_start[0], bow_end[0]) and bow_start[1] > bow_end[1], "the bow's ends are not where expected"
bow = make_bow()

# The key leans clockwise about the icon's centre, then moves so that its ink (not its bounds: the bow is heavier than
# the bit) is centred; upright, the bounds were centred, but turned they sit 3 dp high.
KEY_TURN = 22.5


def ink_centre():
    """The centre of the stroked key's ink, upright, found by sampling a 0.1 dp grid."""
    centre = ((bow_start[0] + bow_end[0]) / 2, (bow_start[1] + bow_end[1]) / 2)
    u = [(bow_start[i] - centre[i]) / radius for i in (0, 1)]
    v = [(mid[i] - centre[i]) / radius for i in (0, 1)]
    arc = [tuple(centre[i] + radius * (u[i] * math.cos(t) + v[i] * math.sin(t)) for i in (0, 1))
           for t in (math.pi * k / 36 for k in range(37))]
    lines = [([kp(1.6, 5.8), kp(16.4, 5.8)], PW_WIDTH),
             ([kp(10.2, 5.8)] + arc + [kp(16.4, 5.8)], PW_WIDTH),
             ([kp(*p) for p in ((2.3, 5.8), (3.7, 10.0), (5.1, 7.1), (6.5, 10.0), (7.9, 6.14))], BIT_WIDTH)]
    segments = [(a, b, width * S / 2) for points, width in lines for a, b in zip(points, points[1:])]

    def near(px, py, a, b, half):
        dx, dy = b[0] - a[0], b[1] - a[1]
        t = max(0.0, min(1.0, ((px - a[0]) * dx + (py - a[1]) * dy) / (dx * dx + dy * dy)))
        return math.hypot(px - a[0] - t * dx, py - a[1] - t * dy) <= half

    corners = [p for a, b, _ in segments for p in (a, b)]
    left, right = int(min(p[0] for p in corners) * 10) - 30, int(max(p[0] for p in corners) * 10) + 30
    top, bottom = int(min(p[1] for p in corners) * 10) - 30, int(max(p[1] for p in corners) * 10) + 30
    xs, ys, n = 0.0, 0.0, 0
    for i in range(left, right + 1):
        for j in range(top, bottom + 1):
            px, py = i / 10, j / 10
            if any(near(px, py, a, b, half) for a, b, half in segments):
                xs, ys, n = xs + px, ys + py, n + 1
    return xs / n, ys / n


def turned(x, y):
    c, s = math.cos(math.radians(KEY_TURN)), math.sin(math.radians(KEY_TURN))
    return 54 + (x - 54) * c - (y - 54) * s, 54 + (x - 54) * s + (y - 54) * c


key_shift = tuple(54 - v for v in turned(*ink_centre()))

# The two devices sit across the key: at right angles to it, the Mac's at 45° and 225° (counter-clockwise, y up).
RING_RADIUS = 322 * S
nodes = [dp(512 + 322 * math.cos(math.radians(a)), 512 + 322 * math.sin(math.radians(a)))
         for a in (-KEY_TURN, 180 - KEY_TURN)]


def circle(cx, cy, r):
    return f"M{f(cx - r)},{f(cy)} a{f(r)},{f(r)} 0 1,0 {f(2 * r)},0 a{f(r)},{f(r)} 0 1,0 {f(-2 * r)},0"


def stroke(data, color, width):
    return (f'    <path android:pathData="{data}" android:strokeColor="{color}" android:strokeWidth="{f(width * S)}"\n'
            f'        android:strokeLineCap="round" android:strokeLineJoin="round" />\n')


def fill(data, color):
    return f'    <path android:pathData="{data}" android:fillColor="{color}" />\n'


HEADER = ('<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated by tools/make-icon.py from the Mac app\'s icon design; '
          'do not edit by hand. -->\n')
VECTOR = ('<vector xmlns:android="http://schemas.android.com/apk/res/android"{extra}\n    android:width="108dp" '
          'android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n')


def foreground(monochrome: bool) -> str:
    ink = "#FF000000" if monochrome else None
    body = stroke(circle(54, 54, RING_RADIUS), ink or "#FFBFD4FF", 24)
    for x, y in nodes:
        body += fill(circle(x, y, 46 * S), ink or "#FFFFFFFF")
        if not monochrome:
            body += fill(circle(x, y, 19 * S), "#FF2F63E0")
    body += (f'    <group android:pivotX="54" android:pivotY="54" android:rotation="{f(KEY_TURN)}"\n'
             f'        android:translateX="{f(key_shift[0])}" android:translateY="{f(key_shift[1])}">\n')
    for data, width in ((shank, PW_WIDTH), (bow, PW_WIDTH), (bit, BIT_WIDTH)):
        body += stroke(data, ink or "#FFF4F8FF", width)
    body += "    </group>\n"
    return HEADER + VECTOR.format(extra="") + body + "</vector>\n"


background = HEADER + VECTOR.format(extra='\n    xmlns:aapt="http://schemas.android.com/aapt"') + '''    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="54" android:startY="0" android:endX="54" android:endY="108">
                <item android:offset="0" android:color="#FF5A93FF" />
                <item android:offset="0.5" android:color="#FF2F63E0" />
                <item android:offset="1" android:color="#FF16358C" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
'''

launcher = HEADER + '''<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
'''

(RES / "drawable/ic_launcher_background.xml").write_text(background)
(RES / "drawable/ic_launcher_foreground.xml").write_text(foreground(False))
(RES / "drawable/ic_launcher_monochrome.xml").write_text(foreground(True))
(RES / "mipmap-anydpi").mkdir(exist_ok=True)
(RES / "mipmap-anydpi/ic_launcher.xml").write_text(launcher)
print("wrote the launcher icon")
