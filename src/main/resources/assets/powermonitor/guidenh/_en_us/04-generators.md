---
navigation:
  title: "Generators"
  parent: /index.md
  icon: 'minecraft:coal'
  position: 60
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

**Steam turbines** burn steam in gulps: **7 L in, 3 EU out.** Perfect
conversion would need only 6 L for those 3 EU, so one liter in every seven
is wasted -- that's the tooltip's "85%" (6/7). At full output the gulps add
up to about **75 L/t, every tick, per turbine**, and your boilers and pipes
must *sustain* that rate: a turbine fed 70 L/t doesn't complain -- it
quietly makes less than 32 EU/t while looking perfectly busy. A big steam
tank between boilers and turbines behaves like a battery. Watch its level:
rising means the boilers are winning; falling means the turbines are living
off reserves.

