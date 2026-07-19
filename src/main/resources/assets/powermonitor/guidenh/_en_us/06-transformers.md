---
navigation:
  title: "Transformers"
  parent: /index.md
  icon: 'minecraft:comparator'
  position: 40
---

# Transformers

A transformer converts between adjacent tiers, **energy-conserving** -- the
conversion itself is exactly 1:1 (its *emission* pays the standard toll like
every other emitter).

**Faces:** one face carries a single large dot -- that face is the
high-voltage side, always.

**Modes** (toggled with a soft mallet -- it's the same allowed-to-work flag
as machines):
- **Step-down** (default): draws 1A of the higher tier, outputs up to 4A of
  the lower.
- **Step-up:** accepts up to 4 low-tier amps (the tooltip's number; it will
  actually take one extra to keep its buffer fed), emits **1A of the higher
  tier** -- which makes a single step-up transformer a <Color color="#55FFFF"><Tooltip label="hard ceiling">A step-up transformer emits exactly 1 amp of the higher tier -- 128 EU/t for LV/MV -- no matter how much low-tier power feeds it. Parallel transformers raise the ceiling.</Tooltip></Color> of one
  high-tier packet per tick on everything behind it.

That output ceiling is the number to check when a step-up network feels
starved: one LV/MV transformer can never deliver more than 128 EU/t to the
MV side, no matter what feeds it.

Hi-Amp transformers (4A high side, 16A low side) exist; check NEI for when
their recipes become available to you.

<!-- TODO scene: the LV/MV step-up rig -->

