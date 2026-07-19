---
navigation:
  title: "Cables"
  parent: /index.md
  icon: 'minecraft:iron_ingot'
  position: 80
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

## <Color color="#FFAA00">The rule that follows</Color>

<Latex formula="\text{deliverable} = \text{rating} - (\text{blocks} \times \text{loss} \times \text{amps})"/>

The nameplate is an <Color color="#7FFFFF"><Tooltip label="injection ceiling">The most EU/t a source can PUT ONTO this cable -- what arrives downstream is always less by the path loss.</Tooltip></Color>. Nobody downstream ever receives
the nameplate.

## <Color color="#FFAA00">Connections</Color>

Cables of *different materials* connect and conduct -- the game routes by the
live connection state, not by matching materials. Wire cutters sever a
connection; a soldering iron restores it; colored insulation isolates
different colors from each other. If two cables render as separate stubs
butting together, no power crosses.

See [Reference Tables](99-tables.md) for every ULV-MV cable's verified stats.

### <Color color="#FFAA00">Scene: sizes and the weak segment</Color>

A 4x trunk stepping down to 1x branches. **Hover each cable.**

<GameScene zoom={3.5} height={300} interactive={true}>
  <Block id="gregtech:gt.blockmachines" nbt='{id:"BaseMetaPipeEntity",mID:1248}' x="0" y="0" z="1" />
  <Block id="gregtech:gt.blockmachines" nbt='{id:"BaseMetaPipeEntity",mID:1248}' x="1" y="0" z="1" />
  <Block id="gregtech:gt.blockmachines" nbt='{id:"BaseMetaPipeEntity",mID:1246}' x="2" y="0" z="0" />
  <Block id="gregtech:gt.blockmachines" nbt='{id:"BaseMetaPipeEntity",mID:1246}' x="2" y="0" z="1" />
  <Block id="gregtech:gt.blockmachines" nbt='{id:"BaseMetaPipeEntity",mID:1246}' x="2" y="0" z="2" />
  <BlockAnnotation pos="0 0 1" color="#80FFAA00" thickness="2">

**Tin 4x cable** -- carries 4 amps (sizes multiply the base). The trunk.

  </BlockAnnotation>
  <BlockAnnotation pos="2 0 1" color="#80DD6644" thickness="2">

**Tin 1x branches** -- 1 amp each. If the trunk pushes more than 1A down a single branch for ~2 seconds sustained, **the branch burns, not the trunk** -- fire finds the under-rated segment.

  </BlockAnnotation>
</GameScene>
