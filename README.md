# ProsperityNPC

A Paper plugin that adds persistent town residents and a living property economy to a
Minecraft server. Residents live in the town, take part in errands, training and small
orders, and rent rooms in player-owned properties. Property owners collect rent and pay
for upkeep, repairs, upgrades and utilities, all in the server's own Vault currency.

It was built for ProsperitySMP, an economy server, and is shared so other server owners can
use it and learn from it.

## Status

Pre-release. It runs on our test server and has not shipped to a production server yet.
Every feature ships switched off, and nothing simulates, pays or renders until you turn it
on. Eight safety checks ship on, and each does nothing until the feature it guards is on.
Some features are still behind beta flags. Expect to read the config comments and test on a
copy of your server first.

## What it does

The full list, with what turns each one on, is in [FEATURES.md](FEATURES.md).

- **Residents.** A cast of three named residents (Mara Vale, Ellis Ward and Nico Reed) with
  stable identities and authored skins, shown with Citizens. You can write your own cast in
  `residents.yml` (see [Your own residents](#your-own-residents)). Players talk to nearby residents
  with `/resident`. Conversations are behind the `beta.encounters-enabled` flag.
- **Town life.** Staff place civic residents on walking routes. Residents can offer one
  courier errand per player per day, optional training (a temporary Jobs payout bonus) and
  item orders. With contracts on, residents ask players to bring them items for familiarity, and
  property owners can partner with a resident for a fee or match perk, paid in items. Contracts and
  partnerships pay no money.
- **Property economy.** Staff enroll AdvancedRegionMarket regions as properties. Simulated
  tenants move in, pay rent and move out. Owners manage their property with `/property`
  and `/tenants`: upkeep, repairs, upgrades, utilities, eviction and opt-in auto-care.
- **Money safety.** All amounts are integer cents. NPC payouts have daily caps per server,
  owner, property and tenant, and a floor on the reserve account. Every Vault payment is
  journaled before and after it runs. An unclear payment is held for review and never
  retried automatically. The training bonus is paid by Jobs, so these caps do not apply to it.
- **Bedrock support.** With Floodgate and Citizens installed, Bedrock players get native
  forms instead of chest menus in resident conversations and the owner desk.
- **Chronicle (optional).** Can post approved resident move-in stories to a Discord channel.
  Turning it on in config only stages private previews. Real sending needs a credential and
  operator approval tooling that is not included here.

## Requirements

- Paper 26.1.2 and Java 25
- MariaDB (tested with 11.8), with a dedicated schema and a user that has all privileges on
  that schema only. The plugin creates and migrates its own tables.
- Vault and a Vault economy plugin

Needed for specific features:

| feature | needs |
|---|---|
| Properties, tenants, rent, owner payments | AdvancedRegionMarket 3.5.5 with the ownership hook from [arm-compat/](arm-compat/README.md), and `integration.arm-coordination: true`. Stock ARM loads, but these features stay held. |
| Any NPC payment | Skript with skript-reflect running [skript/npc-economy-bridge.sk](skript/npc-economy-bridge.sk). Payments wait until the bridge acknowledges the plugin's accounting totals. The bridge writes those totals into `money-flow-daily::*` Skript variables, the format ProsperitySMP's money tracking uses, so adapt it to your own accounting. |
| Residents you can see and talk to, errands, training, orders | Citizens 2.0.43, and signed resident skins in `plugins/ProsperityNPC/resident-appearance.json` (see [Resident skins](#resident-skins)) |
| Training | Jobs Reborn (tested with 5.2.6.6) |
| Bedrock forms | Floodgate 2.2.5 and Citizens 2.0.43 |
| Saving linked Java and Bedrock accounts (`linked-accounts.enabled`) | Floodgate 2.2.5 with account linking on. Links already saved still count without it. |
| Repairs with items (`item-repairs.enabled`) | A price feed that fills the commerce price book with full prices for the recipe materials too. A price skript should loop `CommercePriceBook.materials()` rather than a fixed list. Until a recipe material has a fresh price, item repair is unavailable and cash still works. |

The plugin loads without the optional plugins. The features that need them stay off.

## Building

1. Install JDK 25.
2. Put the local dependency jars in `libs/`. The list and exact builds are in
   [libs/README.md](libs/README.md). `build.gradle` holds the pinned checksums.
3. Run `./gradlew build` (or `gradlew.bat build` on Windows).

The plugin jar is written to `build/libs/`. `./gradlew test` runs the unit tests and the
integration tests. The integration tests start an embedded MariaDB with MariaDB4j, so no
database install is needed for them. The Citizens linkage test is skipped when Citizens'
relocated library jars are not in `libs/`. On Linux or macOS, if `./gradlew` is not
executable, run `sh gradlew build`.

## Installing

1. Create a MariaDB schema and user for the plugin.
2. Put the jar in `plugins/` and start the server once to create `plugins/ProsperityNPC/config.yml`.
   On first start Paper downloads the MariaDB driver and HikariCP into the server's `libraries/`
   folder, so that start needs internet access.
3. Stop the server.
4. Put the database password in `plugins/ProsperityNPC/db-password.txt`, or in `database.password`.
   The file keeps the password out of the config.
5. Set `database.host`, `database.port`, `database.name` and `database.user`.
6. Set `centralbank.uuid` to the lowercase UUID of the economy account that will fund payouts
   and receive charges. Until it is set, NPC payments and the scheduled daily cycle (tenants,
   incidents, moves and rent) stay held. Payouts also stop while that account's balance would
   fall below `economy.centralbank-floor-cents` (default $35,000).
7. Start the server and check `/npcadmin health`.

Turn features on one at a time with the `switches` section and the per-module settings.
`/npcadmin reload` re-reads the switches, the daily caps and the `linked-accounts` settings.
Everything else needs a full restart. Do not use `/reload`.

## Setting up properties

1. Build and install the patched ARM from [arm-compat/](arm-compat/README.md) and set
   `integration.arm-coordination: true`.
2. List your market names and property classes in `enrollment.markets` and `enrollment.classes`.
3. Enroll each ARM sell region: `/npcproperty enroll <world> <region> <market> <class> <capacity> <daily-rent>`.
4. Turn on `switches.system-enabled` and `switches.moves-enabled` (`/npcadmin switch system on`,
   `/npcadmin switch moves on`).
5. Register the resident cast as tenants looking for a home:
   `/npcadmin roster <world> <market> <class> <max-daily-rent>`.
6. Run `/npcadmin match` to move residents in now. The scheduled daily cycle also moves them in
   at the settlement time, but only once the reserve account is set.
7. Rent and owner payments also need `switches.economy-enabled`, `economy.payments-enabled`,
   the reserve account and the Skript bridge from the requirements table.

## Resident skins

Residents only appear with authored skins. Put them in
`plugins/ProsperityNPC/resident-appearance.json`:

```json
{
  "version": 1,
  "assets": [
    { "key": "resident/v1/mara-vale/everyday", "value": "<base64 texture value>", "signature": "<base64 signature>" }
  ]
}
```

- Each resident needs its `everyday` skin. For the cast the keys are
  `resident/v1/mara-vale/everyday`, `resident/v1/ellis-ward/everyday` and
  `resident/v1/nico-reed/everyday`. Optional `work` and `leisure` skins use the same pattern.
  A resident from `residents.yml` uses its key: `resident/v1/<key>/everyday`.
- `value` and `signature` are a Mojang-signed texture property, for example from
  `https://sessionserver.mojang.com/session/minecraft/profile/<uuid>?unsigned=false` or a skin
  service such as MineSkin. Unsigned skins are refused.
- At most 114 skins and 512 KiB.
- With `residents.yml` on, a well-formed entry for a resident who is not in the cast is skipped
  and counted in `/npcadmin health`. Any other bad entry rejects the whole file.
- A resident wears its work or leisure skin when its avatar appears during that part of its
  routine. With `appearance.outfit-change-while-rendered` on, it also changes while on screen, out
  of sight of players and never mid-conversation.
- `tools/skins` in the repo draws original skins, makes outfit variants and builds this file.
  See [tools/skins/README.md](tools/skins/README.md).

Without the file, `/npcadmin health` shows "Appearance: no authored assets; resident avatars
held" and no resident spawns. `/npcadmin appearance` lists which skins each resident has.

## Your own residents

1. Copy `examples/residents.extended.yml` to `plugins/ProsperityNPC/residents.yml` and edit it.
   The key is a resident's permanent identity. Keep the first three entries to keep Mara, Ellis
   and Nico.
2. Set `residents.cast-file-enabled: true` and fully restart. `/npcadmin health` shows
   "Residents: 9 from residents.yml", or says the file was rejected and the built-in three are used.
3. Run `/npctown seed` to save the new residents. Saved residents are never changed by later edits
   to the file. Only their lines are read fresh at each startup.
4. Add each new resident's everyday skin to `resident-appearance.json`. Until then that resident
   is not shown, and `/npcadmin roster` and `/npcadmin roster-one` skip it.

Do step 2 before step 4. With `residents.cast-file-enabled` off, a skin for anyone outside Mara,
Ellis and Nico rejects the whole skin file, and no resident is shown, those three included.

To turn the cast file off again, first install a `resident-appearance.json` that has only the
generic skins and Mara, Ellis and Nico's skins. Then set `residents.cast-file-enabled: false` and
fully restart.

Lines can't contain `$` or other money signs, colour codes or control characters, and each is at
most 160 characters. Names can't contain money signs or braces, and use one plain space.
Routine, history and business lines come from the plugin, because they report facts.

## Commands

| command | who | what it does |
|---|---|---|
| `/resident` | players | Talk with nearby residents |
| `/property` | players | Manage the property you are standing in |
| `/tenants` | players | View and manage your property's tenants |
| `/npcadmin` | staff | Health, switches, reload, Chronicle, settlement and linked account tools |
| `/npcproperty` | staff | Enroll and inspect properties |
| `/npcnav` | staff | Build, validate and publish walking routes |
| `/npctown` | staff | Place civic residents on published routes. `partners [resident]` lists active partnerships by owner UUID, `partner-end <player> <case-ref>` ends one with no refund, and `contract-resolve` settles held contract handovers |
| `/residentlab` | staff | Staff-only resident testing |

## Permissions

| permission | default | grants |
|---|---|---|
| `prosperitynpc.use` | everyone | `/resident`, `/property`, `/tenants` |
| `prosperitynpc.admin` | operators | All staff commands |

## Operator recovery tools

The jar also contains offline tools for settling a stuck payment or item hand-in by hand after
an independent review: `RefundRecoveryTool`, `CommerceRecoveryTool` and `ItemRepairRecoveryTool`.
They never send money.
They need:

- root, and the server's systemd service stopped and runtime-masked
- MariaDB on `127.0.0.1:3306`, with the password in the server's owner-only
  `plugins/ProsperityNPC/db-password.txt`
- private, root-owned profile and evidence files
- a root-owned allowlist at `/etc/prosperitynpc/recovery-servers.properties`, mode 0600 or
  0400, in a directory only root can write, with one line per server:

```
minecraft=/path/to/your/server
```

The header comment of each tool lists its exact profile and evidence fields. The plugin jar
is not a standalone program, so put the database driver on the classpath, for example from
the server's `libraries/` folder:

```
java -cp ProsperityNPC.jar:mariadb-java-client-3.5.9.jar:HikariCP-7.1.0.jar:slf4j-api.jar   com.prosperitysmp.npc.economy.RefundRecoveryTool --record-completed-refund <profile> <evidence>
```

`CommerceRecoveryTool` (in `com.prosperitysmp.npc.commerce`) takes
`--reconcile-payment` or `--cancel-unstarted` with the same two files.

`ItemRepairRecoveryTool` (in `com.prosperitysmp.npc.economy`) settles one held item repair.
`/npcadmin itemrepair list` shows the reference. Its profile also names the server's own jar and
`libraries/` folder, which it uses to read the stopped server's player file. Run `--inspect` first:
it prints how many of each item the saved file holds, against the count before the hand-in, the
count if the items were taken, and the counts the plugin recorded when it took the items, if it
got that far. Then put that file's hash in the manifest and run `--resolve`.
`VERIFIED_COLLECTED` applies the repair, or gives the items back if the home changed.
`VERIFIED_NOT_COLLECTED` closes it with nothing to give back. Every decision needs an
`override_reason`. The plugin keeps no hash of the save the take made, so the tool cannot prove the
stopped server's file is that save. If the save failed and a crash put the items back, the player
can move them into a chest until the file matches the recorded counts. The tool still prints what
the counts and the file show, and refuses with that list:

- `VERIFIED_COLLECTED` fits when the recorded counts show the whole recipe taken and the file
  holds exactly the count after the take.
- `VERIFIED_NOT_COLLECTED` fits when the recorded counts show nothing taken and the file holds
  the count from before.
- With no recorded counts neither fits. A player who moved the items into a chest leaves a file
  that looks the same as a take, so the file alone is never enough. Saying collected when nothing
  was taken gives the player a free repair.

Items owed back reach the player when they type `/property repair returns`.

Contract and partnership handovers held for review are different: staff settle them in game with
`/npctown contract-resolve <contract-id> <consumed|not-consumed> <case-ref>`, with no offline tool
and no server stop. That command never gives items or money back. `consumed` writes the
familiarity the handover earned. For a partnership handover it also starts or renews the partnership
it paid for, if partnerships are on, the resident still has an offer, and the seat and home checks
still pass. The reply says which of these stopped it. `not-consumed` closes it with nothing written. Check CoreProtect or the
player's inventory first, and give items back by hand if they were taken but should not have been.
The staff alert and the player's message give the reference.

If giving back a stack was unclear, that stack is held. While it is held, no item delivery of any
kind starts for that player, and they cannot start another item repair. The staff alert, the
player's message and `/npcadmin itemrepair list` give the stack's reference. A stack that was being
given back when the server stopped is held the same way on the next start, with the same alert.
Settle it with the same tool and profile:

- The manifest names `request_nonce` (the reference) and `player_uuid`. Run `--inspect-return`.
  It prints the stack's `run_id` and `payload_sha256`, how many of that item the saved file
  holds, and the counts the plugin recorded before and after the delivery, if it got that far.
- Add `run_id`, `payload_sha256`, `player_file_sha256`, `resolution_id`, `operator`, `case_ref`,
  `notes` and `decision`, then run `--resolve-return`.
- `VERIFIED_DELIVERED` closes it. The player has the items. It fits when the recorded counts show
  the whole stack added and the file holds that count.
- `VERIFIED_NOT_DELIVERED` closes it and puts the same stack back in the player's returns, once,
  for `/property repair returns`. It fits when the recorded counts show nothing added and the file
  holds the count from before.
- With no recorded counts, or counts that show the stack added but a file without it, the tool
  cannot tell a lost save from items the player moved.
- Every decision needs an `override_reason`, for the same reason as a held item repair: nothing
  proves the file is the save the delivery made.

Both checks also compare the player file's DataVersion with the one saved with the items. After a
server upgrade an item can be saved in a new way and read as 0, so a different DataVersion is
refused. Items saved with no DataVersion are not compared, and the report says so.

`override_reason` works the same way for both: the tool refuses unless the evidence file has an
`override_reason`. Then it goes ahead and keeps the reason with the record. Use it only after checking the player another way, for example their
storage and logs. Saying not delivered when the items arrived gives them twice. Saying delivered
when they never arrived loses them.

## License

Copyright (C) 2026 vxMac.

ProsperityNPC is free software under the GNU General Public License, version 3 only
(GPL-3.0-only). See [LICENSE](LICENSE). You may use, change and share it. If you share a
changed version, you must share its source under the same license.

The ARM patch in `arm-compat/` changes Apache-2.0 licensed ARM source; see
[arm-compat/README.md](arm-compat/README.md).
