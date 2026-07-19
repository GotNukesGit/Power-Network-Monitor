---
navigation:
  title: "Output Loss"
  parent: /index.md
  position: 70
---

# Output Loss: The Hidden Toll

Power never moves for free in GT. Cables charge per meter -- but even a
lossless cable can't make transfer free, because **every emitter charges
admission**: any block that puts EU onto a wire pays a <Tooltip label="toll" tooltip="V + 2^(tier-1) EU per amp, decremented from the emitter's buffer while only V goes on the wire"/> to do it:

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

| Where each EU goes | What that means | Who pays |
|---|---|---|
| **Delivered** | Reaches a machine and does work | -- |
| **Cable loss** | Each packet sheds a few EU per cable block it crosses | dissipated in the wire |
| **Output loss** (generators) | The emission toll, covered by burning extra fuel | invisible to the grid |
| **Output loss** (relays) | Buffers and transformers have no fuel line -- their toll comes from stored energy | **your batteries** |

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

Every GT machine shares one block id (the tile data differentiates them), so
the hulls below are generic -- the labels carry the meaning. Rotate and zoom.

<GameScene zoom={3} interactive={true}>
  <Block id="gregtech:gt.blockmachines" x="0" y="0" z="0" />
  <Block id="gregtech:gt.blockmachines" x="1" y="0" z="0" />
  <Block id="gregtech:gt.blockmachines" x="2" y="0" z="0" />
  <Block id="gregtech:gt.blockmachines" x="3" y="0" z="0" />
  <Block id="gregtech:gt.blockmachines" x="4" y="0" z="0" />
  <Block id="gregtech:gt.blockmachines" x="5" y="0" z="0" />
  <BlockAnnotation pos="0 0 0" color="#8044DD66" thickness="2" alwaysOnTop="true">generators -- toll paid by fuel</BlockAnnotation>
  <BlockAnnotation pos="1 0 0" color="#80FFAA00" thickness="2" alwaysOnTop="true">buffer -- pays 33 per 32, from batteries</BlockAnnotation>
  <BlockAnnotation pos="2 0 0" color="#80AAAAAA" thickness="2" alwaysOnTop="true">4A lossless backbone</BlockAnnotation>
  <BlockAnnotation pos="3 0 0" color="#80AA66DD" thickness="2" alwaysOnTop="true">transformer -- 1A MV ceiling</BlockAnnotation>
  <BlockAnnotation pos="4 0 0" color="#80AAAAAA" thickness="2" alwaysOnTop="true">copper: -2 per block per amp</BlockAnnotation>
  <BlockAnnotation pos="5 0 0" color="#80DD6644" thickness="2" alwaysOnTop="true">extruder -- 120 EU/t delivered</BlockAnnotation>
</GameScene>

