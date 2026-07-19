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

<GameScene zoom={3.5} width="620" height="360" interactive={true}>
  <Block id="gregtech:gt.blockmachines" nbt='{id:"BaseMetaPipeEntity",mID:1248,mConnections:48b}' x="0" y="0" z="1" />
  <Block id="gregtech:gt.blockmachines" nbt='{id:"BaseMetaPipeEntity",mID:1248,mConnections:48b}' x="1" y="0" z="1" />
  <Block id="gregtech:gt.blockmachines" nbt='{id:"BaseMetaPipeEntity",mID:1246,mConnections:28b}' x="2" y="0" z="1" />
  <Block id="gregtech:gt.blockmachines" nbt='{id:"BaseMetaPipeEntity",mID:1246,mConnections:40b}' x="2" y="0" z="0" />
  <Block id="gregtech:gt.blockmachines" nbt='{id:"BaseMetaPipeEntity",mID:1246,mConnections:48b}' x="3" y="0" z="0" />
  <Block id="gregtech:gt.blockmachines" nbt='{id:"BaseMetaPipeEntity",mID:1246,mConnections:4b}' x="2" y="0" z="2" />
  <Block id="minecraft:netherrack" x="4" y="0" z="0" />
  <Block id="minecraft:fire" x="4" y="1" z="0" />
  <BlockAnnotation pos="0 0 1" color="#80FFAA00" thickness="2">

**Tin 4x trunk** -- 4 amps (sizes multiply the base). The thick run.

  </BlockAnnotation>
  <BlockAnnotation pos="2 0 1" color="#8044DD66" thickness="2">

**The junction** -- 1x tin branches split off here at 1 amp each. Thin cables render thin: the wisps leaving this block are the branches.

  </BlockAnnotation>
  <BlockAnnotation pos="4 1 0" color="#80DD4444" thickness="2">

**Where it ends.** Sustained (~2s) draw above 1A ignites the *under-rated segment* -- the branch burns, never the trunk. The fire it starts then spreads like any fire and can take the surroundings with it.

  </BlockAnnotation>
</GameScene>
