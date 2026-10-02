# AetherCore Engineering Remediation Plan

**Document status:** Living implementation plan  
**Scope:** Workstreams 2 through 10 from the server retention backlog  
**Audience:** AetherCore plugin developers, reviewers, test operators, and server administrators  
**Runtime target:** Paper 1.21.x, Java 25, SQLite-backed player data  
**Quality bar:** Production Minecraft plugin standards: deterministic behavior, safe reloads, no item duplication, no silent data loss, and clear rollback procedures.

This document turns the backlog into an implementation contract. Each workstream states the current state, the intended design, acceptance criteria, and the tests required before release. “Implemented” means the current source contains a first pass; it does not mean the feature has passed the release gates below.

## Current implementation snapshot

| Workstream | Current state | Priority | Release exit condition |
|---|---|---:|---|
| 2. Placed minions | Virtual/player-data prototype; no durable placed-object lifecycle | P0 | Crafted, placed, upgraded, collected, and picked up with transactional persistence |
| 3. Fishing encounters | Custom encounters and drops exist; limits and ownership need hardening | P1 | Bounded spawning, attribution, cleanup, and anti-abuse tests pass |
| 4. Bestiary | First pass has categories, pagination, details, and boss identifiers | P1 | Every registered mob has stable progress tracking, rewards, and migration-safe storage |
| 5. Player data | SQLite persistence exists; decoders and recovery paths need hardening | P0 | Corruption is detected, isolated, recoverable, and never silently overwritten |
| 6. Config migrations | Manual configuration growth; no complete versioned migration framework | P0 | Versioned, idempotent, backed-up migrations work from every supported release |
| 7. Kits command | EssentialsX-aware binding exists; fallback and dependency boundaries need tests | P1 | `/kits` remains safe and discoverable with or without EssentialsX |
| 8. Native skills | Native replacement for AuraSkills exists with six active skills and XP throttling | P0 | XP provenance, rewards, stat budgets, and AuraSkills migration are verified |
| 9. Equipment stats | Vanilla attributes and custom RPG stats coexist; authority is not fully audited | P0 | One authoritative calculation path is documented and enforced |
| 10. Custom enchants | GUI, PDC application, anvil combining, fishing acquisition, and several effects exist | P0 | Catalog, validation, effect handlers, and progression are complete and tested |

## Engineering standards

### Threading and Bukkit API ownership

- Bukkit, Paper, entity, inventory, world, and menu APIs run on the server thread unless Paper explicitly documents a safe asynchronous operation.
- SQLite reads and writes run asynchronously through one bounded executor. Results that mutate Bukkit state are marshalled back to the main thread.
- Every async callback checks that the player, world, entity, and record still exist before applying a result.
- A reload or shutdown cancels scheduled tasks, drains persistence writes, and closes database resources before replacing runtime state.

### Persistence and data ownership

- SQLite is the source of truth for durable progression. Runtime caches are disposable projections.
- Every record has a stable UUID or namespaced key, `created_at`, `updated_at`, and a schema version where the shape can change.
- Writes are transactional and idempotent. Repeating a save after a timeout must not duplicate items, currency, XP, or entities.
- Player-facing lore is presentation data. It is never trusted as a source of stats, enchant levels, ownership, or permissions.

### Security and duplication resistance

- Validate ownership, permissions, cooldowns, item type, target slot, and quantity on the server for every command and click action.
- Consume an input item only inside the same logical transaction as the successful output operation. If an operation fails, the input remains available.
- Do not use client-provided slot indices or display names as authorization. Read the actual inventory contents and PDC values on the server.
- All admin mutation commands require an explicit permission, an online/offline target policy, argument validation, and an audit log.

### Configuration, compatibility, and observability

- Configuration keys are namespaced, documented, and migrated by version. Unknown keys are preserved so operators do not lose local settings.
- Optional integrations are isolated behind adapters. Loading AetherCore must not fail because EssentialsX, Vault, DiscordSRV, or another optional plugin is absent.
- Log actionable events with a stable subsystem prefix and a correlation identifier for multi-step operations. Avoid logging player inventory contents or sensitive identifiers at normal level.
- Metrics should cover production errors, migration counts, dropped writes, duplicate-prevention rejections, and feature usage.

### Test and review standard

- Pure calculations and parsers receive unit tests.
- Commands, listeners, inventories, permissions, and scheduled behavior receive MockBukkit or equivalent integration tests.
- Persistence tests use a temporary database and exercise restart, rollback, malformed input, and concurrent-save scenarios.
- Every workstream has at least one negative test for duplication, unauthorized access, stale state, or malformed configuration.

## Workstream 2: Rebuild minions as real placed objects

### Design contract

Minions must be crafted as items, placed into a valid block location, and persisted as server-owned objects. A player-data counter is insufficient because it cannot represent location, protection, chunk unload behavior, or reliable pickup.

The durable record should contain:

```text
minion_id, owner_uuid, world_uuid, block_x, block_y, block_z,
type_id, tier, storage_level, fuel_id, fuel_expires_at,
last_action_at, next_action_at, stored_outputs, custom_data_version,
created_at, updated_at
```

The unique key `(world_uuid, block_x, block_y, block_z)` prevents two minions from occupying one location. `minion_id` remains stable through upgrades and pickup.

### Lifecycle

1. Crafting creates an item with a namespaced PDC type, schema version, and display metadata.
2. Placement validates the block, region protection, ownership rules, nearby limits, and chunk state before creating the database record.
3. Production calculates elapsed time from `last_action_at`; it does not run one unbounded task per minion.
4. Interaction opens the minion menu after ownership or trusted-access checks.
5. Upgrade consumes the exact recipe and writes the new tier atomically.
6. Pickup removes the record and returns one serialized minion item plus stored output. If either side fails, the transaction is retried safely.

Legacy virtual minions need a one-time migration. They should be converted into unplaced minion items with a clear claim command, never silently deleted.

### Acceptance criteria

- A crafted minion cannot produce until it is placed.
- Breaking, pickup, upgrade, chunk unload, restart, and server shutdown preserve ownership and stored output.
- Region protection and placement limits are respected.
- Two simultaneous clicks cannot double-collect, double-upgrade, or duplicate a minion.
- Production is bounded by a configurable offline cap and never creates an unlimited backlog.

## Workstream 3: Add limits and ownership to fishing encounters

Fishing encounters must be attributable, bounded, and removable. Each spawned encounter gets an encounter UUID, owner UUID, source location, type, spawn time, despawn time, and loot table version.

Implement per-player and per-region caps, spawn cooldowns, maximum lifetime, chunk-unload cleanup, and a safe despawn path. Damage and loot attribution should use the encounter record rather than the last player who interacted with an entity. Items and custom enchant books must be generated from server-side loot tables and validated before insertion into an inventory.

Acceptance tests must cover kill stealing, disconnects, unloaded chunks, simultaneous catches, full inventories, repeated cast events, and a player exceeding every configured limit.

## Workstream 4: Complete the Bestiary with pages and categories

The current Bestiary first pass already provides category navigation, pagination, detail pages, and stable custom boss identifiers. The next implementation phase should make those views data-driven and reward-complete.

Create one `BestiaryEntry` definition per registered mob with a stable ID, category, display data, discovery rule, kill rule, milestone table, reward table, and visibility policy. Store progress by `(player_uuid, entry_id)` rather than by display name or entity type alone. Boss identifiers must remain stable when the display name or configuration changes.

The menu should expose category totals, page navigation, locked-entry explanations, current/next milestone, and claimed reward state. Detail pages must show the exact requirement and reward before a player claims it. Reward claims are idempotent and recorded before the item or currency is delivered.

Required categories include ordinary mobs, elite mobs, bosses, fishing creatures, and future event mobs. A disabled entry is hidden from new menus but remains readable for migration and historical progress.

## Workstream 5: Protect player data from corruption

Add a schema metadata table containing the database version, migration history, and last successful checkpoint. Player progress writes should use transactions and a single writer queue per database.

Decoders for quests, weekly progress, collections, skills, bestiary, and custom enchant data must reject malformed fields with a typed error. The player record should be quarantined for repair while the rest of the server continues running. Never replace malformed data with an empty profile without an operator-visible warning and a recoverable backup.

Create automatic rolling backups before migrations and on clean shutdown. Add an admin diagnostic command that reports schema version, last save time, pending writes, and quarantined records without exposing private inventory data. Test interrupted writes, malformed JSON, invalid enum values, duplicate keys, disk-full behavior, and restart recovery.

## Workstream 6: Add configuration versioning and resource migrations

Every shipped configuration file needs a `config-version`. Startup compares that value with the code-supported version and runs idempotent migrations in order. A migration may add defaults, rename keys, or transform values; it must preserve operator overrides and write a backup before changing a file.

Migrations should write atomically to a temporary file, validate the result, then replace the original. If validation fails, retain the original and report the exact path and key. Reload and restart must produce the same final file. Include a dry-run mode for administrators and a migration report in the startup log.

## Workstream 7: Make the kits command safe with or without EssentialsX

Register AetherCore's command executor independently of EssentialsX. If EssentialsX is present, use an adapter for its kit provider; if it is absent, show the native kit GUI or a clear capability message. Keep optional dependency classes out of required class-loading paths.

Kit claims need permission checks, cooldown storage, one-time claim state where applicable, inventory-space handling, and an atomic item delivery path. The command must provide useful tab completion and never consume a claim when the inventory cannot accept the result. Test startup with EssentialsX installed, absent, disabled, and replaced by an incompatible version.

## Workstream 8: Complete the native skills reward and stat audit

The native system currently owns six active skills and applies XP throttling. Finish the system by defining a versioned skill catalog with XP sources, anti-abuse rules, level curve, milestone rewards, and stat rewards. Locked future skills must be represented as disabled definitions rather than unreachable menu code.

Each XP source needs provenance: event type, world, player, action signature, cooldown, and maximum contribution per time window. Repeated block breaks, cancelled events, fake entities, and automated loops must not grant XP. XP calculations are server-side and are never accepted from commands or client metadata.

For the AuraSkills migration, map old skill IDs and levels through an explicit table, preserve unsupported progress in an audit record, and provide a dry-run report before committing. Verify that a migrated player cannot receive duplicate level rewards.

## Workstream 9: Establish one authoritative equipment stat system

Choose AetherCore's RPG stat model as the authoritative source for health, defense, strength, crit chance, crit damage, intelligence/mana, speed, and fortune. Vanilla attributes may be used only for the agreed presentation or compatibility layer; they must not silently apply the same stat a second time.

Define a stat snapshot pipeline:

```text
base stats -> armor pieces -> weapon -> accessories -> skills
-> enchants -> temporary effects -> caps -> final snapshot
```

The snapshot is recalculated on join, respawn, inventory change, equipment change, skill level-up, enchant change, and timed-effect expiry. Add a staff-only diagnostic view that reports each contribution and the final capped value. Document stacking, additive versus multiplicative modifiers, rounding, and dungeon scaling before changing live values.

The armor rework should use stable internal IDs and migration aliases so existing items retain their intended set identity. Set bonuses must validate the complete equipped set and update when one piece changes. Weapons need a single damage formula with explicit strength and crit inputs so future dungeons can balance around predictable values.

## Workstream 10: Finish the secure custom enchant system

The current implementation provides an `/enchants` menu, PDC-backed application, anvil combining, fishing acquisition, and active hooks for farming, mining, fishing, and combat effects. The remaining work is to make the catalog and effect pipeline complete and reviewable.

Every enchant definition must declare:

```text
id, display_name, rarity, max_level, valid_targets,
incompatible_ids, source_tables, per_level_effect,
cost_curve, catalog_version, enabled
```

Books are identified by namespaced PDC values and catalog IDs. Lore is generated from the definition and is not trusted for application. Applying a book validates the target item, current level, incompatibilities, maximum level, permission/source restrictions, and inventory state in one server-side operation. Combining equal-level books produces the next level only when below maximum; unequal levels follow a documented policy and cannot bypass the cap.

Fishing, mobs, collections, quests, and shops should use weighted source tables with pity protection where appropriate. Each award records source, definition version, level, and transaction ID for audit and duplicate recovery. Effect handlers must be separated by domain and must fail closed when an unknown catalog ID is encountered.

Add tests for invalid PDC, renamed items, incompatible enchants, max-level combinations, full inventories, cancelled anvil events, logout during application, and repeated event delivery.

## Cross-system verification plan

- **Unit:** stat formulas, XP curves, loot weights, configuration migrations, PDC codecs, and progress decoders.
- **Integration:** commands, permissions, inventory menus, listeners, scheduled production, reload, and shutdown.
- **Persistence:** fresh database, upgrade database, malformed record, interrupted transaction, restore from backup, and concurrent saves.
- **Abuse:** duplicate clicks, packet/event repetition, reconnect races, full inventories, unauthorized targets, and rapidly repeated actions.
- **Performance:** thousands of minions and bestiary records, chunk unload/reload, menu pagination, and bounded database queue latency.
- **Staging:** a clean server and an upgraded server run a soak test before release. Capture logs, database metrics, memory usage, and player-facing errors.

## Release and rollback procedure

1. Build the jar and record the plugin version, supported Paper version, database schema version, and configuration versions.
2. Back up the database and configuration files; verify that the backup can be opened.
3. Run migrations in a staging copy and review the generated report.
4. Run the full automated suite, then a manual smoke test covering join, menus, commands, item flows, combat, fishing, skills, and shutdown.
5. Deploy to a canary server or a small maintenance window. Monitor errors, pending writes, duplicate-prevention rejections, and tick impact.
6. Roll back the jar and restore the pre-migration backup if data integrity, tick time, or player-facing functionality regresses. Never roll back a database without first stopping writes.

## Definition of Done

A workstream is complete only when its design is documented, configuration and permissions are listed, migrations are idempotent, server-thread boundaries are reviewed, positive and negative tests pass, logs and metrics are present, and the feature has been exercised on both a fresh and an upgraded server. The release notes must state any intentional behavior change and any operator action required.

The recommended delivery order is P0 data and migration safety first, then equipment and skills authority, then minion persistence, then custom enchant completion, followed by fishing, Bestiary rewards, and kit compatibility. This ordering prevents later systems from building on unstable identity, stat, or persistence rules.
