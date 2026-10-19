# ProsperityNPC

ProsperityNPC is a Paper plugin that adds town residents and a small property economy to a Minecraft
server. Residents have names, homes and daily routines. They walk your town on routes your staff build.
They rent rooms in homes that players own. Owners collect rent and pay for upkeep, repairs, upgrades and
utilities, in your server's own currency.

I built it for ProsperitySMP, my economy server. I'm sharing it so other server owners can use it and
learn from it.

## Status

This is a beta: 0.2.0-dev.68.

- It passed the full set of automated tests.
- This exact jar was also started from the console on a local Paper 26.1.2 test server with MariaDB
  11.4.5, with no players connected.
- It has not been tested with real Java or Bedrock players yet.
- It has not been load tested. The 50 to 80 player case has not been measured.
- Real player testing and load testing come later.

Test it on a copy of your server first. [RELEASE-NOTES.md](RELEASE-NOTES.md) has what is new, how it was
tested and the known limitations. New server owners can start with
[docs/GETTING-STARTED.md](docs/GETTING-STARTED.md).

## What it does

[FEATURES.md](FEATURES.md) lists every feature and what turns it on.

- **Residents.** Three named residents to start with: Mara Vale, Ellis Ward and Nico Reed. You can write
  your own cast. Players talk to them with `/resident`. Residents are shown with Citizens.
- **Town life.** Residents walk published routes. They can give players a courier errand, a short Jobs
  training bonus, small item orders, contracts and partnerships. Contracts and partnerships pay no money.
- **Homes and rent.** Staff enroll player-owned plots. Residents move in as tenants and pay the owner a
  small daily rent. Owners manage their home with `/property` and `/tenants`.
- **Owner costs.** A daily utility bill, upkeep, repairs, upgrades, insurance and evictions. You can add
  your own bills and taxes.
- **Money limits.** Every amount is whole cents. Payouts have daily caps per server, owner, home and
  tenant. Payouts stop before the reserve account drops below a floor you set.
- **Bedrock.** With Floodgate and Citizens, Bedrock players get forms instead of chest menus.
- **Chronicle (optional).** Approved resident move-in stories can be posted to a Discord channel. Turning
  it on in config only makes private previews. Sending needs a credential and approval tools that are not
  included here.

## Requirements

- Paper 26.1.2 and Java 25. Tested on Paper 26.1.2 only.
- MariaDB, tested with 11.4.5. Give the plugin its own schema and a user with rights on that schema only.
  The plugin creates and updates its own tables.
- For payments, a money source. Vault with a Vault economy plugin is the default. Vault is optional.
  Without a money source the plugin still loads and payments stay off.

Optional plugins, each needed only for its own features:

| Feature | Needs |
|---|---|
| Homes, tenants, rent, owner payments | A home plugin. The default is AdvancedRegionMarket 3.5.5 with the hook from [arm-compat/](arm-compat/README.md) and `integration.arm-coordination: true`. See [Home plugins](#home-plugins). |
| Any NPC payment | A money source, plus confirmed accounting totals. See [Accounting](#accounting). |
| Residents players can see and talk to, errands, training, orders | Citizens 2.0.43 and signed skins (see [Resident skins](#resident-skins)) |
| Training | Jobs Reborn, tested with 5.2.6.6 |
| Bedrock forms | Floodgate 2.2.5 and Citizens 2.0.43 |
| Linking a player's Java and Bedrock accounts (`linked-accounts.enabled`) | Floodgate 2.2.5 with account linking on. Links already saved still count without it. |
| Resident shops and repairs with items | A price feed: a Skript with skript-reflect, or a plugin. See [docs/PLUG-IN-POINTS.md](docs/PLUG-IN-POINTS.md#price-feed). |

[docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) lists every plugin and version I tested with.

Item hand-ins and deliveries need player data on a disk that keeps the same file ID when a file is saved.
The plugin checks this at startup and holds those features if it cannot. If that happens, check the
server log, fix the storage path and restart.

## Installing

1. Create a MariaDB schema and a user for the plugin.
2. Put the jar in `plugins/` and start the server once. This creates `plugins/ProsperityNPC/config.yml`.
   On this first start Paper downloads the MariaDB driver and HikariCP into `libraries/`. The server
   needs internet access for that.
3. Stop the server.
4. Put the database password in `plugins/ProsperityNPC/db-password.txt`. You can use
   `database.password` instead.
5. Set `database.host`, `database.port`, `database.name` and `database.user`.
6. Set `centralbank.uuid` to the lowercase UUID of the account that pays rent and receives owner
   payments. Until it is set, payments and the daily cycle stay held. Payouts also stop while they would
   take that account below `economy.centralbank-floor-cents` (default $35,000).
7. Start the server. Run `/npcadmin health` and `/npcadmin diagnose`.

Do not use `/reload`. Always do a full restart.

## First setup

Everything ships switched off. Nothing walks, pays or charges until you turn it on. Nine safety checks
ship on, and each does nothing until the feature it guards is on. Some features are also behind beta
flags (`beta.encounters-enabled`, `beta.resident-lab-enabled`).

- The five switches are `system`, `economy`, `incidents`, `moves` and `rendering`. Use
  `/npcadmin switch <name> <on|off>`.
- `/npcadmin reload` reads the switches, the daily caps and the settings marked as reloadable in
  [config.yml](src/main/resources/config.yml) again, including bill policies.
- `money.source`, `property.backend` and `accounting.sink` need a full restart.

Turn on one area at a time and check `/npcadmin health` after each.
[docs/GETTING-STARTED.md](docs/GETTING-STARTED.md) walks through it step by step.

## Residents

The default cast is Mara Vale, Ellis Ward and Nico Reed. Each has a fixed identity, household and
personality. They survive restarts, upgrades and evictions. A resident only appears with a signed skin.

### Your own residents

1. Copy `examples/residents.extended.yml` to `plugins/ProsperityNPC/residents.yml` and edit it. The key
   is a resident's permanent identity. Keep the first three entries to keep Mara, Ellis and Nico. The
   file holds up to 32 residents.
2. Set `residents.cast-file-enabled: true` and fully restart. `/npcadmin health` shows
   "Residents: 9 from residents.yml", or says the file was rejected and the built-in three are used.
3. Run `/npctown seed` (needs `town.enabled`) to save the new residents. Later edits to the file never
   change a saved resident. Only their lines are read fresh at each startup.
4. Add each new resident's everyday skin to `resident-appearance.json`. Until then that resident is not
   shown, and `/npcadmin roster` and `/npcadmin roster-one` skip it.

Do step 2 before step 4. With the cast file off, a skin for anyone other than Mara, Ellis and Nico rejects
the whole skin file, and no resident is shown.

To turn the cast file off again, first put back a `resident-appearance.json` with only the generic skins
and Mara, Ellis and Nico's skins. Then set `residents.cast-file-enabled: false` and fully restart.

Lines can't contain `$` or other money signs, colour codes or control characters. Each line is at most
160 characters. Names can't contain money signs or braces, and use single spaces. Routine, history and
business lines come from the plugin.

### Resident skins

Put signed skins in `plugins/ProsperityNPC/resident-appearance.json`:

```json
{
  "version": 1,
  "assets": [
    { "key": "resident/v1/mara-vale/everyday", "value": "<base64 texture value>", "signature": "<base64 signature>" }
  ]
}
```

- Each resident needs an `everyday` skin. For the default cast the keys are
  `resident/v1/mara-vale/everyday`, `resident/v1/ellis-ward/everyday` and
  `resident/v1/nico-reed/everyday`. Optional `work` and `leisure` skins use the same pattern. A resident
  from `residents.yml` uses its own key: `resident/v1/<key>/everyday`.
- `value` and `signature` are a Mojang-signed texture, for example from
  `https://sessionserver.mojang.com/session/minecraft/profile/<uuid>?unsigned=false` or a skin service
  such as MineSkin. Unsigned skins are refused.
- At most 114 skins and 512 KiB.
- With the cast file on, a good entry for someone not in the cast is skipped and counted in
  `/npcadmin health`. Any other bad entry rejects the whole file.
- A resident wears its work or leisure skin when it appears during that part of its day. With
  `appearance.outfit-change-while-rendered` on, it also changes while on screen, when no player is close
  and nobody is talking to it.
- `tools/skins` draws original skins, makes outfit versions and builds this file. See
  [tools/skins/README.md](tools/skins/README.md).

Without the file, `/npcadmin health` shows "Appearance: no authored assets; resident avatars held" and no
resident spawns. `/npcadmin appearance` lists which skins each resident has.

## Walking routes

Residents only walk routes your staff publish. They never teleport and never leave the route.

With a block palette that names your sidewalk and road blocks:

- Residents walk on sidewalk blocks, plus any `access` blocks you list for paths and doorsteps.
- They step onto a road only to cross it, straight over to the sidewalk opposite.
- They use a painted crosswalk when it is within `nav.street.crosswalk-reach` blocks of detour (default
  12). Otherwise they cross straight over.
- They never start or end a trip on a road.

Palettes go under `nav.palettes`. The commented example in [config.yml](src/main/resources/config.yml)
shows the format, with `sidewalk`, `road`, `crossing` and `access` lists. Check one with
`/npcnav palette <name>`.

Staff have three ways to build a route:

- **Scan.** `/npcnav scanbox` reads an area through a palette into a draft.
- **Waypoints.** `/npcnav wp add` places named points and `/npcnav wp link` joins them with `walk`,
  `cross` or `crosswalk` links. `/npcnav wp build` fills in the blocks and checks them against the world.
  A palette is required by default (`nav.waypoints.require-palette`).
- **Route files.** `/npcnav import` reads a node and edge file from `plugins/ProsperityNPC/nav-import/`.
  `/npcnav export` writes one.

Every draft is checked with `/npcnav audit` and goes live with `/npcnav publish`. Route commands work for
staff as soon as the database is connected. The full guide is in
[docs/PLUG-IN-POINTS.md](docs/PLUG-IN-POINTS.md#pathfinding-styles).

## Money

Every payment moves money between a player and the reserve account (`centralbank.uuid`). Rent comes out
of the reserve. Owner payments go into it. The plugin never creates money.

- The default money source is Vault (`money.source: vault`). I test with EssentialsX 2.22.0.
- Balances with a fraction of a cent work. The usable balance is rounded down to a whole cent. EssentialsX
  can report a balance a tiny fraction of a cent low, and the plugin reads it as the cents it stands for.
  [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md#how-essentialsx-balances-are-read) has the details.
- Every payment is written down before and after it runs.
- A payment refused before any money moves is declined. Nothing moves and other payments carry on.
- With `alerts.enabled` on, staff get an alert when a refusal looks like a fault, or after 5 refusals in
  a row with nothing paid between. An owner without enough money does not count toward those 5.
- A payment with an unclear result is held for staff. It is never retried or refunded on its own. NPC
  payments pause until staff settle it. See [Operator recovery tools](#operator-recovery-tools).
- A payment that would take the receiving account above $900,000,000 is refused before any money moves.

Staff alerts are off by default (`alerts.enabled: false`).
[skript/npc-alerts-prosperitysmp.sk](skript/npc-alerts-prosperitysmp.sk) is an example that shows them in
game.

## Swappable parts

Three parts can be swapped in `config.yml`. Leaving them out keeps Vault, ARM and the Skript bridge.
Changing `money.source`, `accounting.sink` or `property.backend` needs a full restart.
`property.when-unguarded` is read again on `/npcadmin reload`.
[docs/PLUG-IN-POINTS.md](docs/PLUG-IN-POINTS.md) covers each one, and [docs/ADAPTERS.md](docs/ADAPTERS.md)
explains how another plugin adds its own.

```yaml
money:
  source: vault                    # vault, or the id another plugin registers
accounting:
  sink: skript                     # skript or internal
property:
  backend: arm                     # arm, worldguard-listed, or an adapter's id
  when-unguarded: hold-payouts     # hold-payouts or reread-before-payout
```

### Money source

`vault` is built in. Another plugin can register its own money source, and you choose it by its id.

### Home plugins

| Home plugin | `property.backend` | Status in this beta | Rent by default |
|---|---|---|---|
| AdvancedRegionMarket 3.5.5, patched | `arm` | Built in, the default | Paid |
| WorldGuard regions | `worldguard-listed` | Built in. Reads region owners only. Not yet checked against a real WorldGuard server. | Held |
| GriefPrevention 16.18.7 | `griefprevention` | Code and tests only. Not checked on a real server. No jar. | |
| Realty 2.0.0 | `realty` | Adapter jar attached to the pre-release. Booted once on a local test server. No real players. Unverified. | Held |
| Residence, Lands, PlotSquared | | Not included yet | |

- **ARM.** Needs the patched build from [arm-compat/](arm-compat/README.md) and
  `integration.arm-coordination: true`. Stock ARM loads, but homes, rent and owner payments stay held.
  The patch is only needed with `property.backend: arm`.
- **WorldGuard.** Each home region needs exactly one owner, as a player UUID. If WorldGuard cannot be
  read, nothing is paid.
- **Realty.** The adapter pays the title holder of a plot, or the tenant of a rented unit. Rented subregions
  are supported. A vacant unit has no payer. Realty has no guard that can stop an owner change,
  so rent is held by default. It needs Realty 2.0.x, WorldGuard, an economy and Realty's own MySQL or
  MariaDB database. Put the adapter jar from the pre-release in `plugins/` and set
  `property.backend: realty`. It is compiled against the Realty 2.0.0 API jars and was booted once on a
  local test server. Enrolling a home, real players, real payouts and load were not checked.
- Each home remembers which home plugin enrolled it. If you change `property.backend`, homes enrolled
  under the old one stay out of payments and moves until you set it back. The owner desk only shows
  homes enrolled under the home plugin you have set.
- If an enroll is refused because the home is already enrolled, paused, or enrolled under another home
  plugin, the reply says which.

To set up homes with any home plugin:

1. List your market names and home classes in `enrollment.markets` and `enrollment.classes`.
2. Stand in each home and run `/npcproperty enroll <world> <region> <market> <class> <capacity> <daily-rent>`.
   Capacity is 1 to 4 residents. With ARM, only sell regions can be enrolled.
3. Turn on the `system` and `moves` switches.
4. Run `/npcadmin roster <world> <market> <class> <max-daily-rent>` to list the residents as tenants
   looking for a home.
5. Run `/npcadmin match` to move them in now, or wait for the daily cycle.
6. Rent and owner payments also need the `economy` switch, `economy.payments-enabled`, the reserve
   account and confirmed accounting.

### Accounting

Before it pays anything, the plugin needs its daily money totals confirmed.

- `skript`, the default, waits for the Skript bridge in
  [skript/npc-economy-bridge.sk](skript/npc-economy-bridge.sk). It needs Skript and skript-reflect. The
  bridge writes `money-flow-daily::*` variables for your own money tracking.
- `internal` lets the plugin confirm its own totals. No Skript is needed. Nothing is sent to Skript.

## Home plugins that cannot block owner changes

Patched ARM blocks owner changes while a payment runs. Some home plugins cannot, for example plain
WorldGuard.

- With those, rent and appreciation payouts are held by default (`property.when-unguarded: hold-payouts`).
- `property.when-unguarded: reread-before-payout` is an opt-in. Payouts then run after the owner is read
  again right before each one. A change in the moment between that read and the payout is not caught.
- Owners can still pay bills, buy upgrades and pay for repairs.
- With any home plugin other than ARM, the plugin reads the owner of every home once after each start.
  Payouts stay held until that check finishes with every home readable.
- If a home plugin does not say how it tells homes apart, the plugin treats it as unable to block owner
  changes.
- Payments are held when a home plugin's adapter cannot be found, or when its homes could not be loaded
  at startup.

## Bills and property settings

### Bills and taxes

You can write your own daily bills and taxes in a `bills:` block, for example electricity or a property
tax. The commented example in [config.yml](src/main/resources/config.yml) shows the format.

- Bills are off by default (`bills.enabled: false`).
- To turn them on, set `bills.enabled: true` and set both `bills.limits` values. Without both, the bills
  block is refused.
- Once a day, after rent, each home gets at most one bill. Its amounts are fixed when it is issued.
- Owners see bills with `/property bills` and pay with `/property bills pay <bill> <maximum-dollars>`, or
  from the owner desk.
- Automatic collection is built but off (`bills.auto-collect-enabled: false`).
- An unpaid bill moves no money and adds no debt or late fee. It expires after `bills.payable-days`
  (default 7).
- An unpaid bill has no penalty unless you set `bills.on-unpaid: hold-rent`.
- Bills never fund rent.
- Staff use `/npcadmin bills status` and `/npcadmin bills preview <property>`.

Bills have fixed ceilings:

| Limit | Ceiling | Set it lower with |
|---|---|---|
| Each bill line | $200 | the policy's `max-cents` |
| The whole bill for one home on one day | $200 | Fixed. No config key. |
| One owner, per day | $1,000 | `bills.limits.per-owner-daily-max-cents` |
| The whole server, per day | $25,000 | `bills.limits.server-daily-max-cents` |

A value above its ceiling is refused when the bills block loads. Each line is charged at most its
policy's `max-cents`. When the whole-bill limit or a daily limit is reached, lines that don't fit are left
off whole. With `alerts.enabled` on, staff then get a `BILL_CAP_REACHED` alert.

Each policy has a formula, variables, a version and limits. Raise the policy's `version` whenever you
change any key of it. Changed policies start on the next economy day. A bad block on reload keeps the last
good one.

### Hold-rent

`bills.on-unpaid: hold-rent` is off by default. With it on, a home earns no rent while its current owner
has an unpaid bill for that home from an earlier day. The owner is told why in `/property bills` and on
the owner desk. A bill only holds rent for the owner it was issued to. After a sale, the old owner's bill
does not hold the new owner's rent. Rent that was held is not paid later.

With hold-rent on, rent waits at startup until the bills settings have loaded.

### Meters

A bill can read a number to charge on. `flat` is always 1. `derived:<variable>` uses `tenants`,
`upgrade_levels`, `advertising_level`, `facilities_level` or `capacity`. Another plugin can register its
own meter. No meter for a power plugin ships with ProsperityNPC. The full rules for bills, formulas and
meters are in [docs/PLUG-IN-POINTS.md](docs/PLUG-IN-POINTS.md#power-meters-bills-and-taxes).

Once the first bill is issued, an older jar cannot read the database. See [Upgrading](#upgrading).

### Owner prices

The built-in owner charges are set in `config.yml`, in quoted whole cents:

- `owner-prices`: maintenance, eviction, upgrades, and `insurance-cents`, the seven-day premium for homes
  of capacity 1 to 4.
- `utility-prices`: the daily utility bill's base, per tenant and per upgrade level. The base must be
  above zero. The other two can be zero.
- `repair-prices`: `pests-cents`, `plumbing-cents` and `break-in-cents`, from 1 to 100,000,000 cents.
  The defaults are $20, $35 and $50.

Rules for all three:

- A change needs a full restart. `/npcadmin reload` reports a pending or invalid change and keeps the
  prices in use.
- A missing block keeps the shipped prices.
- An invalid block at startup disables the plugin. It never charges a fallback price.
- A price above $8,388,607.99 rejects the block.
- Accepted purchases, issued bills and open problems keep the price they were made at.
- Once a custom-priced charge or purchase is saved, an older jar cannot read it. Roll forward, or restore
  a backup of both the database and the plugin taken while the server was stopped.

Enrollment takes a capacity of 1 to 4 residents. Local price checks are recorded in
[docs/testing/OWNER-PRICE-CHECKS.md](docs/testing/OWNER-PRICE-CHECKS.md).

## Diagnose and health

- `/npcadmin diagnose` lists the money source, home plugin and accounting setting in use, the ones found
  but not used, and why any was refused. If you changed `money.source` or `property.backend` and only
  reloaded, it says a restart is required.
- `/npcadmin health` shows the switches, the database, the home plugin and the last owner check, payments,
  accounting, the reserve, the caps and the floor. More lines show while bills, linked accounts,
  contracts or partnerships are on.

The startup log also names the parts in use in one line, for example
`Pieces: money=vault property=arm accounting=skript`.

## Commands

| Command | Who | What it does |
|---|---|---|
| `/resident` | players | Talk with nearby residents |
| `/property` | players | Manage the home you stand in, including bills, repairs, upgrades and rent |
| `/tenants` | players | View and manage your tenants |
| `/npcadmin` | staff | Health, diagnose, switches, reload, bills, settlement, roster, linked accounts, Chronicle |
| `/npcproperty` | staff | Enroll, pause, resume and inspect homes |
| `/npcnav` | staff | Build, check and publish walking routes |
| `/npctown` | staff | Place residents in town. Also `partners`, `partner-end` and `contract-resolve`. |
| `/residentlab` | staff | Resident testing for staff |

## Permissions

| Permission | Default | Grants |
|---|---|---|
| `prosperitynpc.use` | everyone | `/resident`, `/property`, `/tenants` |
| `prosperitynpc.admin` | operators | All staff commands |

## Upgrading

1. Stop the server and back up the plugin's database.
2. Put in the new jar and start the server. Upgrading from 0.2.0-dev.63 or older runs database migrations
   V41 to V56 on the first start, whatever your switches say.
3. Check the log, `/npcadmin health` and `/npcadmin diagnose`.

- Once the first bill is issued, an older jar cannot read the database. From then on you can only roll
  forward. The same goes for the first custom-priced charge or purchase.
- To go back, stop the server and restore the backup of both the database and the plugin jar.
- Vault is now optional.
- The patched ARM and `integration.arm-coordination: true` are only needed with `property.backend: arm`,
  the default.

[docs/GETTING-STARTED.md](docs/GETTING-STARTED.md#upgrading-and-rolling-back) has more.

## Operator recovery tools

The jar has three offline tools for settling a held payment or item hand-in by hand:
`RefundRecoveryTool`, `CommerceRecoveryTool` and `ItemRepairRecoveryTool`. They never send money. Staff
check what happened first, make any refund themselves, and then record it with the tool. Each tool takes
two files: a profile, then an evidence file. For item repairs the evidence file is called the manifest.
The header comment of each tool lists the exact fields. [FEATURES.md](FEATURES.md) has a short summary of each.

### What they need

- root, and the server's systemd service stopped, and masked with `systemctl mask --runtime`
- MariaDB on `127.0.0.1:3306`, with the password in the server's owner-only
  `plugins/ProsperityNPC/db-password.txt`
- private, root-owned profile and evidence files
- a root-owned allowlist at `/etc/prosperitynpc/recovery-servers.properties`, mode 0600 or 0400, in a
  directory only root can write, with one line per server:

```
minecraft=/path/to/your/server
```

Held rows written by an older build also need `/etc/prosperitynpc/legacy-provenance.properties` in the
same directory, root-owned, mode 0600 or 0400. Without it, those rows stay held.

```
server_root=/path/to/your/dev/server
backup_dir=/path/to/your/stopped/backups
```

The plugin jar does not run on its own, so put the database driver on the classpath, for example from the
server's `libraries/` folder:

```
java -cp ProsperityNPC.jar:mariadb-java-client-3.5.9.jar:HikariCP-7.1.0.jar:slf4j-api.jar   com.prosperitysmp.npc.economy.RefundRecoveryTool --record-completed-refund <profile> <evidence>
```

### Refunds

`RefundRecoveryTool` records a refund staff already made by hand. Make the refund with the server
stopped, so it is the payer's last balance change. The `refund-observed.json` file beside the evidence
names the payer, the held payment it refunds and the balance before and after. The tool checks it against
the balance the stopped server saved. It accepts a balance EssentialsX reports a tiny fraction of a cent
low.

For a configured bill, the tool also checks the bill's payment receipt and its history. The bill then
stays unpaid. A prepared bill with no payment attempt is not covered by the tool and stays held. The
offline steps for configured bills have not been checked end to end on Linux yet.

### Paid orders

`CommerceRecoveryTool` (in `com.prosperitysmp.npc.commerce`) takes `--reconcile-payment` or
`--cancel-unstarted` with the same two files. `--inspect-delivery` reports one held order and writes
nothing. There is no in-tool decision for a held order yet.

With `town.commerce.delivery-save-marker` on, the default, the plugin saves the player before handing
over an order and checks that save. If the save cannot be read back, or the items changed, the player sees
"Collection is busy" and can collect again. Nothing is given or held. With `alerts.enabled` on, staff get
an alert after the third refusal of the same order.

### Item repairs

`ItemRepairRecoveryTool` (in `com.prosperitysmp.npc.economy`) settles one held item repair, or one stack
that was being given back. `/npcadmin itemrepair list` shows the reference.

1. Run `--inspect` (or `--inspect-return` for a stack). It prints what the stopped server's player file
   holds against the counts the plugin recorded.
2. Put the file's hash and your decision in the manifest, the second file you pass to the tool. Run
   `--resolve` (or `--resolve-return`).

- `VERIFIED_COLLECTED` applies the repair, or gives the items back if the home changed. It fits when the
  recorded counts show the whole recipe taken and the file holds the count after the take.
- `VERIFIED_NOT_COLLECTED` closes it with nothing to give back. It fits when nothing was taken and the
  file holds the count from before.
- `VERIFIED_DELIVERED` closes a held stack. `VERIFIED_NOT_DELIVERED` puts it back in the player's
  returns once, for `/property repair returns`.
- With no recorded counts, the tool cannot tell a lost save from items the player moved into a chest.
- Every decision needs an `override_reason`. Check the player another way first, for example their
  storage and logs. Saying not delivered when the items arrived gives them twice.
- If the player file's DataVersion differs from the one recorded with the items, the tool prints the
  mismatch. Items recorded with no DataVersion are not compared.

While a stack is held, no item delivery starts for that player and they cannot start another item repair.

### Contracts and partnerships

Held contract and partnership handovers are settled in game, with no server stop:
`/npctown contract-resolve <contract-id> <consumed|not-consumed> <case-ref>`. It never gives items or
money back. `consumed` writes the familiarity earned. For a partnership it also starts or renews it if
the checks still pass. `not-consumed` closes it with nothing written. Check CoreProtect or the player's
inventory first.

## Building from source

1. Install JDK 25.
2. Put the local dependency jars in `libs/`. The list and exact builds are in
   [libs/README.md](libs/README.md). `build.gradle` holds their checksums.
3. Run `./gradlew build`, or `gradlew.bat build` on Windows. On Linux or macOS, if `./gradlew` is not
   executable, run `sh gradlew build`.

The jar is written to `build/libs/`. `./gradlew test` runs the unit and integration tests. The
integration tests start their own MariaDB, so you don't need one installed. The Citizens test is skipped
when Citizens' library jars are not in `libs/`.

- The GriefPrevention adapter's tests run with `-Padapters`. The Realty adapter's tests run with `-Padapters`
  too. Neither can be built into a jar from this tree yet. See [docs/ADAPTERS.md](docs/ADAPTERS.md) before you build an adapter.
- `./gradlew seamFaultChecks` breaks the swappable parts on purpose in 10 places and checks the tests
  catch each one. `./gradlew billFaultChecks` does the same for billing in 24 places.
  [tools/faults/README.md](tools/faults/README.md) explains both.
- Hand-run checks from my test server are in [docs/testing/README.md](docs/testing/README.md).

## License

Copyright (C) 2026 vxMac.

ProsperityNPC is free software under the GNU General Public License, version 3 only (GPL-3.0-only). See
[LICENSE](LICENSE). You may use, change and share it. If you share a changed version, you must share its
source under the same license.

The adapter API is part of the plugin and under the same license. An adapter you share links against it,
so it must use a GPL-compatible license.

The ARM patch in `arm-compat/` changes Apache-2.0 licensed ARM source. See
[arm-compat/README.md](arm-compat/README.md).
