# YOLO Dataset Generator - Minecraft Fabric Mod

Fabric client-side mod for Minecraft 26.1.2 that automatically captures screenshots and generates YOLO-format bounding
box labels for every mob visible on screen.

---

## How it works

The mod hooks into `LevelRenderEvents.END_MAIN` each frame and:

1. Reads the current frame's render state (camera, projection matrix, entity list)
2. Projects each entity's 8 AABB corners to screen space via the view × projection matrices
3. Discards boxes that are invisible, not in the class map, or smaller than 5 px
4. If at least one valid box exists: saves a PNG + appends rows to `frames.csv` and `boxes.csv`

Images are scaled to 1280×720 then cropped 30% from each edge → **512×288** final resolution.
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
    │   ├── frame_000000.png   (512×288 PNG)
    │   └── ...
    ├── frames.csv             (one row per captured frame)
    └── boxes.csv              (one row per bounding box, joinable on `frame`)
```

**`frames.csv`** — frame-level metadata:

| column       | description                         |
|--------------|-------------------------------------|
| `frame`      | filename stem, e.g. `frame_000042`  |
| `mob`        | mob registry name, e.g. `zombie`    |
| `weather`    | `clear` / `rain` / `thunder`        |
| `time_ticks` | in-game time of the shot (0–23999)  |
| `shot`       | shot index within this mob (0–199)  |
| `mob_idx`    | mob counter across the full session |
| `mob_x/y/z`  | world position of the mob entity    |

**`boxes.csv`** — one row per detected entity per frame:

| column        | description                                 |
|---------------|---------------------------------------------|
| `frame`       | links back to `frames.csv`                  |
| `class_id`    | YOLO class index (see class map below)      |
| `cx`, `cy`    | bounding-box center, normalized to `[0, 1]` |
| `w`, `h`      | bounding-box size, normalized to `[0, 1]`   |
| `dist_blocks` | camera → entity-center distance in blocks   |

Both files are created on first capture with their header row, then appended atomically (synchronized) for every
subsequent frame.

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

When started, the bot sets FOV to **70**, prints its current settings to chat, and loops indefinitely:

**SETUP phase** (70 ticks minimum, ~3.5 s):

1. Kill previous generated entities and nearby mobs, switch to spectator mode
2. Pick random XZ (±500 blocks), pre-map distinct biome relocation candidates in a ±600-block grid, and teleport to
   Y=200 to load terrain
3. From tick 45: wait until the target chunk is loaded, land on the real surface, summon one tagged NoAI random mob
   at a tree-free position (up to 25 attempts comparing `MOTION_BLOCKING` vs `MOTION_BLOCKING_NO_LEAVES`),
   then snap it to the loaded surface

**CAPTURING phase** (200 shots, 3 ticks each, ~30 s):

- Each shot: teleport player to an orbit position, recompute yaw/pitch toward mob, capture
- 7 orbit tiers (TIER_SIZE=25): 6 side/angled tiers + top-down for the last 2 tiers only (25% of shots)
  , close ground (2.5–5.5 blk, 0.3–1.5 blk height), medium low (5–9 blk, 1–3 blk), far moderate (9–15 blk, 1.5–4 blk),
  close side (3–6 blk, 0.3–2 blk), medium mid (6–11 blk, 2.5–6 blk), far eye-level (10–17 blk, 0.5–3 blk),
  then top-down (2–5 blk dist, 8–14 blk height)
- Every 10 shots: pull next entry from the pre-mapped biome pool (6 temperature buckets:
  frozen/cold/cool/temperate/warm/hot, always cycling away from the current bucket), pre-load the target chunk async,
  teleport mob, wait `TERRAIN_POST_SNAP_TICKS` for meshes to settle
- Weather is 60% clear / 20% rain / 20% thunder, applied instantly via `server.setWeatherParameters` (direct API, no
  commands)
- Time advances +20 s per shot from a random base, so no two shots share the same lighting

Full pass over all 87 mobs ≈ **17 400 labelled frames** in ~92 minutes unattended.

**HUD** - a top-center panel shows phase, mob, total frames, time, shot/setup progress, segmented weather schedule,
mapped biome count, terrain/preload status, global progress bar, and ETA. Action-bar text (above hotbar) shows compact
live shot info.
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

| Constant                  | Default      | Effect                                                               |
|---------------------------|--------------|----------------------------------------------------------------------|
| `BIOME_PREMAP_STEP`       | `64`         | Setup-time grid step for distinct biome pre-map                      |
| `BIOME_SCAN_RADIUS`       | `2000`       | Half-size of biome search grid (blocks)                              |
| `CAPTURE_EVERY_N_FRAMES`  | `20`         | Frames between captures in manual mode (`DatasetCapture.kt`)         |
| `CROP_X / CROP_Y`         | `384 / 216`  | 30% crop offset; final output is **512×288** (`DatasetCapture.kt`)   |
| Min box size              | `5 px`       | Smaller projected boxes are discarded; also re-checked post-crop     |
| `MOB_SPAWN_TICK`          | `45`         | Tick within setup when mob is summoned                               |
| `RELOCATE_EVERY`          | `10`         | Shots between biome relocations                                      |
| `SETUP_WAIT_TICKS`        | `70`         | Ticks before capture starts (chunk load buffer)                      |
| `SHOTS_PER_MOB`           | `200`        | Screenshots per mob                                                  |
| `TARGET_W / TARGET_H`     | `1280 × 720` | Scale-to dimensions before crop (`DatasetCapture.kt`)                |
| `TERRAIN_POST_SNAP_TICKS` | `15`         | Extra ticks after mob surface-snap before orbit shots begin          |
| `TERRAIN_WAIT_TICKS`      | `30`         | Ticks to wait after teleporting player to a relocation position      |
| `TIER_SIZE`               | `25`         | Shots per orbit tier; tiers 3+ all use the top-down pattern          |
| `TIME_PER_SHOT`           | `400` ticks  | In-game time advance per shot (+20 s)                                |
| `WeatherPhase` fractions  | 60 / 20 / 20 | Clear / rain / thunder share (%) of `SHOTS_PER_MOB`; defined in enum |
