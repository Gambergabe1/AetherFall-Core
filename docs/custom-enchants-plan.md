# Aetherfall Custom Enchants: Implementation Plan

## Goals

Custom enchants should create meaningful build choices without replacing the importance of skills, collections, reforges, sets, or dungeon classes. Every enchant must have a clear item type, a visible effect, a maximum level, a reliable acquisition path, and a measurable server-side limit.

The system should use the existing native progression model. AuraSkills remains disabled, enchant effects are calculated on the server, and clients only receive display lore. An item is never trusted because of its name or lore; the enchant data must be stored in the item's persistent data container and validated against the server definition.

## Player-facing model

Each custom enchant has four visible parts:

1. Name and Roman numeral level, for example `Cultivation V`.
2. The item types it accepts, such as `HOE`, `AXE`, `SWORD`, `ARMOR`, or `FISHING_ROD`.
3. A concise description of the effect at the current level.
4. An acquisition hint showing the source, collection, or recipe tier.

The anvil and Enchanting Table should never silently destroy an existing custom enchant. Applying a duplicate enchant upgrades the level only when the recipe or upgrade cost succeeds. Incompatible enchants are rejected before consuming any item or currency.

## Data contract

The server definition belongs in `plugins/AetherCore/custom-enchants.yml`. Each definition contains:

```yaml
cultivation:
  name: "Cultivation"
  rarity: RARE
  applies-to: [HOE]
  max-level: 5
  source: FARMING_COLLECTION
  effect: farming_fortune
  per-level: 2
  description: "Increases Farming Fortune while harvesting crops."
  costs:
    1: { farming_xp: 2500, coins: 5000 }
```

The item stores only validated IDs and levels, encoded in the existing `custom_enchants` persistent data field. The runtime must clamp levels to the definition maximum and remove unknown IDs when an item is loaded or used. The item lore is regenerated from the definition so lore edits cannot create effects.

## Runtime architecture

### Definition loading

Add `CustomEnchantRegistry` beside `ItemRegistry`.

- Load `custom-enchants.yml` during plugin startup and `/aether reload`.
- Validate every ID, rarity, material group, maximum level, and numeric value.
- Reject duplicate IDs and invalid effect keys with a warning.
- Expose immutable definitions through `get(id)` and `all()`.
- Keep the existing `CustomEnchantments` PDC helper as the storage layer.

### Validation

Create one validation method used by every application path:

```java
validate(ItemStack item, String enchantId, int level, Player actor)
```

It must check:

- The item has a valid AetherCore item ID or an explicitly supported vanilla material.
- The item type is allowed by the definition.
- The requested level is between 1 and the definition maximum.
- The player meets the required skill, collection, dungeon, or slayer requirement.
- The item does not exceed the configured number of enchants for its rarity.
- The enchant is not mutually exclusive with an existing enchant.

All checks happen before removing ingredients, coins, XP, or the source book.

### Applying and upgrading

Use an `EnchantingMenu` with three inputs:

- Target item
- Enchanted book or catalyst
- Payment preview

The confirm button must show the exact result, level, cost, and any conflict. A successful operation consumes the inputs atomically, writes the PDC, regenerates lore, and saves the player data. A failed operation returns every input.

Support these application paths:

- Enchanted books from gameplay sources
- A custom Enchanting Table menu for direct upgrades
- A future dungeon anvil for dungeon-only enchants

Do not use vanilla `PrepareAnvilEvent` as the only implementation. It is difficult to guarantee safe costs and duplicate handling across versions.

### Effect calculation

Create an `EnchantEffectService` that receives a player, item, event, and context. It returns validated modifiers such as:

- Farming Fortune and Farming Speed
- Mining Fortune and Mining Speed
- Fishing Speed and Sea Creature Chance
- Damage, Strength, Crit Chance, and Crit Damage
- Defense, Health, and Mana
- Minion action time and storage capacity

The service must be deterministic for ordinary stat effects. Random effects use the server random source only after the event has passed permission, claim, world, and anti-cheat checks.

The Farming Framework should read enchant levels once per harvest and apply a single combined Fortune result. Enchants must never call `addXp` in a loop or create recursive block breaks.

## Anti-cheat and exploit controls

- Never trust display names, lore, CustomModelData, or client packets as enchant data.
- Validate the PDC payload every time an item enters an enchant menu, is equipped, or triggers an effect.
- Clamp every level to the definition maximum.
- Reject malformed payloads and log the player UUID, item ID, and source inventory.
- Apply per-player event cooldowns for harvest, mining, fishing, combat, and minion effects.
- Attribute rewards to a real server event and require Survival mode where appropriate.
- Ignore drops from placed blocks, spawners, simulated entities, and plugin-generated recursive events unless a module explicitly marks them as eligible.
- Record enchant applications and upgrades in an audit table with player UUID, item ID, enchant ID, old level, new level, cost, and timestamp.
- Add `/aether enchantaudit <player>` for staff review and `/aether enchantrepair <player>` to strip invalid IDs without granting compensation.

## Acquisition design

Enchants should enter the economy through several activities so one farm cannot dominate the entire market.

### Farming

- Wheat, carrot, potato, sugar cane, cactus, and nether wart collections unlock their matching blueprint enchant books.
- Manual harvest combos improve the chance of finding a book; automated bursts never receive the bonus roll.
- Farming events can award duplicate books, but duplicates convert into upgrade dust instead of becoming worthless.

### Mining

- Mining collections unlock Fortune, Smelting, and Core Resonance books.
- Custom cores from miners and deep ores are required for the higher levels.
- Ore duplication is calculated once per block and capped by Mining Fortune.

### Fishing

- Rare sea creatures and fishing events drop Angler's Focus, Treasure Hunter, and Deepwater books.
- A book roll requires a legitimate reel event and cannot come from minion collection.

### Combat and bosses

- Bosses drop specialized combat books through contribution-weighted loot.
- A minimum damage contribution and a per-boss cooldown prevent tag farming.
- Dungeon enchants require the appropriate floor completion and cannot be traded before that requirement is met.

### Guilds and community goals

- Community milestones can award account-bound cosmetic or utility enchants.
- Guild rewards should favor support and utility effects rather than the highest damage books.

### Crafting

Players combine duplicate books into higher levels using the existing Forge. The recipe is:

- Two identical level `N` books create one level `N+1` book.
- The higher levels require an additional catalyst and coins.
- The recipe never creates a level above the definition maximum.

## Initial specialized enchant roster

### Farming

- `Cultivation I-V`: +2 Farming Fortune per level on crop-specific blueprint hoes.
- `Turbo I-V`: +10 Farming Speed per level while using a compatible hoe.
- `Dedication I-IV`: +1 Farming Fortune per level and +1 extra Fortune after a 25 harvest combo.
- `Cane Affinity I-III`: +20 Sugar Cane Fortune per level; Sugar Cane only.
- `Cactus Affinity I-III`: +20 Cactus Fortune per level; Cactus only.
- `Replenish I`: Replants eligible crops after a valid manual harvest, consuming one seed or crop item.

### Mining

- `Core Resonance I-III`: increases the chance of a custom core from eligible natural ores.
- `Vein Discipline I-III`: grants Mining Fortune to the first block in a manual vein and reduces recursive vein rewards.
- `Deep Delver I-V`: grants Mining Speed below the configured depth threshold.

### Fishing

- `Tidecaller I-III`: increases Fishing Speed and sea creature chance during fishing events.
- `Treasure Map I-V`: increases the quality roll for legitimate treasure catches.
- `Reel Momentum I-III`: grants a short Fishing Fortune bonus after consecutive catches.

### Combat

- `Executioner I-V`: increases damage against mobs below 30% health.
- `Boss Hunter I-V`: increases boss damage only after the player has contributed to the encounter.
- `Aether Drain I-III`: restores a small amount of Mana on a valid critical hit, with a per-player cooldown.

### Minions

- `Efficient I-III`: reduces minion action time by 2% per level, capped by the minion's tier floor.
- `Deep Storage I-III`: adds minion storage capacity, never production speed.
- `Catalyst Keeper I-III`: improves fuel duration without multiplying fuel speed.

### Extended specialized enchant specifications

The following entries expand the initial roster beyond the first nine definitions. Each one is intentionally tied to an existing Aetherfall activity and has a narrow effect so it can be measured and balanced.

- `Replenish I` is a one-level hoe enchant. After a valid manual harvest, it consumes one seed or crop item from the player and replants the same block. It never creates seeds, never triggers on placed-block protection failures, and never runs during a recursive mass-break operation.
- `Harvester's Touch I-III` adds 3 Farming XP per level to an eligible manual crop harvest. It uses the same human-paced harvest gate as the Farming Framework, so macro bursts cannot turn it into an XP generator.
- `Fortune Engine I-III` raises the maximum effective Farming Fortune by one adaptive tier per level. The extra ceiling is available only while a player has an active harvest combo, which keeps the enchant useful for dedicated farmers without increasing idle farm output.
- `Deep Delver I-V` gives 15 Mining Speed per level below the configured depth. It does not work in spawn worlds, on placed blocks, or on custom blocks marked as protected. The depth rule is evaluated server-side for each block break.
- `Vein Discipline I-III` grants 5 Mining Fortune per level to the first natural ore in a connected vein. Additional blocks broken by the same vein action do not receive a second copy of the bonus.
- `Smelter I` converts only registered natural ores into their smelted drops after the normal claim, tool, and origin checks. It cannot convert custom cores, boss drops, or plugin generated blocks.
- `Excavator's Grace I-III` reduces durability cost from approved mining abilities by 4% per level. It does not make a tool unbreakable and does not apply to vanilla damage outside the mining ability path.
- `Treasure Map I-V` improves the quality roll of legitimate fishing treasure by 3 points per level. It does not increase catch frequency, duplicate a drop, or affect junk rolls.
- `Reel Momentum I-III` grants one short Fishing Speed stack per level after consecutive real catches. The stack expires when the player stops fishing, changes world, or fails the reel cooldown check.
- `Deepwater I-III` adds a 2% per-level chance for registered deepwater drops. The roll is made only after a real `PlayerFishEvent` catch and is subject to the existing rare-drop and luck caps.
- `Boss Hunter I-V` adds 2% damage per level to a boss after the player has made a valid contribution. It cannot be used to tag a boss and then remain offline or outside the encounter for credit.
- `Aether Drain I-III` restores 2 Mana per level on a real critical hit. A per-player cooldown prevents attack-speed builds from turning the enchant into unlimited Mana.
- `First Strike I-IV` adds 4% damage per level to the first valid hit against a target. The target receives a server-side timestamp so swapping weapons or rapidly firing projectiles cannot reset the bonus.
- `Guardian I-III` adds 2 Defense per level when a party or guild member is in the same active encounter. It rewards group play without granting a permanent solo stat increase.
- `Deep Storage I-III` adds 32 minion storage slots per level. It changes only the storage cap and cannot increase action speed, offline time, or generated item count.
- `Catalyst Keeper I-III` extends approved minion fuel duration by 10% per level. The fuel speed multiplier remains capped by the minion definition.
- `Compactor I-II` converts eligible stored minion resources to registered compacted items during collection. It never creates a compacted item when the recipe is missing and never compacts custom gear.

The complete staged definitions for these enchants are in `src/main/resources/custom-enchants.yml`. The registry should reject any definition whose `effect` key is not implemented by an effect service, which lets the catalog be prepared before individual activity integrations are shipped.

## Balance rules

- No single enchant should add more than 100 raw points of a primary stat before level-specific content is introduced.
- Fortune effects have a combined cap per activity and use the player’s current skill level as part of the formula.
- Damage enchants should be additive to weapon stats before the final combat multiplier, so critical hits and dungeon scaling remain predictable.
- Utility enchants should be strong enough to matter but should not create direct coin duplication.
- Every enchant needs a visible counterplay or cost: skill requirements, collection requirements, catalysts, cooldowns, or opportunity cost between mutually exclusive effects.
- New dungeon enchants must have a non-dungeon equivalent with reduced strength so outside progression stays relevant.

## Delivery phases

1. Ship the registry, YAML validation, PDC validation, lore generation, and audit logging.
2. Ship the enchanting menu and duplicate-book Forge recipes.
3. Connect Farming, Mining, Fishing, Combat, and Minion effect services one activity at a time.
4. Add acquisition tables to bosses, collections, events, and guild milestones.
5. Add dungeon-only categories after dungeon requirements and floor completion data exist.
6. Use `/aether funnel`, `/aether retention`, and enchant audit data to watch for dominant books, automation abuse, and economy inflation.

