---
navigation:
  title: "Generators"
  position: 40
---

# Generators

A generator's rating is voltage x 1 amp (32 EU/t for any LV generator). It
burns fuel to fill an internal buffer and emits whole packets from it.

**The refill loop is uncapped.** Every burn cycle consumes as much fuel as
needed to top the buffer. The practical consequence: a *fueled* generator
sustains its full rated output indefinitely -- including the emission toll
(see [Output Loss](07-output-loss.md)), which it silently pays **from fuel**,
not from the grid.

**Fuel is per-generator.** Four generators do not share a tank. Runway is a
staircase: full output until the first tank runs dry, a lower rate until the
next, and so on.

**Steam turbines** convert 7 L of steam into 3 EU at LV (the tooltip's
"85%" is this ratio against the ideal 2 L/EU). Full output demands a
continuous ~75 L/t per turbine -- your boiler bank and steam plumbing must
*sustain* that, or the turbine quietly under-produces while still looking
busy. A big steam tank between boilers and turbines acts as a battery:
its level trend tells you whether production is keeping up.

<!-- TODO scene: two turbines + semifluid pair feeding buffers -->

