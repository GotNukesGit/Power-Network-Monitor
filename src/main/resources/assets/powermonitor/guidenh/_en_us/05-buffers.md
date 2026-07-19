---
navigation:
  title: "Battery Buffers"
  parent: /index.md
  icon: 'minecraft:redstone_block'
  position: 50
---

# Battery Buffers

A battery buffer holds energy in two places: the **batteries** in its slots
-- the real reserve -- and the buffer block's own small <Color color="#7FFFFF"><Tooltip label="holding tank">The buffer BLOCK's internal store (2,048 EU per slot at LV) that packets flow through -- shown as 'tank' on the Power Monitor. Not your batteries.</Tooltip></Color>
(2,048 EU per slot at LV; this is the buffer's tank, not the machine buffers
from the [Machines](03-machines.md) chapter). Packets moving through the
buffer flow through the holding tank; the batteries only get involved at
thresholds.

**Amp rules:** output is **1A per battery installed**; charge intake is
2A per chargeable slot. A 4-slot buffer with 2 batteries outputs 2A --
partially filling buffers is a legitimate way to place symmetric buffers at
both ends of a machine line without over-building output you can't feed.

**Burst power on a small diet:** output amps don't care what generation
supplies. Eight batteries behind 4A of generation gives 8A of *burst*
delivery; the batteries cover (draw - generation) during peaks and refill
during lulls. Sustainable as long as the time-average draw stays under
generation:
<Latex formula="\text{burst endurance} \approx \frac{\text{battery EU}}{\text{peak draw} - \text{generation}}"/>

## <Color color="#FFAA00">The thermostat inside every battery buffer</Color>

The batteries don't trade EU continuously -- they wait for thresholds, like
a thermostat that won't fire the furnace over half a degree:

- Holding tank above **2/3** -> surplus pours into the batteries.
- Holding tank below **1/3** -> the batteries release just enough to refloat it.
- In the wide middle -- the <Color color="#7FFFFF"><Tooltip label="dead band">The deliberate no-action zone between 1/3 and 2/3. Without it the batteries would churn on every packet; with it they act only on real trends.</Tooltip></Color> -- **nothing moves, by design.**

On a healthy network with spare generation, the tank rides high and the
batteries stay genuinely full. But on a network whose supply *barely* covers
its burden, watch what unfolds, in order:

**First** the holding tank slowly sinks through the dead band. Your total
stored EU falls -- yet open the buffer and every battery is at a true 100%.
Nothing is wrong with the batteries; the decline is entirely the tank.

**Then** the tank starts hitting its 1/3 floor -- and from that point on,
**the batteries pay the shortfall.** Every tick the network comes up short,
the missing EU is taken from the batteries. It is never put back, because
refilling batteries takes spare power, and a short network has none. So the
batteries slowly, steadily empty.

The useful flip side: **how fast they empty tells you exactly how short you
are.** Batteries losing about 1,200 EU per minute means the network is
1 EU/t short -- that number is the size of the fix. One more generator, one
less relay stage, or one cable upgrade, and the same readout shows the
batteries refilling.

**Item charging is lossless.** Every EU leaving the buffer's ledger lands on
the battery's, verified in source. But the buffer's *emission* onto the wire
pays the universal toll -- see [Output Loss](07-output-loss.md).

