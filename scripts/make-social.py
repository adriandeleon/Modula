#!/usr/bin/env python3
"""
Generates branding/social-preview.png — the 1280x640 card GitHub shows when the repo is linked.

Run from the project root:  python3 scripts/make-social.py   (needs Pillow)

The mark and the palette come from make-icon.py, so the card cannot drift from the icon.

GitHub has no API for the social preview: it is uploaded through Settings -> Social preview in the
web UI. This writes the file; attaching it is one manual step.
"""

import importlib.util
import math
import os

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

_spec = importlib.util.spec_from_file_location("make_icon", os.path.join(ROOT, "scripts", "make-icon.py"))
icon = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(icon)

W, H = 1280, 640
FONTS = os.path.join(ROOT, "src", "main", "resources", "com", "modula", "fonts")

INK = (0xF2, 0xED, 0xE4)
INK_DIM = (0x9A, 0xA0, 0xAC)
INK_FAINT = (0x5C, 0x63, 0x71)

TAGLINE = "Commercial AM/FM radio for an RTL-SDR dongle"
DETAIL = "Stereo  ·  RDS  ·  seek  ·  presets  ·  recording"

MARGIN = 104  # GitHub crops this card to several aspect ratios, so nothing sits near an edge


def font(name, size):
    return ImageFont.truetype(os.path.join(FONTS, name), size)


def fitted(draw, text, name, ideal, available):
    """
    The largest size at or below `ideal` that fits `available` pixels.

    Measured rather than hand-tuned: a number chosen by eye holds until the wording changes, and
    the failure is silent — the text simply runs off the edge of the card.
    """
    for size in range(ideal, 9, -1):
        f = font(name, size)
        if draw.textlength(text, font=f) <= available:
            return f
    return font(name, 10)


def spectrum(d, y_base, left, right, height=88):
    """
    A silhouette of the app's own display, as texture.

    Deliberately ink only, with no amber centre marker. In the interface that marker is the one
    place the accent leaves the dial — but here the mark is already the amber spend, and a second
    amber element would make the card a panel of equals, which is the failure the kit rules out.
    """
    # Deterministic humps at channel-ish positions: regeneration must produce the same card.
    peaks = [(0.06, 0.30), (0.14, 0.52), (0.23, 0.20), (0.31, 0.74), (0.40, 0.35),
             (0.50, 0.95), (0.60, 0.28), (0.68, 0.61), (0.77, 0.24), (0.86, 0.44), (0.94, 0.18)]
    span = right - left
    points = []
    for x in range(left, right + 1):
        t = (x - left) / span
        v = 0.0
        for centre, amplitude in peaks:
            v += amplitude * math.exp(-((t - centre) ** 2) / (2 * 0.017 ** 2))
        v += 0.06  # a noise floor, so the trace never touches the baseline
        points.append((x, y_base - min(v, 1.0) * height))
    d.line(points, fill=INK_FAINT, width=3, joint="curve")


def build():
    img = Image.new("RGB", (W, H), icon.CABIN)
    d = ImageDraw.Draw(img)

    # The cabin, lit from where the mark sits rather than from the centre of the card.
    cx, cy = W * 0.26, H * 0.44
    for r in range(int(W * 0.85), 0, -2):
        t = r / (W * 0.85)
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=icon.blend(icon.SURF, icon.CABIN, (1 - t) ** 1.7))

    # The display's own trace, spanning the full width beneath everything as texture — ink only.
    spectrum(d, H - MARGIN + 26, MARGIN, W - MARGIN)
    d.line([(MARGIN, H - MARGIN + 26), (W - MARGIN, H - MARGIN + 26)], fill=(0x1D, 0x20, 0x25), width=2)

    mark_size = 236
    mark = icon.render(mark_size, tile=False, rings=(1, 2))
    mark_y = 196
    img.paste(mark, (MARGIN + 6, mark_y), mark)

    x = MARGIN + mark_size + 62
    available = W - MARGIN - x

    name_font = fitted(d, "Modula", "IBMPlexMono-Bold.ttf", 100, available)
    tag_font = fitted(d, TAGLINE, "IBMPlexMono-Regular.ttf", 29, available)
    detail_font = fitted(d, DETAIL, "IBMPlexMono-Regular.ttf", 23, available)

    d.text((x, mark_y + 4), "Modula", font=name_font, fill=INK)
    d.text((x + 3, mark_y + 124), TAGLINE, font=tag_font, fill=INK_DIM)
    d.text((x + 3, mark_y + 172), DETAIL, font=detail_font, fill=INK_FAINT)

    return img


if __name__ == "__main__":
    out = os.path.join(ROOT, "branding", "social-preview.png")
    build().save(out, optimize=True)
    print("wrote %s (%d KB)" % (os.path.relpath(out, ROOT), os.path.getsize(out) // 1024))
