---
navigation:
  title: "Output Loss"
  position: 70
---

# Output Loss: The Hidden Toll

There is no such thing as lossless power transfer in GT -- not because of
cables, but because of **emitters**. Every block that puts EU onto a wire
pays a toll to do it:

<Latex formula="\text{paid} = V + 2^{\max(0,\ tier-1)} \quad\text{per amp, to emit } V"/>

| Tier | Emits | Pays | Toll |
|------|-------|------|------|
| ULV  | 8     | 9    | 1    |
| LV   | 32    | 33   | 1    |
| MV   | 128   | 130  | 2    |

The toll is charged on **every emission**: generators launching packets,
battery buffers re-emitting them, transformers converting them. Three
emitters in a chain = three tolls, *compounding*.

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

<Mermaid>
flowchart LR
    G["4x LV Gens<br/>burn 132 (fuel)<br/>emit 128"] --> B["2x Buffers<br/>receive 128<br/>emit 125.8, pay 129.7"]
    B --> X["Transformer<br/>receive 125.8<br/>emit 123.9, pay 125.8"]
    X --> C["2x Copper MV<br/>-3.9 in transit"]
    C --> E["Extruder<br/>receives 120"]
</Mermaid>

Buffers received 128 and paid out 129.7. The missing **~1.7 EU/t came from
the batteries, forever** -- a structural drain no cable upgrade could fix,
measured as a slow battery ratchet weeks before the mechanic was identified.

## The chain rule

<Latex formula="\text{required source} \approx \text{load} \times \tfrac{33}{32}^{(\text{LV relays})} \times \tfrac{130}{128}^{(\text{MV relays})} + \text{cable loss}"/>

Every relay stage is a ~3% compounding tax at LV. "Add a battery buffer for
safety" costs 3% of everything that passes through it. The cures are
headroom (one extra generator drowns the toll) or **fewer emitter stages**
-- every relay you remove from a chain refunds its toll permanently.

<Details summary="Scene: the ledger rig (fill block IDs in the in-game editor)">
<Structure width="240" height="120">
0 0 0 PLACEHOLDER_steam_turbine
1 0 0 PLACEHOLDER_battery_buffer
2 0 0 PLACEHOLDER_redstone_alloy_cable_4x
3 0 0 PLACEHOLDER_lv_mv_transformer
4 0 0 PLACEHOLDER_copper_cable
5 0 0 PLACEHOLDER_mv_extruder
</Structure>
Open /guidenhc editor on this page -- block ids autocomplete; F3+T reloads.
</Details>

