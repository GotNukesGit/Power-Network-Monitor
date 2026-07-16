#!/usr/bin/env python3
"""
Generates ALL Power Monitor cover art, one set per tier:

  textures/blocks/overlay_powermonitor_<tier>.png
      Full-face animated CRT readout: 8 frames stacked vertically
      (16x128 sprite sheet). A sibling .png.mcmeta animation descriptor
      is written alongside -- Forge's stitcher detects the stacked sheet
      and animates it automatically; without the .mcmeta a non-square
      texture fails to stitch ("broken aspect ratio").

  textures/items/overlay_powermonitor_<tier>.png
      Static inventory icon == frame 0 of the block animation, so the
      NEI/hotbar icon exactly matches the placed cover face.

Registered names are prefix-free ("powermonitor:overlay_powermonitor_x"):
each atlas resolves against its own base directory (textures/blocks/ or
textures/items/). See the ATLAS PATH RULE javadoc in PowerMonitorIcons --
do NOT put "blocks/" or "items/" inside the registered name.

Art: 1px dark bezel, dark-green phosphor screen with faint gridlines, a
scrolling sine waveform in the tier's accent color with a dimmed
connecting trace, and a white beam draw-point sweeping with the phase.
Deterministic output -- re-running never produces spurious diffs.
Requires Pillow (pip install Pillow).
"""

from PIL import Image
import math
import os

BASE = os.path.dirname(os.path.abspath(__file__))
BLOCKS_DIR = os.path.join(BASE, "src", "main", "resources", "assets",
                          "powermonitor", "textures", "blocks")
ITEMS_DIR = os.path.join(BASE, "src", "main", "resources", "assets",
                         "powermonitor", "textures", "items")
os.makedirs(BLOCKS_DIR, exist_ok=True)
os.makedirs(ITEMS_DIR, exist_ok=True)

# Tier -> waveform accent color.
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

FRAME_C = (15, 15, 15, 255)    # CRT bezel
SCREEN = (5, 12, 8, 255)       # phosphor background
GRID = (10, 22, 14, 255)       # faint gridlines
WHITE = (255, 255, 255, 255)   # beam draw-point

NFRAMES = 8
PERIOD = 14.0
AMP = 3.0
MID = 7
FRAMETIME = 3  # ticks per frame

MCMETA = '{\n  "animation": {\n    "frametime": %d\n  }\n}\n' % FRAMETIME


def draw_frame(tc, phase):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for y in range(16):
        for x in range(16):
            px[x, y] = FRAME_C if (x in (0, 15) or y in (0, 15)) else SCREEN
    for gy in (4, 8, 11):
        for x in range(1, 15):
            px[x, gy] = GRID
    t = tc + (255,)
    dim = tuple(c // 2 for c in tc) + (255,)
    prev_y = None
    for x in range(1, 15):
        y = MID + AMP * math.sin(2 * math.pi * ((x + phase * 2) / PERIOD))
        y = max(2, min(13, int(round(y))))
        px[x, y] = t
        if prev_y is not None and abs(y - prev_y) > 1:
            step = 1 if y > prev_y else -1
            for yy in range(prev_y + step, y, step):
                px[x, yy] = dim
        prev_y = y
    bx = 1 + (phase * 2) % 14
    by = MID + AMP * math.sin(2 * math.pi * ((bx + phase * 2) / PERIOD))
    px[int(bx), max(2, min(13, int(round(by))))] = WHITE
    return img


def main():
    for tier, tc in TIERS.items():
        sheet = Image.new("RGBA", (16, 16 * NFRAMES), (0, 0, 0, 0))
        for f in range(NFRAMES):
            sheet.paste(draw_frame(tc, f), (0, 16 * f))
        block_png = os.path.join(BLOCKS_DIR, f"overlay_powermonitor_{tier}.png")
        sheet.save(block_png)
        with open(block_png + ".mcmeta", "w", newline="\n") as m:
            m.write(MCMETA)
        draw_frame(tc, 0).save(
            os.path.join(ITEMS_DIR, f"overlay_powermonitor_{tier}.png"))
        print("wrote", tier)
    print(f"done: {len(TIERS)} tiers (animated block sheet + mcmeta + item icon each)")


if __name__ == "__main__":
    main()
