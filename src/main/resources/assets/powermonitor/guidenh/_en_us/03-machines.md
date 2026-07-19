---
navigation:
  title: "Machines as Loads"
  parent: /index.md
  position: 30
---

# Machines as Loads

**Demand comes from recipes, not machines.** An idle machine draws nothing.
A machine with an active recipe wants its recipe's EU/t -- and it reports
that demand even while starving.

**Every machine has an internal buffer** (tier voltage x 64: 2,048 EU at LV).
It draws from the buffer; the network refills the buffer in whole packets.
This smooths the mismatch between packet sizes and recipe draws.

**Amps a machine accepts:**
<Latex formula="\text{maxAmpsIn} = \frac{2 \times \text{recipe EU/t}}{V_{tier}} + 1"/>
For ordinary recipes that's 2A -- which is why one machine can't hog a line.

**Starvation:** a machine that can't drain its recipe's EU/t <Tooltip label="stutters">Progress freezes (parked at -100 internally); the machine looks idle but still reports its full recipe demand.</Tooltip> --
progress freezes (internally the progress counter is parked at -100) and it
sips power without advancing. It looks idle. It is not idle: it still demands.

**The power switch and the soft mallet are the same flag**, and its behavior
is subtler than it looks:
- A *healthy* machine that you switch off **finishes its current recipe**,
  then won't start another.
- A *starved* machine that you switch off **freezes hard** -- no draw, no
  progress. This is the correct move when your grid is browning out: switch
  off the starved machines, let the buffers refill, switch back on.

**Overvoltage: an LV machine on a powered MV line explodes.** Immediately,
with a log entry in Explosion.log. Rain on an exposed machine can also
explode it (roof your machines), fire adjacent to a machine can detonate it,
and water is verified harmless.
