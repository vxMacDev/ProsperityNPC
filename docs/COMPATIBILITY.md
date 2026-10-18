# Compatibility

This page lists the plugins ProsperityNPC works with, and how well each one is tested in this beta.
[RELEASE-NOTES.md](../RELEASE-NOTES.md) says what has and has not been tested overall.

## What the status means

| Status | Meaning |
|---|---|
| Built in | Part of the ProsperityNPC jar. |
| Built in, untested | Part of the jar, but not tested against a real copy of that plugin. |
| Source and model tests only | Adapter source is in this repository, with tests against a model of the plugin. No jar ships. |
| Not included | Nothing ships for this plugin. A developer can write a piece with the [developer API](PLUG-IN-POINTS.md#developer-api). |

`/npcadmin diagnose` shows each piece's id, the plugin that registered it, its API version and its
label, when it has one.

## Server

| Software | Version | Status | Notes |
|---|---|---|---|
| Paper | tested on Paper 26.1.2 only | Required | |
| Java | 25 | Required | |
| MariaDB | tested with MariaDB 11.4.5 | Required | Give the plugin a schema of its own and a user with rights on that schema only. |

## Money

| Plugin | Version | Status | `money.source` | Notes |
|---|---|---|---|---|
| Vault | | Built in | `vault` | Optional. Without it the plugin loads and payments stay off. |
| EssentialsX, through Vault | tested with 2.22.0 | Built in | `vault` | See [How EssentialsX balances are read](#how-essentialsx-balances-are-read). |
| Other Vault economy plugins | | Built in, untested | `vault` | Connects through Vault. Not tested. |
| Hibernia Treasury, through Vault | | Built in, untested | `vault` | Treasury makes itself the Vault economy. See [the notes](#hibernia-treasury-and-realty). |
| A treasury or bank account | | Built in, untested | `vault` | Can work through Vault if your treasury plugin gives the treasury a Vault account. Set `centralbank.uuid` to that account. |
| Money plugins outside Vault | | Not included | your source's id | A developer can write a money source with the API. |

### How EssentialsX balances are read

EssentialsX stores each balance as an exact decimal number. Vault can only pass a balance on as a double,
a binary number that often cannot hold that decimal exactly.

When EssentialsX 2.21 or newer reports a balance to Vault, it starts from the nearest double. If that
double is above the stored balance, it reports the next double below it instead. So the reported balance
is never above the stored balance, but it can be a tiny fraction of a cent below it. A stored $12.30 is
reported as 12.299999999999999.

ProsperityNPC accounts for this:

- It reads a balance like 12.299999999999999 as the whole cents it stands for, here 1,230.
- If the stored balance itself has a fraction of a cent, the usable balance is rounded down to a whole
  cent.
- When it checks that a payment moved exactly the right amount, it allows for this one-step difference
  in each of the two balances it compares. Payments to and from these balances go through.

### The reserve account and EssentialsX commands

Some EssentialsX commands may not find an account that has never joined the server. `/eco` and
`/balance` can be among them, both by name and by UUID. If your reserve account has never joined, check
that you can fund it and read its balance before you set `centralbank.uuid`. If those commands cannot
find it, fund it another way, for example with a plugin or script that sets the balance by UUID.

## Homes and land

| Plugin | Version | Status in this beta | `property.backend` | Guard | Rent and appreciation by default |
|---|---|---|---|---|---|
| AdvancedRegionMarket, patched | 3.5.5 with the hook from `arm-compat/` | Built in, default | `arm` | Write hook | Paid |
| WorldGuard | | Built in, unverified against real WorldGuard | `worldguard-listed` | None | Held |
| GriefPrevention | 16.18.7 | Source plus model tests, limited lab recordings on 16.18.7, no jar | `griefprevention` | Veto, or none with RealEstate | Paid after the startup check. Held with RealEstate. |
| Realty | | Built in, untested, top-level regions only | `worldguard-listed` | None | Held |
| Residence | | Not included | | | |
| Lands | | Not included | | | |
| PlotSquared | | Not included | | | |
| AreaShop | | Not included | | | |

"Held" means rent and appreciation payouts wait while `property.when-unguarded` is `hold-payouts`, the
default. Owners can still pay their bills and buy upgrades. The
[home backend section](PLUG-IN-POINTS.md#home-backend) explains each guard.

No vendor adapter jar ships in this beta. The adapter API is described in [ADAPTERS.md](ADAPTERS.md).

### Notes per plugin

- **AdvancedRegionMarket.** Stock ARM 3.5.5 loads, but homes, rent and owner payments stay held. You need
  the patched build from `arm-compat/` and `integration.arm-coordination: true`. Both are needed only
  with `property.backend: arm`. Never disable ProsperityNPC on its own while the patch is installed: ARM
  then refuses owner changes until a full restart.
- **WorldGuard.** A home region needs exactly one owner, as a player UUID. Names, groups and parent
  regions do not count. WorldGuard has not been installed on our test server, so this backend has only
  been tested against a model of WorldGuard. If WorldGuard cannot be read, nothing is paid. WorldGuard
  changes an owner list in place, so an owner change and a change back, with no payment in between,
  look like no change at all.
- **GriefPrevention.** The adapter source in `adapters/griefprevention/` is written against 16.18.7 and
  has model tests and a few recordings from a local test server. It is not release-verified, and no jar
  can be built from this tree yet, because its vendor checksum pin is not recorded. What it does:
  - With the RealEstate add-on installed, the adapter never cancels a transfer, because RealEstate takes
    the buyer's money first.
  - Without RealEstate, it cancels a claim transfer at HIGHEST priority while a payment for that claim is
    running. Another plugin listening later at HIGHEST can undo that cancel. Safety then rests on the
    check that runs after every applied transfer, plus the owner check before each payout, the same as
    the `reread-before-payout` setting.
  - When the adapter does cancel a transfer, GriefPrevention still tells the person who ran it that it
    worked. The server log says the transfer was held because an ownership operation was in progress.
- **Residence, Lands, PlotSquared.** No adapter source exists for these in this beta.

### Hibernia Treasury and Realty

Neither has been tested with ProsperityNPC. This is what reading their code says.

- **Hibernia Treasury.** Treasury makes itself the Vault economy, so `money.source: vault` reaches it.
  - Create the reserve account in Treasury before you turn payments on. A payment to an account Treasury
    has never seen creates that account with Treasury's starting balance.
  - Turn off Treasury's income tax for ProsperityNPC (`plugin-rates.ProsperityNPC: 0`). Otherwise every
    payout is held for staff.
  - Another plugin changing the same balance during a payment also holds that payment for staff.
- **Realty.** Realty writes the owner into the WorldGuard region on every sale and rental. So
  `property.backend: worldguard-listed` can see top-level Realty homes.
  - Subregions do not work.
  - Payouts are held by default, because a Realty sale does not wait for a payment to finish.
  - A Realty adapter is planned.
- **AreaShop.** No adapter ships. A developer can write one with the API.

## Residents and players

| Plugin | Version | Status | Needed for |
|---|---|---|---|
| Citizens | 2.0.43 | Built in | Residents that players can see and talk to |
| Floodgate | 2.2.5 | Built in | Bedrock forms, and linking a player's Java and Bedrock accounts |
| Jobs Reborn | tested with 5.2.6.6 | Built in | Resident training. Jobs pays the training bonus, so ProsperityNPC's caps do not apply to it. |

## Scripts and accounting

| Plugin | Status | Needed for |
|---|---|---|
| Skript with skript-reflect | Built in | The price feed for resident shops and item repairs. The Skript accounting bridge, if you use `accounting.sink: skript`. |

With `accounting.sink: internal`, payments do not need Skript. Shops and item repairs still need a price
feed, which can also be a plugin.

## Power and electricity

| Plugin | Status | Notes |
|---|---|---|
| Electricity and power-grid plugins | Not included | No meter ships. We have not found a power plugin that reports how much power each building uses. A meter that reports only whether a home has power still works with a formula like `rate * powered`. See [meters](PLUG-IN-POINTS.md#meters). |

Bills and taxes that need no outside plugin work with the built-in `flat` and `derived:<variable>`
meters.
