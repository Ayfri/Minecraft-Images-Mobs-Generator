# Mob variants

How the `variants` config flag changes each mob's appearance. Two independent flags drive spawn
diversity:

- **`babies`** - randomly turns ~50% of ageable mobs into babies. Nothing else.
- **`variants`** - rolls a random appearance per mob. When **off**, every mob is forced to its
  canonical default so the dataset is reproducible and a mob never silently inherits a biome look
  from its relocation position (Minecraft's `finalizeSpawn` assigns biome/random variants by
  default - we override or reset it).

All logic lives in `applyRandomVariant` (on) and `resetSpawnVariantToDefault` (off) in
`AutoCaptureMob.kt`. Variants are applied on the server entity right after `finalizeSpawn`, before
`NoAI` freezes the mob, so size-changing variants (pufferfish, slime, salmon) also resize the YOLO
bounding box correctly.

Mobs not listed here have a single appearance and are unaffected by either flag.

## Per-mob table

Listed alphabetically. "Mechanism" is the underlying Minecraft API the variant maps to.

| Mob              | Variants (when `variants` on)                                              | Default (when `variants` off) | Mechanism                                   |
|------------------|----------------------------------------------------------------------------|-------------------------------|---------------------------------------------|
| Axolotl          | 5 colors (lucy, wild, gold, cyan, blue)                                    | Lucy                          | `Axolotl.Variant`                           |
| Cat              | 11 breeds (tabby, black, red, siamese, british shorthair, calico, persian, ragdoll, white, jellie, all black) | All black | `CAT_VARIANT` registry (biome-based)  |
| Chicken          | 3 temperature variants (temperate, warm, cold)                            | Temperate                     | `CHICKEN_VARIANT` registry (biome-based)    |
| Cow              | 3 temperature variants (temperate, warm, cold)                            | Temperate                     | `COW_VARIANT` registry (biome-based)        |
| Fox              | red, snow                                                                  | Red                           | `Fox.Variant`                               |
| Frog             | 3 temperature variants (temperate, warm, cold)                            | Temperate                     | `FROG_VARIANT` registry (biome-based)       |
| Goat             | normal, screaming                                                          | Normal (not screaming)        | `isScreamingGoat` boolean                   |
| Horse            | 7 coat colors x 5 markings                                                 | White coat, no markings       | `setVariantAndMarkings` (coat + markings)   |
| Llama            | 4 coats (creamy, white, brown, gray)                                       | Creamy                        | `Llama.Variant`                             |
| Magma Cube       | sizes 1-4                                                                   | Size 2                        | `setSize` (shares `Slime` branch)           |
| Mooshroom        | red, brown                                                                 | Red                           | `MushroomCow.Variant`                       |
| Panda            | 7 genes (normal, lazy, worried, playful, brown, weak, aggressive)         | Normal                        | main + hidden `Panda.Gene` (set equal)      |
| Parrot           | 5 colors (red/blue, blue, green, yellow/blue, gray)                       | Red/blue                      | `Parrot.Variant`                            |
| Pig              | 3 temperature variants (temperate, warm, cold)                            | Temperate                     | `PIG_VARIANT` registry (biome-based)        |
| Pufferfish       | 3 puff states (small, mid, full) - changes size                           | Small (deflated)              | `setPuffState`                              |
| Rabbit           | brown, white, black, white splotched, gold, salt (evil excluded)          | Brown                         | `Rabbit.Variant`                            |
| Salmon           | small, medium, large - changes size                                       | Medium                        | `Salmon.Variant`                            |
| Sheep            | 16 dye colors                                                              | White                         | `setColor` (`DyeColor`)                     |
| Shulker          | 16 dye colors (color is only set when variants on)                        | No color (default purple)     | `setVariant(Optional<DyeColor>)`            |
| Slime            | sizes 1-4                                                                   | Size 2                        | `setSize`                                   |
| Tropical Fish    | 22 common pattern/color combos                                            | Kob white/white               | `setPackedVariant` (`COMMON_VARIANTS`)      |
| Wolf             | 9 coats (pale, woods, ashen, black, chestnut, rusty, snowy, spotted, striped) | Pale                      | `WOLF_VARIANT` registry (biome-based)       |
| Zombie Villager  | 7 biome skins x random profession                                         | Plains skin                   | `VillagerData` (type + profession)          |

## Notes

- **Biome-based variants** (cat, chicken, cow, frog, pig, wolf, zombie villager) are the reason the
  flag exists: Minecraft picks these from the spawn biome inside `finalizeSpawn`, so without the
  reset, a "default" capture would drift with the relocation biome. The reset pins them to the
  registry default.
- **Trader Llama** shares the `Llama` branch - its `setVariant` is inherited (private on `Llama`),
  so the reflection helper walks the class hierarchy to reach it.
- **Magma Cube** shares the `Slime` branch via inheritance; the call dispatches to the right
  `setSize` override.
- **Size variants** (pufferfish, salmon, slime, magma cube) change the entity bounding box. They are
  set before `NoAI`, so the projected YOLO box matches the rendered size.
- **Panda** expresses a visible trait only when both the main and hidden gene match (recessive
  genes). We set both to the same rolled gene so every gene is actually shown.
- **Plain Villager** is not in the capture list; only **Zombie Villager** carries villager skins
  here. To add the full villager type table for a new villager class, reuse `randomizeVillager`.

## Adding a variant for a new mob

1. Confirm the mob's variant API (registry holder, enum, boolean, or size) via the IDE Index MCP /
   `javap` against the 26.1 jar.
2. Add a branch to `applyRandomVariant` (random roll) and, if `finalizeSpawn` randomizes that mob,
   a matching branch to `resetSpawnVariantToDefault` (canonical default).
3. Use `setVariantReflect` for private `setVariant(x)` setters (it walks the class hierarchy),
   `rollRegistryVariant` / `resetRegistryVariant` for registry-backed variants.
4. Add a row to the table above in alphabetical order.
