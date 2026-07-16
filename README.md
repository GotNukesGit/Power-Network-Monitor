# GTNH Power Monitor — Zircaloy Labs

Tiered power-grid dashboard cover for GT New Horizons 2.9.0-beta-2.
Built against the actual decompiled `gregtech-5.09.54.20.jar` per your
"never fabricate GTNH data" rule — every API call in this code was
verified against the real jar, not assumed from memory or prior GT
versions. Where something ISN'T verified, it's flagged explicitly below
and in code comments rather than guessed at silently.

## What's here — a block/item, textures, and recipes for every tier

- **Item**: `ItemPowerMonitorCover.java` — one item, 9 metadata subtypes
  (ULV through UV), same "meta item" pattern GT itself uses for covers.
- **Textures**: `gen_textures.py` generated 9 placeholder 16x16 overlay
  PNGs (`assets/powermonitor/textures/blocks/overlay_powermonitor_*.png`),
  color-ramped by tier. These are functional placeholders, not final art —
  swap the art, keep the filenames/wiring.
- **Recipes**: `ModPowerMonitorRegistration.registerRecipe()` — one shaped
  crafting recipe per tier, using GT's real circuit tier items.
- **Logic**: `PowerMonitorTier`, `RollingSampleBuffer`, `NetworkDiscovery`,
  `PowerMonitorCoverBehavior` — the tier table, history tracking, network
  BFS, and tick/overvolt logic.
- **The real Cover**: `PowerMonitorCover.java` — extends `gregtech.common.
  covers.Cover` directly, verified against a working GT cover template.

## Verified this session, all from the real jar

- `IBasicEnergyContainer` (public) exposes `getAverageElectricInput/Output()`,
  `getStoredEU/getEUCapacity()`, `getInputVoltage/getOutputVoltage()` —
  no mixins/reflection needed to read live power data.
- `MTECable#getConnectableMTE()` (public) reuses GT's own connectivity
  rules for the network BFS.
- `BaseMetaTileEntity#injectEnergyUnits()` checks voltage against rating
  and calls `doExplosion(aVoltage)` on overvolt — `doExplosion(long)` is
  public on `IGregTechTileEntity`, so the cover's overvolt-destroy calls
  the literal same mechanism real cables/machines use.
- Battery buffer classes confirmed: `MTEBasicBatteryBuffer` and
  `kekztech.common.tileentities.MTELapotronicSuperCapacitor` (LSC),
  correctly excluded from generation/consumption totals.
- `Cover(CoverContext, ITexture)` constructor, and the real overridable
  NBT hooks (`readDataFromNbt`/`saveDataToNbt` — NOT `readFromNbt`/
  `writeToNBT`, which are `final` on the base class; got this wrong on
  the first pass, fixed after checking directly).
- `CoverRegistry.registerCover(ItemStack, ITexture, CoverFactory)` and the
  real GT circuit tier item names (`Circuit_Primitive/Basic/Good/Advanced/
  Data/Elite/Master`) — confirmed via a `strings` scan of `ItemList.class`,
  and the registration shape confirmed against `MetaGeneratedItem02
  #registerCovers()`, the actual method GT uses to register its own covers.
- `TextureFactory` is in `gregtech.api.render`, not `gregtech.api.util` —
  caught and fixed a wrong import guess.
- `MACHINE_CASINGS[tier][x]` indexing confirmed by cross-checking
  `MTETransformer` (a real tiered GT machine): indexed by GT's standard
  tier integer (0=ULV, 1=LV...), which matches `PowerMonitorTier`'s enum
  ordinal order — not a coincidence, the enum was deliberately ordered
  to match.

## What's flagged as NOT verified — real gaps, not guessed at

1. **ZPM/UV circuit mapping.** Only 7 plain circuit tiers were found
   (Primitive→Master) for 9 PowerMonitorTiers. ZPM and UV currently both
   fall back to `Circuit_Master` rather than guessing which of
   `Circuit_Biowarecomputer`/`Circuit_Biowaresupercomputer` (both exist in
   the jar) actually corresponds to which tier. Cheap fix once confirmed —
   two lines in `circuitForTier()`.
2. **Cable ingredient in the recipe** currently reuses the circuit item as
   a placeholder (`'c', circuit`) rather than a real tier-matched cable
   ItemStack — GT's cable item naming per material/tier wasn't decompiled
   this session.
3. **No casing/plate material ingredient.** GT's real tier-material
   convention (which metal plate belongs at which voltage tier) wasn't
   verified, so the recipe is deliberately just circuit + placeholder
   cable + redstone rather than guessing at period-accurate materials.
4. **Wiring our own PNG textures into GT's texture pipeline.** The cover
   currently layers only GT's existing `MACHINE_CASINGS` art (visually
   legitimate, matches GT's own covers) — the 9 generated overlay PNGs
   aren't wired in yet. That needs an `IIconContainer` implementation
   registered through Forge's texture stitch (`IconRegister`/`TextureMap`
   hook) — standard Forge asset pipeline work, not GT-specific, but not
   verified against this jar this session.
5. **`EntityPlayer#addChatMessage`/`ChatComponentText` import paths** in
   the v1 screwdriver-click readout — standard Forge 1.7.10 deobfuscated
   names, should resolve fine against your dev environment's real MCP
   mappings, but the jar decompiled this session showed obfuscated names
   for vanilla MC methods, so this is the one thing here I couldn't check
   against actual mapped names.
6. **History doesn't persist across chunk reload** (only tier does) —
   acceptable v1 limitation, not a blocker.
7. **A proper HUD panel GUI** instead of the v1 chat readout — natural v2.

## Suggested next session

Drop this into your project. Compiler will likely flag #5 first (quick
fix). Then decide how much of #1-4 you want period-accurate before first
test-build vs. shipping with placeholders and refining later — none of
them block getting the mod loading and functional in-game.
