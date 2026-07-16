#!/usr/bin/env python3
"""
Generates the 16x16 INVENTORY icons for the Power Monitor cover item, one
per tier, into src/main/resources/assets/powermonitor/textures/items/.

These are distinct from the block-atlas overlays produced by
gen_textures.py: the block overlay is a small transparent-backed CRT
readout layered onto GT's own casing texture on the placed cover face,
which would look like a tiny floating rectangle in an inventory slot. The
item icon is therefore a full composite -- a machine-casing-style plate
with an enlarged CRT screen and the tier's trace color (colors sampled
from the block overlay PNGs so the two stay visually consistent).

Registered by ItemPowerMonitorCover.registerIcons() as
"powermonitor:overlay_powermonitor_<tier>" (NO "blocks/" or "items/"
prefix -- the items atlas already resolves against textures/items/; see
the TEXTURE ATLAS RULE javadoc in ItemPowerMonitorCover).

Deterministic output: per-tier seeded noise, so re-running never produces
spurious diffs. Requires Pillow (pip install Pillow).
"""

from PIL import Image
import random
import os

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "src", "main", "resources", "assets", "powermonitor",
                       "textures", "items")
os.makedirs(OUT_DIR, exist_ok=True)

# Tier -> trace color, sampled from the actual block overlay PNGs.
TIERS = {
    "ulv": (150, 150, 150),
    "lv":  (200, 200, 60),
    "mv":  (80, 160, 220),
    "hv":  (220, 200, 60),
    "ev":  (220, 120, 40),
    "iv":  (220, 60, 60),
    "luv": (180, 60, 200),
    "zpm": (100, 220, 200),
    "uv":  (240, 240, 240),
}

FRAME  = (15, 15, 15, 255)     # CRT bezel -- same palette as the block overlay
SCREEN = (5, 12, 8, 255)       # dark green phosphor background
WHITE  = (255, 255, 255, 255)  # live-trace glint

BORDER = (60, 60, 60, 255)     # casing plate edge
FILL   = (139, 139, 139, 255)  # casing plate body
DARK   = (120, 120, 120, 255)  # casing noise
RIVET  = (85, 85, 85, 255)     # corner rivets

# Descending waveform across the screen interior, echoing the 9x7 original.
TRACE = [(2, 5), (3, 5), (4, 6), (5, 7), (6, 6), (7, 7),
         (8, 8), (9, 7), (10, 8), (11, 9), (12, 9)]


def main():
    for tier, tc in TIERS.items():
        img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
        px = img.load()

        # machine-casing plate
        for y in range(16):
            for x in range(16):
                px[x, y] = BORDER if (x in (0, 15) or y in (0, 15)) else FILL
        rnd = random.Random(tier)  # deterministic per-tier noise
        for y in range(1, 15):
            for x in range(1, 15):
                if rnd.random() < 0.18:
                    px[x, y] = DARK
        for (x, y) in [(1, 1), (14, 1), (1, 14), (14, 14)]:
            px[x, y] = RIVET

        # CRT screen (14x10), enlarged version of the cover overlay
        x0, y0, x1, y1 = 1, 3, 14, 12
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                px[x, y] = FRAME if (x in (x0, x1) or y in (y0, y1)) else SCREEN

        t = tc + (255,)
        for (x, y) in TRACE:
            px[x, y] = t
        px[13, 10] = WHITE

        out = os.path.join(OUT_DIR, f"overlay_powermonitor_{tier}.png")
        img.save(out)
        print("wrote", out)

    print(f"done: {len(TIERS)} item icons")


if __name__ == "__main__":
    main()
