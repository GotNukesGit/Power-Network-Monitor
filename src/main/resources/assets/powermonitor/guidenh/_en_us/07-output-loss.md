---
navigation:
  title: "Output Loss"
  parent: /index.md
  position: 70
---

# Output Loss: The Hidden Toll

Power never moves for free in GT. Cables charge per meter -- but even a
lossless cable can't make transfer free, because **every emitter charges
admission**: any block that puts EU onto a wire pays a toll to do it:

<Latex formula="\text{paid} = V + 2^{\max(0,\ tier-1)} \quad\text{per amp, to emit } V"/>

| Tier | Emits | Pays | Toll | As a share |
|------|-------|------|------|------------|
| ULV  | 8     | 9    | 1    | 12.5%      |
| LV   | 32    | 33   | 1    | 3.1%       |
| MV   | 128   | 130  | 2    | 1.6%       |

The toll's *percentage shrinks* as you tier up -- one more quiet reward for
voltage upgrades. It is charged on **every emission**: generators launching
packets, battery buffers re-emitting them, transformers converting them.
Three emitters in a chain = three tolls, *compounding*.

<Mermaid>
mindmap
  root((Every EU generated))
    Delivered to machines
    Cable loss
      per block, per amp
    Output loss
      Generators
        paid from fuel -- invisible
      Relays
        buffers and transformers
        paid from storage -- your batteries
</Mermaid>

## Who actually pays

**Generators pay from fuel.** Their burn loop refills the internal buffer
without limit, so a fueled generator delivers its full rated output to the
grid and the 33rd EU per amp quietly costs extra fuel. You never see it on
the wire.

**Relays pay from storage.** Battery buffers and transformers have no fuel
line. Their toll comes out of the energy transiting them -- and when the
network has no generation headroom, out of the batteries.

## A real ledger

A field rig that drained its batteries ~2 EU/t despite apparently sufficient
generation. Four LV generators, two battery buffers, lossless 4A backbone,
LV/MV step-up transformer, two copper cables, one MV extruder at 120 EU/t:

| Stage | Receives | Puts on wire | Pays | Lost here |
|-------|----------|--------------|------|-----------|
| 4x LV generators | (fuel: 132) | 128.0 | 132.0 | 4.0 -- from fuel |
| 2x battery buffers | 128.0 | 125.8 | 129.7 | 3.9 -- **from batteries** |
| LV/MV transformer | 125.8 | 123.9 | 125.8 | 1.9 -- from transit |
| 2x copper MV cable | 123.9 | 120.0 | -- | 3.9 -- in the wire |
| Extruder | **120.0** | | | |

Buffers received 128 and paid out 129.7. The missing **~1.7 EU/t came from
the batteries, for as long as the load ran** -- a structural drain no cable
upgrade could fix, measured as a slow battery decline weeks before the
mechanic was identified.

## The chain rule

<Latex formula="\text{required source} \approx \text{load} \times \tfrac{33}{32}^{(\text{LV relays})} \times \tfrac{130}{128}^{(\text{MV relays})} + \text{cable loss}"/>

Every LV relay stage taxes ~3.1% of everything passing through it; every MV
stage ~1.6%. "Add a battery buffer for safety" is never free. The cures are
headroom (one extra generator drowns the toll) or **fewer emitter stages**
-- every relay you remove from a chain refunds its toll permanently.

## Scene: the ledger rig

*(Block IDs to be filled via /guidenhc editor -- they autocomplete.)*

<Structure width="240" height="120">
0 0 0 PLACEHOLDER_steam_turbine
1 0 0 PLACEHOLDER_battery_buffer
2 0 0 PLACEHOLDER_redstone_alloy_cable_4x
3 0 0 PLACEHOLDER_lv_mv_transformer
4 0 0 PLACEHOLDER_copper_cable
5 0 0 PLACEHOLDER_mv_extruder
</Structure>

