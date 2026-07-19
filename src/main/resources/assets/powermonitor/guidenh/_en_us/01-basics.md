---
navigation:
  title: "The Packet Model"
  parent: /index.md
  position: 10
---

# The Packet Model: Volts and Amps

GT power is not a fluid. It moves in **packets**: each packet holds V EU
(the *voltage*), and <Tooltip label="amperage">Packets per tick. One whole 32 EU packet arriving each tick is one LV amp -- packets never split.</Tooltip> is how many packets pass per tick.

- An LV generator emits 1 packet of 32 EU per tick: **32 EU/t at 1A**.
- Four LV generators on one cable: 32V at 4A = 128 EU/t. This is **not** MV.
  MV means *bigger packets* (128 EU each), not more of them.
- Packets are indivisible. A machine that needs 20 EU/t still takes a whole
  32 EU packet when its buffer has room (the rest lands in its buffer).

<Latex formula="P = V \times A"/>

Voltage decides **tier compatibility** (and what explodes -- see
[Machines](03-machines.md)). Amperage decides **throughput**. Almost every
sizing question in GT reduces to: *how many packets per tick does this line
need to carry, and how many can it?*

Everything else in this guide is bookkeeping on packets: cables shave EU off
each packet in transit, emitters pay a toll to launch them, and machines
buffer them.
