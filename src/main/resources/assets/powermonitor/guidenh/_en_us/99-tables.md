---
navigation:
  title: "Reference Tables"
  parent: /index.md
  icon: 'minecraft:book'
  position: 10
---

# Reference Tables

All values read from GT 5.09.54.20 / GTNH 2.8.x source registrations.

## <Color color="#FFAA00">Cables, ULV-MV (per 1x size -- sizes multiply amps; wires lose 2x and shock)</Color>

| Material | Tier | Amps (1x) | Cable loss /m/A |
|----------|------|-----------|------------------|
| Red Alloy | ULV | 1 | **0** |
| Tin | LV | 1 | 1 |
| Zinc | LV | 1 | 1 |
| Soldering Alloy | LV | 1 | 1 |
| Cobalt | LV | 2 | 1 |
| Lead | LV | 2 | 2 |
| Redstone Alloy (GT++) | LV | 1 | **0** |
| Copper | MV | 1 | 2 |
| Annealed Copper | MV | 1 | 1 |
| Iron | MV | 2 | 3 |
| Nickel | MV | 3 | 3 |
| Cupronickel | MV | 4 | 3 |

Note **Red Alloy** (ULV, 8V) and **Redstone Alloy** (LV, 32V) are different
materials from different mods -- confusing them costs real debugging time.
Truly lossless multi-amp wire (superconductor) exists but its production
chain begins tiers later.

## <Color color="#FFAA00">Emission toll (per amp)</Color>

| Tier | Emits | Pays | As a share |
|------|-------|------|------------|
| ULV | 8 | 9 | 12.5% |
| LV | 32 | 33 | 3.1% |
| MV | 128 | 130 | 1.6% |

## <Color color="#FFAA00">Amp rules</Color>

| Device | In | Out |
|--------|----|----|
| Machine | (2 x recipe EU/t)/V + 1 | -- |
| Battery Buffer | 2A per chargeable slot | 1A per battery |
| Battery Charger | 8A per slot (min 4) | 4A per battery (min 2) |
| Transformer (step-up) | 4A low (+1 slack) | 1A high |
| Transformer (step-down) | 1A high | up to 4A low |

## <Color color="#FFAA00">Hazards (all source-verified)</Color>

- Packet over machine's input voltage: **explosion** (logged).
- Packet over cable's max voltage: under-rated segments **catch fire**.
- Sustained (~2s) amps over cable rating: under-rated segments catch fire.
- Rain on an exposed machine: 10% explosion / 90% fire per trigger. Roof it.
- Fire adjacent: explosion risk. Water: verified harmless.

## <Color color="#FFAA00">Overclocking (MV running LV recipes)</Color>

Per tier above the recipe: **4x energy, 2x speed**. An MV machine runs an LV
recipe twice as fast at four times the EU/t -- budget amps accordingly.
