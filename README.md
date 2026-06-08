# YOLO Dataset Generator - Minecraft Fabric Mod

Fabric client-side mod for Minecraft 26.1 that automatically captures screenshots and generates YOLO-format bounding box labels for every mob visible on screen.

---

## How it works

Every 20 rendered frames, the mod hooks into `LevelRenderEvents.END_MAIN` to:

1. Read the current frame's render state (camera position, projection matrix, view matrix, entity list)
2. Reconstruct each entity's AABB from its render state and project all 8 corners to screen space
3. Discard boxes that are too small (<5 px), invisible, or not in the class map
4. If at least one valid box exists: save a PNG screenshot + a `.txt` label file

Output is written to `<game-dir>/dataset/` on a background IO thread so the render loop is never blocked.

---

## Requirements

- Java 25
- Minecraft 26.1.2
- Fabric Loader 0.19.3+
- Fabric API 0.150.0+26.1.2
- Fabric Language Kotlin 1.13.12+kotlin.2.4.0

---

## Build

```bash
./gradlew build
```

The mod JAR is output to `build/libs/`. Copy it to your Minecraft `mods/` folder along with Fabric API and Fabric Language Kotlin.

---

## Running the pipeline

### 1. Install the mod

Drop the compiled JAR into `.minecraft/mods/` (or your instance's mods folder).

### 2. Launch Minecraft

Start Minecraft with Fabric. Join a world (singleplayer creative is recommended for automation).

### 3. Spawn mobs around you

The mod captures automatically - no keybind needed. Just make sure mobs are visible on screen.

For mass generation, use commands to automate the cycle (see [Automation](#automation)).

### 4. Collect the dataset

While the game runs, images and labels accumulate in:

```
<game-dir>/
└── dataset/
    ├── images/
    │   ├── frame_000000.png
    │   ├── frame_000001.png
    │   └── ...
    └── labels/
        ├── frame_000000.txt
        ├── frame_000001.txt
        └── ...
```

Each `.txt` file follows standard YOLO format - one line per entity:

```
<class_id> <x_center> <y_center> <width> <height>
```

All values are normalized to `[0, 1]` relative to the screen dimensions.

---

## Automation

To generate large datasets quickly, run a repeating command loop in-game (requires cheats):

```
# Summon a mix of mobs around the player, wait a few ticks, kill them, repeat
/summon minecraft:zombie ~ ~ ~3
/summon minecraft:skeleton ~ ~ ~-3
/summon minecraft:creeper ~3 ~ ~
# ... etc
/kill @e[type=!player]
```

Or use a command block chain / a data pack that:

1. Teleports the player to a random location
2. Sets random time (`/time set <0-24000>`) and weather
3. Summons a random selection of mobs at varying distances and angles
4. Waits ~2 seconds (40 ticks) for the mod to capture several frames
5. Kills all non-player entities and repeats

A flat superflat world with no structures keeps the background uniform and reduces label noise.

---

## Class map

87 classes covering all mob categories. Class IDs are stable - do not reorder.

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

### YOLO `data.yaml`

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

| Constant                 | File                | Default | Effect                                                      |
|--------------------------|---------------------|---------|-------------------------------------------------------------|
| `CAPTURE_EVERY_N_FRAMES` | `DatasetCapture.kt` | `20`    | Frames between captures (~1 capture/sec at 20 fps)          |
| min box size             | `Projector.kt`      | `5 px`  | Entities with a projected box smaller than this are ignored |
