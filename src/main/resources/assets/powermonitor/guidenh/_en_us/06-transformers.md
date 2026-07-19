---
navigation:
  title: "Transformers"
  parent: /index.md
  icon: 'minecraft:comparator'
  position: 40
---

# Transformers

A transformer converts between adjacent tiers, **energy-conserving** -- the
conversion itself is exactly 1:1 (its *emission* pays the standard toll like
every other emitter).

**Faces:** one face carries a single large dot -- that face is the
high-voltage side, always.

**Modes** (toggled with a soft mallet -- it's the same allowed-to-work flag
as machines):
- **Step-down** (default): draws 1A of the higher tier, outputs up to 4A of
  the lower.
- **Step-up:** accepts up to 4 low-tier amps (the tooltip's number; it will
  actually take one extra to keep its buffer fed), emits **1A of the higher
  tier** -- which makes a single step-up transformer a <Color color="#7FFFFF"><Tooltip label="hard ceiling">A step-up transformer emits exactly 1 amp of the higher tier -- 128 EU/t for LV/MV -- no matter how much low-tier power feeds it. Parallel transformers raise the ceiling.</Tooltip></Color> of one
  high-tier packet per tick on everything behind it.

That output ceiling is the number to check when a step-up network feels
starved: one LV/MV transformer can never deliver more than 128 EU/t to the
MV side, no matter what feeds it.

Hi-Amp transformers (4A high side, 16A low side) exist; check NEI for when
their recipes become available to you.

### <Color color="#FFAA00">Scene: step-up and the ceiling</Color>

<GameScene zoom={3.5} height="360" interactive={true}>
  <Block id="gregtech:gt.blockmachines" nbt='{id:"BaseMetaTileEntity",mID:1120,mFacing:5s}' x="-1" y="0" z="0" />
  <Block id="gregtech:gt.blockmachines" nbt='{id:"BaseMetaTileEntity",mID:1120,mFacing:3s}' x="0" y="0" z="-1" />
  <Block id="gregtech:gt.blockmachines" nbt='{id:"BaseMetaTileEntity",mID:1120,mFacing:2s}' x="0" y="0" z="1" />
  <Block id="gregtech:gt.blockmachines" nbt='{id:"BaseMetaTileEntity",mID:1120,mFacing:3s}' x="1" y="0" z="-1" />
  <Block id="gregtech:gt.blockmachines" nbt='{id:"BaseMetaPipeEntity",mID:30653,mConnections:60b}' x="0" y="0" z="0" />
  <Block id="gregtech:gt.blockmachines" nbt='{id:"BaseMetaPipeEntity",mID:30653,mConnections:52b}' x="1" y="0" z="0" />
  <Block id="gregtech:gt.blockmachines" nbt='{id:"BaseMetaTileEntity",mID:21,mFacing:5s}' x="2" y="0" z="0" />
  <Block id="gregtech:gt.blockmachines" nbt='{id:"BaseMetaPipeEntity",mID:1366,mConnections:48b}' x="3" y="0" z="0" />
  <BlockAnnotation pos="-1 0 0" color="#8044DD66" thickness="2">

**4x LV generators** -- each attached to the cable run, 128 EU/t combined at 4A: exactly one step-up transformer's appetite.

  </BlockAnnotation>
  <BlockAnnotation pos="2 0 0" color="#80AA66DD" thickness="2">

**LV/MV Transformer, step-up** -- the single-dot face (pointing at the copper here) is the high-voltage side, *always*. Accepts 4A of LV; emits **1A of MV: a hard 128 EU/t ceiling** downstream. Need more? Parallel transformers.

  </BlockAnnotation>
</GameScene>

