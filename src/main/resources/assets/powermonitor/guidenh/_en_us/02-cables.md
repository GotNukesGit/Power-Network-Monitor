---
navigation:
  title: "Cables"
  parent: /index.md
  position: 20
---

# Cables: Ratings, Loss, and Fire

A cable's tooltip states three numbers. All three are per-packet rules:

**Max voltage** -- the biggest packet it accepts. One packet over and the
cable catches fire. In a mixed run, only the segments *rated below* the
offending voltage burn: the run fails at its weakest links.

**Max amperage** -- packets per tick, per size. Sizes multiply the base:
a 4x cable of a 1A material carries 4A. Amps are tracked in a **rolling
~2 second window**, so brief spikes above rating are tolerated; *sustained*
overdraw sets the under-rated segments on fire. (This is why a transformer's
burst-pulls don't burn a backbone that averages under its rating.)

**Loss per meter per amp** -- each packet loses this much EU per cable block
it crosses. The packet *shrinks in transit*: what matters downstream is what
arrives, not what was sent.

## The rule that follows

<Latex formula="\text{deliverable} = \text{rating} - (\text{blocks} \times \text{loss} \times \text{amps})"/>

The nameplate is an <Tooltip label="injection ceiling" tooltip="The most EU/t a source can PUT ONTO this cable -- what arrives downstream is always less by the path loss"/>. Nobody downstream ever receives
the nameplate.

## Connections

Cables of *different materials* connect and conduct -- the game routes by the
live connection state, not by matching materials. Wire cutters sever a
connection; a soldering iron restores it; colored insulation isolates
different colors from each other. If two cables render as separate stubs
butting together, no power crosses.

See [Reference Tables](99-tables.md) for every ULV-MV cable's verified stats.

<!-- TODO scene: mixed-material junction (lossless run into tin 4x) -->
