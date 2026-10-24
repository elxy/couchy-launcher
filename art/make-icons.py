#!/usr/bin/env python3
"""Regenerate every launcher bitmap from art/couchy-launcher.svg.

The couch artwork has one source (art/couchy-launcher.svg, Twemoji, MIT). Every
bitmap below is derived from it: legacy launcher icons per density, the
adaptive-icon foreground layer, and the store listing images. Run this after
editing the artwork:

    python3 art/make-icons.py

Needs python3 and rsvg-convert (librsvg2-bin). The output is committed, so a
normal Gradle build never runs this.
"""

import os
import re
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ART = ROOT / "art"
RES = ROOT / "app/src/main/res"
STORE = ROOT / "fastlane/metadata/android/en-US/images"

# Sky gradient, matching the top-to-bottom order of the old app_icon.xml:
# angle 270 means the start colour sits at the top.
SKY = """<defs>
    <linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#1E3A5F"/>
      <stop offset="0.5" stop-color="#3E78AE"/>
      <stop offset="1" stop-color="#9BD0F0"/>
    </linearGradient>
  </defs>"""

# Artwork geometry, in fractions of the canvas edge. 2/3 is what the old
# layer-list used: a 72dp couch on a 108dp icon.
ART_SCALE = 2 / 3

# Adaptive icons are 108dp with a 72dp visible square and a 66dp "guaranteed
# visible" circle. Artwork at 2/3 would be clipped by round masks, so the
# foreground layer is drawn a little smaller than the legacy bitmap.
ADAPTIVE_SCALE = 0.58


def couch(x, y, w, h):
    return (
        f'<svg x="{x}" y="{y}" width="{w}" height="{h}" viewBox="0 0 36 36" '
        f'preserveAspectRatio="xMidYMid meet">{COUCH}</svg>'
    )


def artwork(edge, scale=ART_SCALE):
    """Square canvas of `edge` units, artwork centred at `scale`."""
    pad = edge * (1 - scale) / 2
    return couch(pad, pad, edge * scale, edge * scale)


def svg(w, h, body):
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" '
        f'viewBox="0 0 {w} {h}">\n{SKY}\n{body}\n</svg>\n'
    )


def legacy_icon(size):
    return svg(size, size, f'<rect width="{size}" height="{size}" fill="url(#sky)"/>\n{artwork(size)}')


def round_icon(size):
    """Same artwork, clipped to a circle so launchers that do not mask still look right."""
    r = size / 2
    return svg(
        size,
        size,
        f'<defs><clipPath id="round"><circle cx="{r}" cy="{r}" r="{r}"/></clipPath></defs>\n'
        f'<g clip-path="url(#round)"><rect width="{size}" height="{size}" fill="url(#sky)"/>\n'
        f"{artwork(size)}</g>",
    )


def foreground(size):
    """Adaptive-icon foreground: transparent, artwork inside the 66dp safe circle."""
    return svg(size, size, artwork(size, ADAPTIVE_SCALE))


def banner(w, h):
    # 104dp couch on the 180dp-tall banner, as in the old app_banner.xml.
    return svg(w, h, f'<rect width="{w}" height="{h}" fill="url(#sky)"/>\n{couch(w / 2 - h * 52 / 180, h * 38 / 180, h * 104 / 180, h * 104 / 180)}')


def render(svg_text, out_path, w, h):
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", suffix=".svg", delete=False) as f:
        f.write(svg_text)
        tmp = f.name
    try:
        subprocess.run(
            ["rsvg-convert", "-w", str(w), "-h", str(h), "-o", str(out_path), tmp],
            check=True,
        )
    finally:
        os.unlink(tmp)
    print(f"{out_path.relative_to(ROOT)}  {w}x{h}")


COUCH = re.search(
    r"<svg[^>]*>(.*)</svg>",
    (ART / "couchy-launcher.svg").read_text(encoding="utf-8"),
    re.S,
).group(1).strip()

# 48dp / 108dp bases, in the densities Android ships.
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

for density, scale in DENSITIES.items():
    d = RES / f"mipmap-{density}"
    legacy = round(48 * scale)
    fg = round(108 * scale)
    render(legacy_icon(legacy), d / "ic_launcher.png", legacy, legacy)
    render(round_icon(legacy), d / "ic_launcher_round.png", legacy, legacy)
    render(foreground(fg), d / "ic_launcher_foreground.png", fg, fg)

# Store listings: Play wants 512x512 icon and a 1280x720 TV banner; the
# home-screen banner is 320x180.
render(legacy_icon(512), STORE / "icon.png", 512, 512)
render(banner(1280, 720), STORE / "tvBanner.png", 1280, 720)
