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
up to about **1,500 L/s per turbine** (75 L/t -- in-game tooltips use per
second), and your boilers and pipes must *sustain* that rate: a turbine fed
1,400 L/s doesn't complain -- it
quietly makes less than 32 EU/t while looking perfectly busy. A big steam
tank between boilers and turbines behaves like a battery. Watch its level:
rising means the boilers are winning; falling means the turbines are living
off reserves.

### <Color color="#FFAA00">Scene: the steam appetite</Color>

<GameScene zoom={3} width="620" height="380" interactive={true}>
  <Block id="gregtech:gt.blockmachines" nbt='{id:"BaseMetaTileEntity",mID:100,mFacing:3s}' x="0" y="0" z="0" />
  <Block id="gregtech:gt.blockmachines" nbt='{id:"BaseMetaTileEntity",mID:101,mFacing:3s}' x="2" y="0" z="0" />
  <Block id="gregtech:gt.blockmachines" nbt='{id:"BaseMetaTileEntity",mID:102,mFacing:3s}' x="4" y="0" z="0" />
  <Block id="gregtech:gt.blockmachines" nbt='{id:"BaseMetaTileEntity",mID:105,mFacing:3s}' x="6" y="0" z="0" />
  <Block id="gregtech:gt.blockmachines" nbt='{id:"BaseMetaTileEntity",mID:114,mFacing:3s}' x="8" y="0" z="0" />
  <Block id="gregtech:gt.blockmachines" nbt='{id:"BaseMetaTileEntity",mID:1120,mFacing:3s}' x="2" y="0" z="3" />
  <Block id="gregtech:gt.blockmachines" nbt='{id:"BaseMetaTileEntity",mID:1121,mFacing:3s}' x="6" y="0" z="3" />
  <BlockAnnotation pos="0 0 0" color="#80DD6644" thickness="2">

**The boiler shelf** -- every early steam producer. **Hover each machine for its production rate** (the in-game tooltip states liters of steam per second). Total your bank against your turbines' appetite.

  </BlockAnnotation>
  <BlockAnnotation pos="2 0 3" color="#8044DD66" thickness="2">

**LV Steam Turbine** -- 7 L of steam per 3 EU (85% efficiency). Full **32 EU/t** output needs a *sustained* **~1,500 L/s** (75 L/t). Underfed, it quietly makes less while looking busy.

  </BlockAnnotation>
  <BlockAnnotation pos="6 0 3" color="#80AA66DD" thickness="2">

**MV Steam Turbine** -- 8 L of steam per 3 EU (75% efficiency). Full **128 EU/t** output needs a *sustained* **~6,800 L/s** (341 L/t) -- plan a serious boiler bank or a Large Boiler before relying on it.

  </BlockAnnotation>
</GameScene>
