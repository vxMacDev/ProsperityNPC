# Getting started with ProsperityNPC

ProsperityNPC adds town residents and a small property economy to a Paper server. This page covers what
the plugin does, what it needs, how to install it, and how to turn it on safely.

The pieces you can swap (money, home plugin, bills, routes) are described in
[PLUG-IN-POINTS.md](PLUG-IN-POINTS.md). Supported plugins are listed in
[COMPATIBILITY.md](COMPATIBILITY.md).

## What the plugin does

- **Residents.** A cast of named residents lives in your town. They walk routes that your staff build.
  Players can talk to them, run errands for them, train with them and buy supplies from them.
- **Homes.** Staff enroll player-owned plots as homes. Residents move in as tenants. Each tenant pays
  the owner a small daily rent.
- **Owner costs.** Owners pay a daily utility bill. They also pay for repairs, upgrades, maintenance,
  insurance and evictions. You can add your own bills and taxes.
- **Money.** Every payment moves money between a player and one reserve account that you choose. Rent
  comes out of the reserve. Owner payments go into it. The plugin never creates money.
- **Limits.** Payouts have daily caps per server, per owner, per home and per tenant. Payouts stop while
  they would take the reserve below a floor you set.
- **Records.** Every payment is written down before and after it runs. A payment with an unclear result
  is held for staff. It is never retried or refunded on its own.

[FEATURES.md](../FEATURES.md) lists every feature and what turns it on.

## Status

ProsperityNPC 0.2.0-dev.68 is a beta. It has not been tested with real Java or Bedrock players or under
a full player load yet. [RELEASE-NOTES.md](../RELEASE-NOTES.md) lists what was tested and what was not.
Test it on a copy of your server first.

Every feature ships switched off. Nothing walks, pays or charges until you turn it on. A few safety
checks ship switched on. Each one does nothing until the feature it guards is on.

## Requirements

| What | Version | Needed for |
|---|---|---|
| Paper | tested on Paper 26.1.2 only | Everything |
| Java | 25 | Everything |
| MariaDB | tested with MariaDB 11.4.5 | Everything. Give the plugin a schema of its own. |
| A money source: Vault and a Vault economy plugin by default | tested with EssentialsX 2.22.0 | Any payment. Without one the plugin still loads, and payments stay off. |

Optional plugins:

| Plugin | Needed for |
|---|---|
| Citizens 2.0.43 | Residents that players can see and talk to |
| A home plugin | Homes, tenants and rent. See [Choose a home backend](#choose-a-home-backend). |
| Floodgate 2.2.5 | Bedrock forms, and linking a player's Java and Bedrock accounts |
| Jobs Reborn, tested with 5.2.6.6 | Resident training |
| Skript with skript-reflect | The price feed for resident shops and item repairs, and the optional Skript accounting bridge |

The plugin loads without any of the optional plugins. The features that need a missing plugin stay off.

## How to install

1. Create a MariaDB schema and a user for the plugin. Give the user all privileges on that schema and
   nothing else. The plugin creates and updates its own tables.
2. Put the ProsperityNPC jar in `plugins/`.
3. Start the server once. This creates `plugins/ProsperityNPC/config.yml`. On this first start, Paper
   downloads the MariaDB driver and HikariCP into `libraries/`, so the server needs internet access.
4. Stop the server.
5. Put the database password in `plugins/ProsperityNPC/db-password.txt`. Only the account that runs the
   server should be able to read that file. You can set `database.password` instead, but the file keeps
   the password out of the config.
6. Make the first config changes below.
7. Start the server. Run `/npcadmin health` and `/npcadmin diagnose`.

Do not use `/reload`. Always do a full restart.

If you are upgrading from an older ProsperityNPC, read [Upgrading and rolling back](#upgrading-and-rolling-back)
first.

## First config

All money values in `config.yml` are whole cents. `500000` means $5,000.

These are the keys to set before anything else. The values are examples.

```yaml
database:
  host: "127.0.0.1"
  port: 3306
  name: "npc"
  user: "npc"
  password: ""              # empty means: read db-password.txt

centralbank:
  uuid: "123e4567-e89b-42d3-a456-426614174000"   # the reserve account, in lowercase

economy:
  centralbank-floor-cents: 500000                # payouts stop below $5,000 in the reserve

clock:
  timezone: "Europe/London"
  daily-reset-hour: 4
  settlement-hour: 4
  settlement-minute: 10

money:
  source: vault

accounting:
  sink: internal

property:
  backend: worldguard-listed
```

What each part does:

- **`database`**. Where the plugin keeps its records. Without a working database the plugin still runs,
  but every economy feature stays off.
- **`centralbank.uuid`**. The economy account that pays rent and receives owner payments. Use the
  account's UUID, not a player name, because a name can point at a different account. Write it in
  lowercase. Until it is set, payments and the daily cycle stay held.
- **`economy.centralbank-floor-cents`**. Payouts stop while they would take the reserve below this
  balance. The shipped value is `3500000` ($35,000). Set it to suit the size of your economy. Fund the
  reserve above the floor before you turn on money.
- **`clock`**. The economy day. Daily caps reset at `daily-reset-hour`. The daily cycle runs at
  `settlement-hour` and `settlement-minute`. The shipped time zone is `America/Chicago`. Set your own.
- **`money.source`**. Where money comes from. `vault`, the default, uses the economy plugin registered
  with Vault.
- **`accounting.sink`**. How the plugin confirms its own daily payment totals before it pays. Use
  `internal` unless you run the Skript accounting bridge from the repo. The default, `skript`, makes
  payments wait for that bridge.
- **`property.backend`**. Which plugin owns your homes. The default is `arm`. See the next section.

`money`, `accounting` and `property` are not in the shipped `config.yml` as live keys, only as commented
examples. Leaving them out keeps the defaults.

The other daily caps are in the `economy` section. As shipped, rent payouts are capped at $150 per server,
$45 per owner, $15 per home and $6 per tenant each day.

### Choose a home backend

| Your server uses | Set `property.backend` to | Also needed |
|---|---|---|
| AdvancedRegionMarket | `arm` | The patched ARM from `arm-compat/`, and `integration.arm-coordination: true` |
| Plain WorldGuard regions | `worldguard-listed` | Each home region has exactly one WorldGuard owner. Not yet checked against real WorldGuard. |
| Realty | `realty` | The Realty adapter jar from the pre-release, in `plugins/` next to Realty 2.0.0. Realty needs WorldGuard, an economy and its own MySQL or MariaDB database. Unverified. |
| Another home plugin | the id its adapter registers | An adapter plugin for it. None ships for other plugins in this beta. |

`integration.arm-coordination` and the patched ARM are needed only for `arm`. Other backends do not use
them.

GriefPrevention has adapter source and model tests in this repository, but no jar in this beta.
Realty has an adapter jar attached to the pre-release. It pays the title holder of a plot or the tenant
of a rented unit, and it needs Realty 2.0.x. It was booted once on a local test server. Enrolling a home, real players and real payouts were not checked.
Residence, Lands and PlotSquared are not included. [COMPATIBILITY.md](COMPATIBILITY.md) has the details.

Some home plugins cannot stop an owner change while a payment is running. With those, rent payouts are
held until you choose otherwise. [PLUG-IN-POINTS.md](PLUG-IN-POINTS.md#home-backend) explains why and
what you can change.

### Choose the reserve account

The reserve can be any account that your money source can read and pay from. Some economy plugins cannot
find an account that has never joined the server. With EssentialsX, `/eco` and `/balance` may be among
them. Make sure you can fund and read the account before you set it.
[COMPATIBILITY.md](COMPATIBILITY.md#the-reserve-account-and-essentialsx-commands) has more.

## How to turn features on one at a time

Each step below adds one area. Check `/npcadmin health` after each step before you move on.

Two kinds of setting turn things on:

- **The five switches:** `system`, `economy`, `incidents`, `moves` and `rendering`. Use
  `/npcadmin switch <name> <on|off>`. The change is saved to the config.
- **Module settings in `config.yml`.** `/npcadmin reload` re-reads the switches, the daily caps and some
  module settings. The comment above each setting says whether it needs a restart. The piece choices
  (`money.source`, `accounting.sink` and `property.backend`) always need a full restart. If you change
  `money.source` or `property.backend` and only reload, `/npcadmin diagnose` says a restart is required.
  For `accounting.sink` it does not, so restart after changing it.

### Step 1: connect and check

Leave everything off and start the server.

- `/npcadmin health` should show `State: STANDBY`, a healthy database and the reserve account set.
- `/npcadmin diagnose` should show the money source, home backend and accounting sink you chose, each
  marked in use.

### Step 2: build walking routes

Route commands work for staff as soon as the database is connected. Residents only walk routes that staff
publish.

1. Write a block palette under `nav.palettes`. It lists your sidewalk, road and crosswalk blocks. The
   commented example in `config.yml` shows the format.
2. Check it with `/npcnav palette <name>`.
3. Scan an area with
   `/npcnav scanbox <region> <world> <x1> <y1> <z1> <x2> <y2> <z2> [palette] [load]`.
   The scan prints a draft id. Without the `load` option the scan stops at unloaded chunks, so stand near
   the area. `load` loads chunks without generating new ones. Use it on a test server.
4. Stand on each stop and tag it with `/npcnav taghere <draft> <tag>`. The tags are `home`, `work`,
   `market`, `leisure` and `spawn`.
5. Run `/npcnav corridor <draft>`. It makes a smaller draft for residents to walk. One region can have
   2 to 8 tagged stops. Split a larger area into more regions.
6. Run `/npcnav audit <draft>` on the new draft, then `/npcnav publish <draft>`.

You can also build routes from staff waypoints or from a route file. See
[Pathfinding styles](PLUG-IN-POINTS.md#pathfinding-styles).

### Step 3: show residents

1. Install Citizens.
2. Add signed skins to `plugins/ProsperityNPC/resident-appearance.json`. A resident without a skin is not
   shown. The [README](../README.md#resident-skins) explains the file.
3. Set `town.enabled: true` and restart.
4. Run `/npctown seed`. Then run `/npctown place <resident> <district>` for each resident. The district
   is the region name of a published route.
5. Turn on the `system` and `rendering` switches.
6. To let players talk to residents, set `beta.encounters-enabled: true` and restart.

### Step 4: homes and tenants, with no money

1. Set up your home backend from [the table above](#choose-a-home-backend), and restart.
2. List your markets and home classes under `enrollment.markets` and `enrollment.classes`. These are your
   own labels.
3. Stand inside each home and run
   `/npcproperty enroll <world> <region> <market> <class> <capacity> <daily-rent>`.
   Capacity is 1 to 4 rooms. Daily rent is per tenant per day, in dollars.
4. Make sure the `system` switch is on. Turn on the `moves` switch.
5. Run `/npcadmin roster <world> <market> <class> <max-daily-rent>`. This lists the residents as tenants
   looking for a home.
6. Run `/npcadmin match` to move them in now. Otherwise they move in at the next daily cycle.

### Step 5: money

1. Fund the reserve above `economy.centralbank-floor-cents`.
2. Check that `accounting.sink` suits your server.
3. Set `economy.payments-enabled: true` and restart.
4. Turn on the `economy` switch.
5. Run `/npcadmin health`. The `Payments` line should show payments enabled and the reserve set. The
   `Accounting projection` line should show acknowledged.

Rent is paid once a day, at the settlement time. A home earns rent only on a day its owner paid the
utility bill. `/npcadmin settle` runs today's cycle by hand. It cannot run a past day.

### Step 6: everything else

Turn these on one at a time, as you need them. [FEATURES.md](../FEATURES.md) says what each one needs.

- Upkeep problems: the `incidents` switch.
- Auto-care: `auto-care.enabled`.
- Repairs with items: `item-repairs.enabled`.
- Errands, training and resident shops: `town.errands-enabled`, `town.training-enabled` and
  `town.commerce-enabled`.
- Contracts and partnerships: `town.contracts.enabled` and `town.partnerships.enabled`.
- Moods, ratings and other housing options: the `housing` section.
- Bills and taxes: `bills.enabled`. See [Bills and taxes](PLUG-IN-POINTS.md#power-meters-bills-and-taxes).
  Back up your database before the first bill, because after that you can only roll forward.
- Staff alerts: `alerts.enabled`. Alerts are written to the server log. To show them in game, the repo
  includes `skript/npc-alerts-prosperitysmp.sk` as an example. Sending them to Discord needs a script of
  your own.

## How to check health

### /npcadmin health

`/npcadmin health` shows the plugin's state in one screen.

| Line | What to look for |
|---|---|
| `State` | `ACTIVE` when the `system` switch is on. `STANDBY` when it is off. |
| `Database` | `healthy`, with the schema version. `DISCONNECTED` means every economy feature is off. |
| `Properties` | How many homes are enrolled, the home backend's name (`ARM`, `WorldGuard` or the adapter's name) and whether it is available, and when the last owner check ran. A `MISSING` count usually means a region was deleted or renamed. |
| `Payments` | Whether `economy.payments-enabled` is on, and whether the reserve account is set. |
| `Receipt runtime` | `unavailable` means payments cannot start. With ARM, check the patch and `integration.arm-coordination`. With another backend, check the database and `/npcadmin diagnose`. |
| `Accounting projection` | `acknowledged` when payments may run. |
| `Switches` | Each of the five switches. |
| `Caps` | The daily caps and the reserve floor in use. |

More lines appear while some modules are on, for example linked accounts, contracts and bills. If homes
were enrolled under a different home backend than the one configured, a line says how many.

### /npcadmin diagnose

`/npcadmin diagnose` lists every piece the plugin found: money sources, home backends and accounting
sinks. For each one it shows whether it is in use and what it can do. If a piece was refused, it gives
the reason.

It looks like this:

```
ProsperityNPC pieces (API 1.2)
money.source: vault [in use] provider Essentials via Vault; scale 2; artifact sha256 bda46851
property.backend: arm [in use] guard write-hook installed; owner model: one UUID owner, no members
accounting.sink: skript [in use] Skript heartbeat present
```

That is the default setup with EssentialsX 2.22.0 behind Vault. The hash is the first 8 characters of the
economy plugin jar's SHA-256, so yours will differ.

The startup log also names the pieces in use, in one line, for example
`Pieces: money=vault property=worldguard-listed accounting=internal`.

Neither shows balances, player UUIDs or secrets from your config.

## When a payment is held

A payment with an unclear result is held for staff. It is never retried and never refunded on its own.
NPC payments stay paused until staff settle it. `/npcadmin health` and the staff alerts say what is held.
The README section [Operator recovery tools](../README.md#operator-recovery-tools) explains how to settle
one.

## Upgrading and rolling back

- **Back up the database first.** Stop the server and take a full backup of the plugin's schema before
  you put in a new jar.
- **Migrations always run.** Upgrading from 0.2.0-dev.63 or older runs database migrations V41 to V56
  on the first start, whatever your switches and settings say. They add and change tables for bills,
  home backends, waypoints and owner prices.
- **Bills are one way.** Once the first bill event exists, an older jar cannot read it, so you can only
  roll forward.
- **Custom prices are one way too.** After a custom-priced charge or purchase is saved, an older jar
  cannot read its saved price. The README section
  [Bills and property settings](../README.md#bills-and-property-settings) has the details.
- **To roll back**, stop the server and restore the backup of both the database and the plugin jar
  together. Do not run an older jar against the upgraded database.

## Commands used on this page

| Command | Who | What it does |
|---|---|---|
| `/npcadmin health` | staff | Shows the plugin's state |
| `/npcadmin diagnose` | staff | Lists the pieces in use and why any were refused |
| `/npcadmin switch <name> <on\|off>` | staff | Turns a switch on or off |
| `/npcadmin reload` | staff | Re-reads the switches, caps and some module settings |
| `/npcadmin roster <world> <market> <class> <max-daily-rent>` | staff | Lists the residents as tenants looking for a home |
| `/npcadmin match` | staff | Moves waiting residents into free rooms now |
| `/npcadmin settle` | staff | Runs today's daily cycle by hand |
| `/npcproperty enroll ...` | staff | Enrolls the home you stand in |
| `/npcnav ...` | staff | Builds, checks and publishes walking routes |
| `/npctown seed`, `/npctown place` | staff | Saves the residents and places them in town |
| `/resident` | players | Talk to a nearby resident |
| `/property` | players | Manage the home you stand in |
| `/tenants` | players | View and manage your tenants |

Staff commands need `prosperitynpc.admin`. Player commands need `prosperitynpc.use`, which everyone has
by default.
