#!/usr/bin/env python3
"""
Generates placeholder 16x16 overlay textures for the Power Monitor cover,
one per tier. These are deliberately simple/functional placeholders (a dial
gauge motif, color-ramped by tier) -- not final art. GT covers render as a
casing texture (MACHINE_CASINGS[tier][0], supplied by GT itself) layered
with an overlay texture (this file) via TextureFactory.of(casing, overlay),
same pattern confirmed in MetaGeneratedItem02's registerCovers() this
session. So we only need to generate the overlay, not a full block texture.
"""

from PIL import Image, ImageDraw
import os

OUT_DIR = "/home/claude/powermonitor/src/main/resources/assets/powermonitor/textures/blocks"
os.makedirs(OUT_DIR, exist_ok=True)

# Tier -> accent color. Ascending brightness/saturation as a simple visual
# "more advanced" cue -- NOT verified against GT's own official tier color
# table (GTValues has tier colors used in tooltips; didn't cross-check this
# session). Swap these for GT's real tier colors if you want visual
# consistency with GT's own UI -- flagged as a follow-up, not blocking.
TIERS = [
    ("ULV", (120, 120, 120)),
    ("LV",  (200, 200, 80)),
    ("MV",  (80, 160, 220)),
    ("HV",  (220, 200, 60)),
    ("EV",  (200, 90, 90)),
    ("IV",  (120, 200, 160)),
    ("LuV", (170, 120, 220)),
    ("ZPM", (80, 220, 200)),
    ("UV",  (230, 230, 230)),
]

def draw_gauge(color):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # dark casing backing
    d.rectangle([1, 1, 14, 14], fill=(30, 30, 30, 255), outline=(10, 10, 10, 255))
    # gauge dial circle
    d.ellipse([3, 3, 12, 12], fill=(20, 20, 20, 255), outline=color)
    # needle
    d.line([7, 7, 10, 4], fill=color, width=1)
    # center pin
    d.point((7, 7), fill=(255, 255, 255, 255))
    # small tick marks
    for tx, ty in [(3, 3), (12, 3), (3, 12), (12, 12)]:
        d.point((tx, ty), fill=color)
    return img

for name, color in TIERS:
    img = draw_gauge(color)
    path = os.path.join(OUT_DIR, f"overlay_powermonitor_{name.lower()}.png")
    img.save(path, "PNG")
    print(f"  {path}")
