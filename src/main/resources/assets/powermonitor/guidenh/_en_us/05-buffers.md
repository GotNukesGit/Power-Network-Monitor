---
navigation:
  title: "Battery Buffers"
  position: 50
---

# Battery Buffers

A battery buffer is two stores wearing one GUI:

- **The batteries** -- the real reserve.
- **A small internal pass-through buffer** (tier V x 64 per slot) -- the
  operating fluid that packets actually move through.

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

**The hysteresis band.** Batteries only *charge* when the internal buffer is
above 2/3 full, and only *discharge* to rescue it below 1/3. Between those
thresholds the batteries sit untouched while the internal level wanders.
Under a razor-thin margin this becomes a **ratchet**: the internal level
walks down, the batteries top it up at each floor-touch, and never refill --
your batteries step down slowly while every one of them reads about full.
That pattern is not a leak. It is a precision measurement of a margin near
zero.

**Item charging is lossless.** Every EU leaving the buffer's ledger lands on
the battery's, verified in source. But the buffer's *emission* onto the wire
pays the universal toll -- see [Output Loss](07-output-loss.md). A buffer is
a ~3% tax on everything passing through it at LV.

