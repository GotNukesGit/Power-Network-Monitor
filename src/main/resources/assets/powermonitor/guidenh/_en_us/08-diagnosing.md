---
navigation:
  title: "Diagnosing Your Network"
  position: 80
---

# Diagnosing Your Network

The questions, in the order to ask them:

**Is demand being met?** Demand (what recipes want) vs delivered (what
machines receive). Equal within a couple EU/t: healthy. A persistent gap is
one of exactly three things: not enough generation, not enough *transit*
(an under-amped segment), or the emission toll eating a zero-margin system.

**Are the generators pinned?** At 100% with unmet demand: add generation.
*Below* 100% with unmet demand: the power exists but can't get there --
find the segment whose amps are the bottleneck.

**Is storage trending?** Batteries draining slowly with everything "fine" is
a margin measurement: your burden (load + cable loss + relay tolls) exceeds
supply by exactly the drain rate. Do the ledger before adding batteries --
batteries buy time, never balance.

**Duty cycle matters.** Loads that pulse can average under your generation
while peaking over it -- storage smooths the peaks and bleeds the average.
Size generation for the *average*, storage for the *peaks*.

**Startup is not brownout.** Demand appears in one tick when a recipe
starts; generation ramps over seconds. A few seconds of shortfall on recipe
start is spin-up, not failure -- persistent shortfall is real.

The Power Network Monitor cover reads all of the above live from any cable:
demand vs delivered with amps, line loss split from output loss, per-fluid
fuel reserves with trends, battery-level integrators, and an outage log with
the numbers at the worst moment. This guide exists because that instrument
kept being right.

