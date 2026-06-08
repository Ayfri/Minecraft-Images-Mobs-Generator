# Minecraft Mobs YOLO Dataset Generator

Fabric client-side mod for Minecraft 26.1.2 that auto-generates a labeled image dataset of all Minecraft mobs in YOLO format.

Built as the data generation step for a computer vision course exercise (object detection). The AI training code lives in a separate repository.

---

## Dataset

The bot iterates through all 87 mob classes in order, captures 200 shots per mob from varying angles, lighting, weather, and biomes, then writes everything to `<game-dir>/dataset/`.

```
dataset/
- images/          512x288 PNG frames
- frames.csv       frame-level metadata (mob, weather, time, position)
- boxes.csv        one row per bounding box, joinable on `frame`
- progress.txt     completed mobs - allows resuming after a stop or crash
```

**`frames.csv`**

| column       | description                          |
|--------------|--------------------------------------|
| `frame`      | filename stem, e.g. `frame_000042`   |
| `mob`        | mob registry name, e.g. `zombie`     |
| `weather`    | `clear` / `rain` / `thunder`         |
| `time_ticks` | in-game time of the shot (0-23999)   |
| `shot`       | shot index within this mob (0-199)   |
| `mob_idx`    | which mob in the ordered class list  |
| `mob_x/y/z`  | world position of the mob entity     |

**`boxes.csv`**

| column        | description                                  |
|---------------|----------------------------------------------|
| `frame`       | links back to `frames.csv`                   |
| `class_id`    | YOLO class index (see class map below)       |
| `cx`, `cy`    | bounding-box center, normalized to `[0, 1]`  |
| `w`, `h`      | bounding-box size, normalized to `[0, 1]`    |
| `dist_blocks` | camera to entity-center distance in blocks   |

### Dataset properties

- **Ready for YOLO** - bounding boxes are in normalized YOLO format, no preprocessing needed to plug into YOLOv8/v11
- **Perfect ground truth** - labels are computed from 3D projection math, not hand-annotated, so there is zero annotation error
- **Scale** - 87 classes x 200 shots = ~17 400 labeled frames generated unattended in ~92 minutes
- **Controlled diversity** - every mob is captured across 6 weather states, a full 24h lighting cycle, 6 biome temperature buckets (frozen to hot), and 7 orbit tiers covering close/far/top-down viewpoints
- **Metadata for analysis** - `frames.csv` lets you slice by weather, time, or distance to study how conditions affect detection quality

---

## How it works

Each frame, the mod reads the render state and projects every entity's AABB corners through the view x projection matrix to screen space. If the resulting box is at least 5px and the entity is in the class map, it is recorded. Screenshots are scaled to 1280x720 then cropped 30% from each edge to 512x288.

The auto-capture bot (`/yologen`) handles the full pipeline unattended: it iterates through every mob in order, teleports the player to orbit positions around the mob, varies weather and time per shot, relocates to different biomes every 10 shots, and writes progress after each mob so the session can resume after a crash or manual stop.

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

Output goes to `build/libs/`. Copy the JAR to your Minecraft `mods/` folder alongside Fabric API and Fabric Language Kotlin.

---

## Commands

| Command      | Effect                                                          |
|--------------|-----------------------------------------------------------------|
| `/yologen`   | Start the bot (or stop it if running). Resumes from last saved mob. |
| `/yolostop`  | Stop the bot explicitly                                         |
| `/yoloclear` | Stop the bot, delete the entire `dataset/` folder and reset progress |

All commands require cheats / op.

---

## Class map

87 classes. IDs are assigned by list order in `ClassMap.kt` - never reorder existing entries.

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
