# YOLO Dataset Generator - Minecraft Fabric Mod

Fabric client-side mod for Minecraft 26.1.2 that automatically captures screenshots and generates YOLO-format bounding
box labels for every mob visible on screen.

---

## How it works

The mod hooks into `LevelRenderEvents.END_MAIN` each frame and:

1. Reads the current frame's render state (camera, projection matrix, entity list)
2. Projects each entity's 8 AABB corners to screen space via the view × projection matrices
3. Discards boxes that are invisible, not in the class map, or smaller than 5 px
4. If at least one valid box exists: saves a PNG + a `.txt` label file + a metadata line

Output is written to `<game-dir>/dataset/` on a 4-thread background IO pool so the render loop is never blocked.

---

## Requirements

- Java 25
- Minecraft 26.1.2
- Fabric Loader 0.19.3+
- Fabric API 0.150.0+26.1.2
- Fabric Language Kotlin 1.13.12+kotlin.2.4.0

---

## Build

```powershell
./gradlew build
```

The mod JAR is output to `build/libs/`. Copy it to your Minecraft `mods/` folder along with Fabric API and Fabric
Language Kotlin.

---

## Dataset output

```
<game-dir>/
└── dataset/
    ├── images/
    │   ├── frame_000000.png   (1280×720 PNG)
    │   └── ...
    ├── labels/
    │   ├── frame_000000.txt   (YOLO format, one line per entity)
    │   └── ...
    └── metadata.jsonl         (one JSON line per frame)
```

**Label format** - one line per entity, values normalized to `[0, 1]`:

```
<class_id> <cx> <cy> <w> <h> <dist_blocks>
```

**Metadata format** - `dataset/metadata.jsonl`, one JSON object per captured frame:

```json
{
  "frame": "frame_000042",
  "mob": "zombie",
  "x": 12.34,
  "y": 64.00,
  "z": -8.12,
  "weather": "rain",
  "time_ticks": 26400,
  "shot": 22,
  "mob_idx": 5
}
```

---

## Commands

| Command      | Effect                               |
|--------------|--------------------------------------|
| `/yologen`   | Toggle the auto-capture bot on / off |
| `/yolostop`  | Stop the bot explicitly              |
| `/yoloclear` | Delete the entire `dataset/` folder  |

All commands require cheats / op.

---

## Auto-capture bot (`/yologen`)

When started, the bot prints its current settings to chat and loops indefinitely:

**SETUP phase** (70 ticks minimum, ~3.5 s):

1. Kill previous generated entities and nearby mobs, switch to spectator mode
2. Pick random XZ (±500 blocks), pre-map distinct biome relocation candidates in a ±600-block grid, and teleport to Y=200 to load terrain
3. From tick 45: wait until the target chunk is loaded, land on the real surface, summon one tagged NoAI random mob, then snap it to the loaded surface

**CAPTURING phase** (100 shots, 3 ticks each, ~15 s):

- Each shot: teleport player to an orbit position, wait for the server round-trip, capture
- 4 orbit tiers: close-ground (3.5–8 blk), mid (7–13 blk), far (11–19 blk), top-down
- Every 10 shots: use the shuffled pre-mapped biome pool, pre-load the target chunk, then teleport the tagged mob to the loaded surface
- Shots 0–59: clear weather | 60–79: rain | 80–99: thunder, applied instantly through the integrated server when available
- Time advances +20 s per shot from a random base, so no two shots share the same lighting

Full pass over all 87 mobs ≈ **8700 labelled frames** in ~22 minutes unattended.

**HUD** - a top-center panel shows phase, mob, total frames, time, shot/setup progress, weather schedule, relocation batch
ticks, mapped biome count, and terrain/preload status. Action-bar text (above hotbar) shows compact live shot info.
Neither HUD element appears in screenshots.

---

## Class map

87 classes. IDs are assigned by list order in `ClassMap.kt` - never reorder.

| ID | Entity           | Category   |
|----|------------------|------------|
| 0  | Blaze            | Hostile    |
| 1  | Bogged           | Hostile    |
| 2  | Breeze           | Hostile    |
| 3  | Cave Spider      | Hostile    |
| 4  | Creaking         | Hostile    |
| 5  | Creeper          | Hostile    |
| 6  | Drowned          | Hostile    |
| 7  | Elder Guardian   | Hostile    |
| 8  | Enderman         | Hostile    |
| 9  | Endermite        | Hostile    |
| 10 | Evoker           | Hostile    |
| 11 | Ghast            | Hostile    |
| 12 | Guardian         | Hostile    |
| 13 | Hoglin           | Hostile    |
| 14 | Husk             | Hostile    |
| 15 | Illusioner       | Hostile    |
| 16 | Magma Cube       | Hostile    |
| 17 | Parched          | Hostile    |
| 18 | Phantom          | Hostile    |
| 19 | Piglin           | Hostile    |
| 20 | Piglin Brute     | Hostile    |
| 21 | Pillager         | Hostile    |
| 22 | Ravager          | Hostile    |
| 23 | Shulker          | Hostile    |
| 24 | Silverfish       | Hostile    |
| 25 | Skeleton         | Hostile    |
| 26 | Slime            | Hostile    |
| 27 | Spider           | Hostile    |
| 28 | Stray            | Hostile    |
| 29 | Vex              | Hostile    |
| 30 | Vindicator       | Hostile    |
| 31 | Warden           | Hostile    |
| 32 | Witch            | Hostile    |
| 33 | Wither Skeleton  | Hostile    |
| 34 | Zoglin           | Hostile    |
| 35 | Zombie           | Hostile    |
| 36 | Zombie Villager  | Hostile    |
| 37 | Zombified Piglin | Hostile    |
| 38 | Bee              | Neutral    |
| 39 | Dolphin          | Neutral    |
| 40 | Goat             | Neutral    |
| 41 | Iron Golem       | Neutral    |
| 42 | Llama            | Neutral    |
| 43 | Panda            | Neutral    |
| 44 | Polar Bear       | Neutral    |
| 45 | Strider          | Neutral    |
| 46 | Trader Llama     | Neutral    |
| 47 | Wolf             | Neutral    |
| 48 | Allay            | Passive    |
| 49 | Armadillo        | Passive    |
| 50 | Axolotl          | Passive    |
| 51 | Bat              | Passive    |
| 52 | Camel            | Passive    |
| 53 | Camel Husk       | Passive    |
| 54 | Cat              | Passive    |
| 55 | Chicken          | Passive    |
| 56 | Cod              | Passive    |
| 57 | Cow              | Passive    |
| 58 | Donkey           | Passive    |
| 59 | Fox              | Passive    |
| 60 | Frog             | Passive    |
| 61 | Glow Squid       | Passive    |
| 62 | Happy Ghast      | Passive    |
| 63 | Horse            | Passive    |
| 64 | Mooshroom        | Passive    |
| 65 | Mule             | Passive    |
| 66 | Nautilus         | Passive    |
| 67 | Ocelot           | Passive    |
| 68 | Parrot           | Passive    |
| 69 | Pig              | Passive    |
| 70 | Pufferfish       | Passive    |
| 71 | Rabbit           | Passive    |
| 72 | Salmon           | Passive    |
| 73 | Sheep            | Passive    |
| 74 | Skeleton Horse   | Passive    |
| 75 | Sniffer          | Passive    |
| 76 | Snow Golem       | Passive    |
| 77 | Squid            | Passive    |
| 78 | Tadpole          | Passive    |
| 79 | Tropical Fish    | Passive    |
| 80 | Turtle           | Passive    |
| 81 | Zombie Horse     | Passive    |
| 82 | Zombie Nautilus  | Passive    |
| 83 | Copper Golem     | Boss/Other |
| 84 | Ender Dragon     | Boss/Other |
| 85 | Giant            | Boss/Other |
| 86 | Wither           | Boss/Other |

### `data.yaml`

```yaml
path: dataset
train: images
val: images

nc: 87
names:
  0: blaze
  1: bogged
  2: breeze
  3: cave_spider
  4: creaking
  5: creeper
  6: drowned
  7: elder_guardian
  8: enderman
  9: endermite
  10: evoker
  11: ghast
  12: guardian
  13: hoglin
  14: husk
  15: illusioner
  16: magma_cube
  17: parched
  18: phantom
  19: piglin
  20: piglin_brute
  21: pillager
  22: ravager
  23: shulker
  24: silverfish
  25: skeleton
  26: slime
  27: spider
  28: stray
  29: vex
  30: vindicator
  31: warden
  32: witch
  33: wither_skeleton
  34: zoglin
  35: zombie
  36: zombie_villager
  37: zombified_piglin
  38: bee
  39: dolphin
  40: goat
  41: iron_golem
  42: llama
  43: panda
  44: polar_bear
  45: strider
  46: trader_llama
  47: wolf
  48: allay
  49: armadillo
  50: axolotl
  51: bat
  52: camel
  53: camel_husk
  54: cat
  55: chicken
  56: cod
  57: cow
  58: donkey
  59: fox
  60: frog
  61: glow_squid
  62: happy_ghast
  63: horse
  64: mooshroom
  65: mule
  66: nautilus
  67: ocelot
  68: parrot
  69: pig
  70: pufferfish
  71: rabbit
  72: salmon
  73: sheep
  74: skeleton_horse
  75: sniffer
  76: snow_golem
  77: squid
  78: tadpole
  79: tropical_fish
  80: turtle
  81: zombie_horse
  82: zombie_nautilus
  83: copper_golem
  84: ender_dragon
  85: giant
  86: wither
```

---

## Tuning

All constants are `internal const val` in `AutoCapture.kt` except where noted.

| Constant                     | Default      | Effect                                                       |
|------------------------------|--------------|--------------------------------------------------------------|
| `SHOTS_PER_MOB`              | `100`        | Screenshots per mob                                          |
| `SHOTS_CLEAR` / `SHOTS_RAIN` | `60` / `20`  | Weather distribution; thunder = remainder                    |
| `SETUP_WAIT_TICKS`           | `70`         | Ticks before capture starts (chunk load buffer)              |
| `MOB_SPAWN_TICK`             | `45`         | Tick within setup when mob is summoned                       |
| `TIME_PER_SHOT`              | `400` ticks  | In-game time advance per shot (+20 s)                        |
| `RELOCATE_EVERY`             | `10`         | Shots between biome relocations                              |
| `BIOME_SCAN_RADIUS`          | `600`        | Half-size of biome search grid (blocks)                      |
| `BIOME_PREMAP_STEP`          | `64`         | Setup-time grid step for distinct biome pre-map              |
| `BIOME_SCAN_STEP`            | `32`         | Fallback grid step if the pre-map has no valid candidate     |
| `CAPTURE_EVERY_N_FRAMES`     | `20`         | Frames between captures in manual mode (`DatasetCapture.kt`) |
| `TARGET_W / TARGET_H`        | `1280 × 720` | Output resolution (`DatasetCapture.kt`)                      |
| Min box size                 | `5 px`       | Smaller projected boxes are discarded (`Projector.kt`)       |
