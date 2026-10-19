# Plug-in points

ProsperityNPC connects to other plugins through a few named pieces. Each piece is chosen by one config
key. You can keep the default, choose another built-in piece, or install a piece that someone else wrote.

This page covers each piece: what it is, the default, the other choices, the config keys, and what
happens when the piece is missing. The last sections are for developers who want to write their own
piece.

- [Summary](#summary)
- [How pieces are chosen](#how-pieces-are-chosen)
- [Money source](#money-source)
- [Accounting](#accounting)
- [Home backend](#home-backend)
- [Power, meters, bills and taxes](#power-meters-bills-and-taxes)
- [Pathfinding styles](#pathfinding-styles)
- [Price feed](#price-feed)
- [Developer API](#developer-api)
- [Which version added what](#which-version-added-what)

## Summary

| Piece | Config key | Default | Other choices |
|---|---|---|---|
| Money source | `money.source` | `vault` | An id registered by another plugin |
| Accounting | `accounting.sink` | `skript` | `internal` |
| Home backend | `property.backend` | `arm` | `worldguard-listed`, or an id registered by an adapter plugin |
| Bills and taxes | `bills.policies` | none, and `bills.enabled: false` | Your own policies |
| Meters | `bills.policies.<id>.meter` | `flat` | `derived:<variable>`, or an id registered by another plugin |
| Route building | chosen by the command you use | block palette scan | staff waypoints, route files |

## How pieces are chosen

- The plugin chooses each piece once, while the server starts.
- A built-in piece is chosen during the plugin's own startup. A piece from another plugin is chosen when
  the server has finished starting, so every plugin has had a chance to register.
- If the configured id is not found, nothing is chosen. The plugin never falls back to the default. The
  features that need that piece stay off, and `/npcadmin diagnose` says why.
- If two plugins register the same id, neither one is used.
- Built-in ids are reserved. Another plugin cannot register them. The reserved ids are `vault`, `arm`,
  `worldguard-listed`, `skript`, `internal`, `flat`, `derived`, `scan`, `waypoints` and `file`.
- A piece built for a different API version is refused. Diagnose prints both version numbers.
- A piece is never swapped while the server runs. A piece registered after startup is not used until the
  next restart.
- If the plugin that provides a piece is disabled while the server runs, that piece stops working for the
  rest of the run. Everything that needs it is held.
- To change a piece, edit the key and do a full restart. `/reload` and `/npcadmin reload` never change a
  piece. After a reload, diagnose still shows the piece in use. For `money.source` and `property.backend`
  it also says a restart is required.

The startup log has one line that names the pieces in use, for example
`Pieces: money=vault property=arm accounting=skript`.

## Money source

### What it is

The money source is how the plugin reads balances and moves money. Every payment has two steps. First
the plugin takes the money from one account. Then it pays the same amount into another account. One of
the two accounts is always the reserve. The plugin never creates money.

### The default: Vault

`money.source: vault` uses the economy plugin that is registered with Vault. EssentialsX is the one we
test with. Other Vault economy plugins have not been tested.

Vault is optional. Without it the plugin still loads. Payments stay off, and the log says
`Vault economy not found; player purchases will be unavailable.`

### The reserve account

`centralbank.uuid` names the account that pays rent and receives owner payments.

- Use the account's UUID in lowercase. A player name can point at a different account.
- Until it is set, every payment and the daily cycle stay held.
- Payouts stop while they would take the reserve below `economy.centralbank-floor-cents`.
- The plugin reads the reserve setting again at every payment. If the setting changes while a payment is
  being prepared, that payment stops.

### Other money setups

**A server treasury or bank account.** The reserve can be any account that the money source can read and
pay from. If your treasury or bank plugin gives the treasury an account through Vault, set
`centralbank.uuid` to that account's UUID. Nothing else is needed. This has not been tested with a
treasury plugin yet.

**A money plugin that does not use Vault.** A developer can write a money source for it with the
[developer API](#developer-api). Install that plugin, then set `money.source` to the id it registers.

### Config keys

| Key | Default | What it does |
|---|---|---|
| `money.source` | `vault` | Which money source to use. Restart required. |
| `centralbank.uuid` | `""` | The reserve account's UUID, in lowercase |
| `economy.centralbank-floor-cents` | `3500000` ($35,000) | Payouts stop while they would take the reserve below this |
| `economy.payments-enabled` | `false` | Rent and owner payments run only while this is on |
| `switches.economy-enabled` | `false` | The money switch. Also set with `/npcadmin switch economy on` |

### When the money source is missing

- With no money source in use, no payment starts. Nothing is charged and nothing is paid.
- `/npcadmin diagnose` gives the reason, for example `Vault is not installed`,
  `no economy plugin is registered with Vault` or `not found`.
- If the money plugin is disabled before a payment's money moves, the payment stops with nothing moved.
- If it is disabled after money may have moved, the payment is held for staff.

### Limits that come from Vault

Vault passes balances as binary numbers called doubles. The plugin works in whole cents.

- Every payment is a whole number of cents.
- A balance that holds a fraction of a cent can still pay and be paid. The plugin rounds the usable
  balance down to a whole cent.
- Balances up to just under $1,000,000,000 can be checked.
- A payout that would take the receiving account above $900,000,000 is refused before any money moves.

[COMPATIBILITY.md](COMPATIBILITY.md#how-essentialsx-balances-are-read) explains how EssentialsX reports
balances to Vault.

## Accounting

### What it is

The plugin keeps daily totals of the money it moves. Before it pays anything, those totals must be
confirmed. The accounting setting says who confirms them.

### The choices

| `accounting.sink` | What it does |
|---|---|
| `skript`, the default | A Skript bridge from the repo reads the totals into Skript variables for your own money tracking. Payments wait until the bridge confirms it has them. Needs Skript, skript-reflect and the bridge script. |
| `internal` | The plugin confirms its own totals. Nothing is published to Skript. No Skript is needed. |

Use `internal` unless you run the Skript bridge. Other plugins cannot add their own accounting choice.
A change needs a full restart.

### When it is missing or wrong

- An unknown value holds every payment. The startup log says
  `accounting.sink '<value>' is unknown; use skript or internal`. The `Accounting projection` line in
  `/npcadmin health` says the same, followed by `(payments held)`.
- With `skript` and no bridge running, payments wait. `/npcadmin health` shows
  `Skript bridge: no heartbeat`.
- With `internal`, `/npcadmin health` shows
  `Accounting projection: acknowledged by the plugin (accounting.sink=internal; no Skript totals are published)`.

## Home backend

### What it is

The home backend is the real-estate or land plugin that owns your homes. It tells ProsperityNPC which
plot a player stands in, who owns it, and when the owner changes. Rent goes to the owner it reports.
Owner payments are taken from that owner.

The backend also has to protect payments. A payment takes a moment to complete. If a home changed owner
in that moment, rent could go to the old owner. Each backend protects against this in one of three ways:

| Guard | What it means |
|---|---|
| Write hook | The home plugin refuses any owner change on a home while a payment for that home is running. Only the patched ARM has this. |
| Veto | ProsperityNPC cancels an owner change while a payment for that home is running. It only does this where the home plugin has not yet taken anyone's money. |
| None | The owner change cannot be stopped. ProsperityNPC notices it afterwards. |

### The default: AdvancedRegionMarket

- `property.backend: arm`.
- Needs AdvancedRegionMarket 3.5.5 with the ownership hook from `arm-compat/`, and
  `integration.arm-coordination: true`. Stock ARM loads, but homes, rent and owner payments stay held.
- Guard: write hook. Rent and appreciation are paid normally.
- Only ARM sell regions can be enrolled.
- A home must have exactly one owner UUID, with no names, no groups and no parent region.
- While the patch is installed, never disable ProsperityNPC on its own. ARM then refuses owner changes on
  those plots until a full restart.

### The other backends

| Backend | `property.backend` | Status in this beta | Guard | Rent and appreciation by default |
|---|---|---|---|---|
| ARM | `arm` | Built in, default | Write hook | Paid |
| WorldGuard regions | `worldguard-listed` | Built in, unverified against real WorldGuard | None | Held |
| GriefPrevention | `griefprevention` | Source plus model tests, limited lab recordings on 16.18.7, no jar | Veto without RealEstate, none with it | Paid after the startup check without RealEstate. Held with it. |
| Realty | `realty` | Adapter jar attached to the pre-release, booted once on a local test server, unverified | None | Held |
| Residence, Lands, PlotSquared | | Not included | | |

An adapter is a separate plugin jar that registers a home backend. Install it next to ProsperityNPC and
the home plugin, then set `property.backend` to its id. The Realty adapter jar is attached to the pre-release. No other vendor adapter jar ships.
[COMPATIBILITY.md](COMPATIBILITY.md) has the tested versions and [ADAPTERS.md](ADAPTERS.md) explains how
adapters are written.

`integration.arm-coordination` is only for `arm`. A backend other than ARM does not need it.

#### WorldGuard regions (`worldguard-listed`)

- Built in. Needs WorldGuard. It has not been checked against a real WorldGuard install yet. If
  WorldGuard cannot be read, nothing is paid.
- Who pays and is paid: the region's only WorldGuard owner. The owner list must hold exactly one player
  UUID, with no names, no groups and no parent region. Any other region has no owner, and its home is
  held.
- Region id: the WorldGuard region id.
- WorldGuard fires no event that can stop an owner edit, so the guard is none.
- A region that is deleted and defined again counts as a different region. A payment that started
  before the change stops.
- WorldGuard edits an owner list in place, so an owner change and a change back, with no payment in
  between, look like no change.

#### GriefPrevention (`griefprevention`)

The adapter source is in `adapters/griefprevention/`. It has model tests and a few recordings from a
local test server on GriefPrevention 16.18.7. No jar ships in this beta, and no jar can be built from
this tree until its vendor checksum pin is recorded. This is what the source does:

- Who pays and is paid: the claim owner. An admin claim has no owner, and its home is held.
- Region id: the claim id number.
- A subdivision reports its parent claim's owner. A transfer or deletion of the parent affects every
  enrolled subdivision.
- Without the RealEstate add-on, the guard cancels a claim transfer while a payment for that claim is
  running. Another plugin listening later at the same priority can undo that cancel. Safety then rests
  on the check after every applied transfer and the owner check before each payout.
- With RealEstate installed, the guard is none. RealEstate takes the buyer's money before the transfer,
  so cancelling it would cost the buyer. If RealEstate is added or removed while the server runs, the
  backend is held until a restart.
- GriefPrevention tells the person who ran a cancelled transfer that it worked. The server log says the
  transfer was held because an ownership operation was in progress.
- Before every payout, the plugin reads the claim owner again.

#### Realty (`realty`)

The adapter source is in `adapters/realty/`, and its jar is attached to the pre-release. It is compiled
against the Realty 2.0.0 API jars with the vendor pin recorded, and it passes `verifyPinnedVendorApis`
and `verifyAdapterJar`. It was booted once on a local test server, which checked owner reads, the 14
event bindings and the held list. Enrolling a home, real players, real payouts and load were not checked.
The WorldGuard calls use reflection, so a compile does not check them. It needs Realty 2.0.x. This is what the adapter does:

- Who pays and is paid: the title holder of a freehold, or the tenant of a rented lease. A vacant unit,
  an unsold plot, a landlord and an authority are never paid. A landlord or authority can be a player,
  an account or a permission group. The adapter never reads them.
- Region id: the WorldGuard region id, up to 128 characters.
- Subregions work. The tenant of a rented subregion is the payer for it. A plot is not paid while a
  registered subregion inside it is rented or sold. The unit has to be a WorldGuard child of the freehold.
- A vacant unit has no payer until someone rents it.
- The guard is none. Realty fires no event at all for `unset titleholder` and `unset tenant`. A lease
  sweep changes the database before it fires its event. Other plugins can call Realty's API directly.
  None of these can be stopped, so a veto would promise more than it keeps.
- The plot's WorldGuard owner list must hold exactly the payer, with no names and no groups. If it does
  not, the plot has no payer.
- Realty keeps its data in a database. The adapter reads a snapshot, and every Realty event marks the
  plot stale until it is read again.
- Payouts are held by default. With `reread-before-payout`, a change made only in Realty's database can
  pay the old owner until the next refresh.

### Config keys

| Key | Default | What it does |
|---|---|---|
| `property.backend` | `arm` | Which home backend to use. Restart required. |
| `property.when-unguarded` | `hold-payouts` | What to do with rent and appreciation when the backend cannot block owner changes. Read at startup and on `/npcadmin reload`. |
| `integration.arm-coordination` | `false` | Turns on the ARM write hook. ARM only. Restart required. |
| `enrollment.markets` | `["downtown"]` | Your market labels. Enrollment refuses any other. |
| `enrollment.classes` | `["room", "penthouse"]` | Your home class labels. Enrollment refuses any other. |
| `enrollment.reconcile-interval-ticks` | `6000` | How often the plugin checks every home's owner. 6000 ticks is 5 minutes. |

`property.when-unguarded` takes one of two values:

- `hold-payouts`, the default. Rent and appreciation wait. The reply says
  `Rent waits: the home plugin cannot block owner changes (property.when-unguarded=hold-payouts).`
- `reread-before-payout`, an opt-in. Rent and appreciation are paid after the owner is read again right
  before each payout. A change in the moment between that read and the payout is not caught. Use it only
  if you accept that small risk.

Any other value counts as `hold-payouts`, and the log names the value it ignored.

### Enrolling homes with another backend

The command is the same for every backend. Stand inside the home and run
`/npcproperty enroll <world> <region> <market> <class> <capacity> <daily-rent>`.

- `<region>` is the region id in the backend's own format, as listed above.
- `<market>` and `<class>` are your own labels from `enrollment.markets` and `enrollment.classes`. They
  do not depend on the backend.
- ARM enrolls sell regions only. Other backends enroll a home that has one paying owner.
- `/npcproperty armdump` prints what the backend reports, for debugging.

### When the backend is missing or cannot block owner changes

Payouts are held. Nothing is ever paid to an owner the plugin cannot confirm.

- **Backend not installed, or its id not found.** Nothing is chosen. Your homes and tenants are kept. No
  rent is paid, no owner payment is taken and no resident moves. `/npcadmin diagnose` gives the reason.
- **Backend fails while the server runs.** This covers the home plugin or the adapter being disabled, and
  an error inside one of the adapter's event handlers. The backend is marked unavailable for the rest of
  the run, and every payment is held. Staff get the alert `PROPERTY_BACKEND_FAILED`.
- **Backend cannot block owner changes.** This covers a guard of none, and a backend that cannot tell a
  re-created home from the old one (weak identity). Rent and appreciation are held by default. Owners can
  still pay bills, buy upgrades and pay for repairs.
- **Startup check.** With any backend other than ARM, the plugin reads the owner of every enrolled home
  once after each start. Payouts stay held until that check finishes with every home readable. If the
  homes could not be loaded from the database, the check fails and payouts stay held.
- **Every charge reads the owner again** right before money moves. If the owner changed, the charge stops
  before any money moves.
- **Owner check.** The plugin checks every home's owner every 5 minutes.
- **Unreadable owner.** A home whose owner cannot be read gets no rent. With
  `economy.rent-isolation-enabled: true`, only that home is held and the rest are paid. With `false`, the
  shipped value, it stops that day's whole rent batch.

### Switching backends

Each home remembers the backend it was enrolled under. A region id from one plugin never matches a
region in another.

If you change `property.backend`, homes enrolled under the old backend are left out of every payment and
every move. `/npcadmin health` says how many homes were enrolled under the other backend, and that they
stay out of payments until `property.backend` is set back. `/npcproperty resume` refuses such a home and
names the backend it was enrolled under.

This beta has no command to move a home to a new backend. To manage those homes again, set
`property.backend` back to the old id and restart.

## Power, meters, bills and taxes

### What they are

- A **bill** or **tax** is a policy you write in config. Its formula gives an amount in cents for one home
  for one day.
- A **meter** gives a policy a number to bill on, such as units used or whether a home has power.

Bills are for anything you want owners to pay for: electricity, water, property tax, a town fee.

### How bills work

- Bills are off by default. `bills.enabled` turns them on.
- Once a day, after rent, each home gets at most one invoice. It lists that day's bills.
- Amounts are fixed when the invoice is issued. They never change after that.
- The payer is always the home owner. The money always goes to the reserve.
- Owners pay from the owner desk, or with `/property bills pay <bill> <maximum-dollars>`.
- Automatic collection is built but off by default. With `bills.auto-collect-enabled: true`, the plugin
  tries to take each new invoice once, right after issuing it. It never tries again.
- If the owner cannot pay, nothing moves and the invoice stays unpaid. Nobody borrows money. There is no
  debt and no late fee.
- An unpaid invoice expires after `bills.payable-days` days. No money moves when it expires.
- An unpaid bill has no penalty unless you set `bills.on-unpaid: hold-rent`. With `none`, the default, an
  unpaid bill changes nothing else.
- A home whose owner is away (see `housing.vacation-days`) gets no invoice that day.
- Bills never fund rent. After a one-time starting allowance, the rent a home can earn is limited by what
  its owner has spent on it. With the shipped settings, each dollar spent on the built-in charges lets
  the home earn up to 90 cents of rent and appreciation. Bills and taxes do not count toward that.
- The six built-in charges keep their prices: the utility bill, upgrades, maintenance, cash repairs,
  insurance and eviction. The bills block cannot change them or switch them off.
- Paying a bill uses the same payment path as the utility bill, so it needs everything payments need.

### Hold-rent

`bills.on-unpaid: hold-rent` is off by default. With it on:

- A home earns no rent while its current owner has an unpaid bill for that home from an earlier day that
  can still be paid. Today's bill never holds today's rent.
- A bill only holds rent for the owner it was issued to. After a sale, the old owner's unpaid bill does
  not hold the new owner's rent.
- `/property bills` and the bill pages on the owner desk tell the owner:
  `Rent is paused for this home until this bill is paid or expires on <date>.` When more than one
  earlier bill may be unpaid, the line says rent is paused until every unpaid bill from an earlier day
  is paid or expires, and gives this bill's expiry date.
- Rent comes back on the next rent batch after the bill is paid or expires. Rent that was held is not
  paid later.
- While the server starts and the bills block is still loading, rent waits instead of running unheld.
  There are two exceptions, both cases where bills are off and nobody can pay: the bills block was
  refused (for example, the open bills could not be read at startup), or the bills runtime itself failed
  to start. Rent then runs unheld and the startup log says why.

### Limits

Each bill line is capped at its own `max-cents`. Then the whole bill is capped at $200, then each
owner's daily total, and then the server's daily total. When a total is reached, whole lines are left
off, in policy-id order. A line is
never cut down to fit, so every amount on an invoice is exactly what its formula gave, or its
`max-cents`.

With linked accounts on, a player's Java and Bedrock accounts share one owner limit.

The plugin has fixed ceilings. Your config can set lower limits, never higher ones. A value above a
ceiling is refused when the bills block loads.

| Limit | Ceiling |
|---|---|
| One bill line | $200 |
| One whole bill (one home, one day, all its lines) | $200 |
| One owner, per day, all homes together | $1,000 |
| The whole server, per day | $25,000 |

### Config keys

| Key | Default | What it does |
|---|---|---|
| `bills.enabled` | `false` | Turns your bills on |
| `bills.auto-collect-enabled` | `false` | Allows one automatic payment attempt per new invoice |
| `bills.on-unpaid` | `none` | `none` or `hold-rent` |
| `bills.payable-days` | `"7"` | Days an invoice can be paid before it expires, 1 to 90 |
| `bills.limits.per-owner-daily-max-cents` | none, required once enabled | Each owner's daily total, across all their homes, up to `"100000"` |
| `bills.limits.server-daily-max-cents` | none, required once enabled | The server's daily total, up to `"2500000"` |
| `bills.policies` | empty | Up to 16 policies |

Each policy under `bills.policies.<id>` has these keys:

| Key | Required | What it does |
|---|---|---|
| `version` | yes | A whole number. Raise it whenever you change any other key of the policy. |
| `title` | yes | The name owners see, 1 to 32 letters, digits, spaces and simple punctuation |
| `applies-to` | no, default `all` | `all`, `occupied` or `vacant` homes |
| `classes` | no | Only homes of these classes |
| `markets` | no | Only homes in these markets |
| `properties` | no | Only these homes, by home UUID. Useful for trying a bill on a few homes first. |
| `meter` | no, default `flat` | `flat`, `derived:<variable>` or a registered meter id |
| `amount` | yes | The formula |
| `vars` | no | Named numbers the formula uses. Money values are cents. Decimals are allowed, for example `"0.02"`. |
| `rounding` | no, default `floor` | `floor`, `ceil` or `half-up`. Applied once, at the end. |
| `min-cents` | no, default `"1"` | A result below this bills nothing |
| `max-cents` | yes | The most this line can be, up to `"20000"` |

A policy id uses lowercase letters, digits and dashes, starts with a letter, and is at most 24
characters. It cannot be the name of a built-in charge, such as `utility` or `repair`.

### An example

```yaml
bills:
  enabled: true
  auto-collect-enabled: false
  on-unpaid: none
  payable-days: "7"
  limits:
    per-owner-daily-max-cents: "5000"      # $50 per owner per day
    server-daily-max-cents: "100000"       # $1,000 per server per day
  policies:
    electricity:
      version: "1"
      title: "Electricity"
      applies-to: occupied
      meter: derived:tenants
      amount: "base + rate * units"
      vars: { base: "50", rate: "25" }     # 50 cents plus 25 cents per tenant
      rounding: floor
      min-cents: "1"
      max-cents: "500"
    property-tax:
      version: "1"
      title: "Property tax"
      applies-to: all
      meter: flat
      amount: "ceil(percent * rent_paid_cents)"
      vars: { percent: "0.02" }            # 2% of yesterday's rent
      max-cents: "2000"
```

### Rules for the bills block

- Every number is a quoted string: `"50"`, never `50`. An unquoted number is refused. This stops YAML from
  reading `050` or `0x10` as a different number.
- An unknown key is refused.
- The whole block loads, or none of it does.
- At startup, a bad block leaves bills off. The reason is in the log and in `/npcadmin health`.
- At `/npcadmin reload`, a bad block keeps the bills you already had, and you get the reason.
- A new or changed policy takes effect on the next economy day. An invoice that was already issued never
  changes.
- The block's own settings work differently. A reload applies `limits`, `on-unpaid`, `payable-days` and
  `auto-collect-enabled` at once, not on the next day. For example, turning on `hold-rent` mid-day holds
  rent today for unpaid bills that were issued while it was off.
- When you change any key of a policy, raise its `version`. A version that was already used with
  different content is refused.
- These keys are refused on purpose: `funds-rent`, `payee`, `counts-as-service`, `grace-days` and
  `late-fee`. Bills never fund rent, the payee is always the reserve, and there are no late fees.

### The formula

A formula uses only these parts:

- numbers, such as `50` or `0.02`, with up to 12 digits and up to 6 decimal places;
- names from `vars` and the variables below;
- `+`, `-`, `*`, `/` and brackets;
- `min(a, b, ...)` and `max(a, b, ...)`;
- `clamp(x, low, high)`, which keeps `x` between `low` and `high`;
- `pick(i, a, b, ...)`, which chooses one of the listed values by its position, counting from 1:
  `pick(1, a, b)` gives `a`;
- `floor(x)` and `ceil(x)`, which round down and up.

Other rules:

- You can only divide by a number or a `vars` value, and never by zero. Dividing by a variable such as
  `tenants` is refused.
- Text is at most 256 characters, 64 parts and 16 brackets deep.
- The plugin works out the lowest and highest result each formula can give when the block loads. It
  refuses a formula that could go below zero. It refuses a `pick` that could get a position that is not a
  whole number in range, and a `clamp` whose low value could be above its high value.
- The load message says at which values `max-cents` starts to cap the result.
- Rounding happens once, at the end. Nothing is rounded along the way.

### Variables

All variables are whole numbers, read for each home when the invoice is issued.

| Variable | Meaning |
|---|---|
| `capacity` | Rooms in the home, 1 to 4 |
| `tenants` | Rooms let, 0 to 4 |
| `upgrade_levels` | All upgrade levels together, 0 to 6 |
| `advertising_level` | 0 to 3 |
| `facilities_level` | 0 to 3 |
| `condition` | 0 to 100 |
| `asking_rent_cents` | The owner's asking rent, per tenant per day |
| `reference_rent_cents` | The rent set at enrollment, per tenant per day |
| `rent_paid_cents` | Rent this home was paid on the previous economy day, or 0 |
| `owner_homes` | How many enrolled homes the owner has |
| `units` | From the meter |
| `powered` | 1 or 0, from a meter that reports power |

`rent_paid_cents` uses the previous day on purpose. A tax on today's rent could be dodged by timing.

### Meters

| `meter` | `units` is |
|---|---|
| `flat` | Always 1 |
| `derived:<variable>` | One of `tenants`, `upgrade_levels`, `advertising_level`, `facilities_level` or `capacity` |
| a registered id | The units that meter reports for the home and the day |

A meter from another plugin is chosen by its id, with the same rules as the other pieces. Each reading is
saved on the invoice line, so the amount can always be worked out again from what was saved.

A meter declares one of three kinds:

- `period`: the amount used in that day.
- `level`: a value as it stands now.
- `cumulative`: a counter that only goes up. The plugin bills the difference since the last reading. The
  first reading only sets the starting point and bills nothing. If the counter goes down, the plugin
  treats it as a reset: no charge, a new starting point, and a staff alert.

Cumulative meters have known gaps in this beta:

- The starting point moves on even when a line is left off, for example by a daily limit, a suspect
  reading or `min-cents`. That usage is never billed.
- A home whose owner is away is not read. The next billed day charges the whole away period at once.
- The starting point belongs to the home, not the owner. After a sale, the new owner's first bill can
  include usage from before the sale.

### Electricity and power-grid plugins

No meter for a power plugin ships with ProsperityNPC. We have not found a power plugin that reports how
much power each building uses. A meter can still report whether a home has power. `powered` is then 1 or
0, for a formula like `rate * powered`. A developer writes the meter with the
[developer API](#developer-api).

### When a meter or formula fails

The plugin never guesses an amount. A failure means no charge for that line.

- **Meter error.** An error, an empty reading, a negative value or a value above 1,000,000,000 gives no
  line for that policy that day. Staff get the alert `BILL_METER_FAILED`.
- **Reading out of range.** If a line uses a meter from another plugin and its amount comes out above
  4 times `max-cents`, it bills nothing and staff get `BILL_METER_FAILED`. Lines without such a meter are
  only capped.
- **Slow meter.** A meter that takes more than 5 milliseconds is named in a log warning.
- **Formula error while running.** No line. Staff get `BILL_FORMULA_FAILED` once per policy per day.
- **Meter not found.** That policy bills nothing. `/npcadmin bills status` shows the reason next to the
  policy.
- **Daily limit reached.** Staff get `BILL_CAP_REACHED`.
- **Bad bills block.** Staff get `BILLS_CONFIG_REJECTED`.

Alerts need `alerts.enabled: true`.

### Commands

| Command | Who | What it does |
|---|---|---|
| `/property bills` | owners | Lists the home's open and recent bills |
| `/property bills before <YYYY-MM-DD>` | owners | Lists older bills |
| `/property bills pay <bill> <maximum-dollars>` | owners | Pays one bill. `<bill>` is the start of the bill id shown in the list. The maximum is the total shown. |
| `/property receipt <request-uuid>` | owners | Shows a receipt, including bill payments |
| `/npcadmin bills status` | staff | The loaded bills, the day they take effect and today's limits used |
| `/npcadmin bills preview <property-uuid>` | staff | A trial invoice for one home. Writes nothing and moves no money. |

`/npcadmin health` shows one bills line while `bills.enabled` is on, or after a bills block was refused.

### Accounting for bills

Bill payments are counted under one accounting source, `npc-property-bills`. With
`accounting.sink: skript`, it appears in Skript after the first bill payment and stays there.

### Rolling back after bills

Back up your database before you turn bills on. Once the first bill event exists, an older version
cannot read it, so you can only roll forward. To go back, restore that backup of both the database and
the plugin. [GETTING-STARTED.md](GETTING-STARTED.md#upgrading-and-rolling-back) covers upgrades in
general.

## Pathfinding styles

### How residents walk

Residents walk a route graph that staff publish for a region. They never use free pathfinding. If a walk
fails, the resident disappears and shows up again later on the route.

There are three ways to build the graph. All three give the same kind of graph, and every graph is
checked, audited and published the same way.

| Style | How staff build it | Best for |
|---|---|---|
| Block palette scan, the default | Scan an area. The plugin reads each block's type from a palette. | Towns with clear sidewalk and road blocks |
| Staff waypoints | Place named points and link them | Towns without clear block types, or exact control |
| Route file | Import a file of nodes and edges | Routes made by another tool |

With a classified palette, these road rules hold for every style:

- Residents walk on sidewalk blocks, plus any "access" blocks you list for paths and doorsteps.
- They step onto a road only to cross it, in a straight line to the sidewalk directly opposite.
- They never turn, cut a corner or walk along a road.
- They use a painted crosswalk when it is within `nav.street.crosswalk-reach` blocks of sidewalk detour.
  Otherwise they cross straight over.
- They never appear, start a trip or end a trip on a road.

### Block palette, the default

A palette lists block types under `nav.palettes.<name>`. It has two forms.

The section form sorts blocks into classes:

```yaml
nav:
  palettes:
    town:
      sidewalk: [STONE_BRICKS, POLISHED_ANDESITE]
      road: [GRAY_CONCRETE, BLACK_CONCRETE]
      crossing: [WHITE_CONCRETE]
      access: [DIRT_PATH]
```

- `sidewalk`: where residents walk. Required.
- `road`: crossed only. Required.
- `crossing`: crosswalk paint. Optional.
- `access`: paths and doorsteps residents may walk on but never cross a road from. Optional.

A typo, a block in two classes, an empty `sidewalk` or `road` list, or a block that is not a full block is
an error.

The list form, `town: [STONE, GRASS_BLOCK]`, treats every listed block as plain ground. A graph scanned
with it is "unclassified". Publishing one needs `/npcnav publish <graph> legacy`.

Only the palette name `default` uses the built-in block list. Any other unknown name is an error.

Commands:

| Command | What it does |
|---|---|
| `/npcnav palette <name>` | Prints the palette's classes, or the exact error |
| `/npcnav scanbox <region> <world> <x1> <y1> <z1> <x2> <y2> <z2> [palette] [load]` | Scans an area into a draft |
| `/npcnav scanhere <region> <radius> [palette]` | Scans around where you stand |
| `/npcnav taghere <draft> <tag> [remove]` | Tags the spot you stand on as a stop |
| `/npcnav copytags <from> <draft> [partial]` | Copies tags from an older graph to a new draft |
| `/npcnav corridor <draft>` | Makes a smaller draft between the tagged stops, 2 to 8 of them |
| `/npcnav validate <draft>` | Checks the graph's shape |
| `/npcnav audit <graph> [live] [load] [palette <name>]` | Walks every route residents could take and fails on any broken road rule |
| `/npcnav audit selftest` | Proves the audit can fail. It must show one FAIL and one PASS. |
| `/npcnav publish <draft> [legacy\|asbuilt]` | Makes the draft the live route |
| `/npcnav revalidate <graph\|active> [load]` | Checks that the world still matches a published graph |
| `/npcnav restore <archived>` | Makes a new draft from an old graph |
| `/npcnav info <graph>`, `/npcnav list` | Shows graphs |

### Staff waypoints

Staff stand on a sidewalk, add named points, and link them. `/npcnav wp build` fills in every block
between the points, checks the world, and saves a normal draft.

**Points.**

- A name of up to 32 lowercase letters, digits, dashes or underscores.
- A kind: `sidewalk` or `access`.
- Up to 8 tags, the same tags as scanned graphs.
- At `wp build` with a palette, a point that stands on a road block is refused.
- Where a walk link goes from a sidewalk onto an access path, add an `access` point where the access path
  starts. A walk link's blocks are checked as sidewalk.

**Links.**

- `walk`: along a sidewalk.
- `cross`: straight over a road.
- `crosswalk`: straight over a road on painted lines.
- A link runs straight along x or along z. A bend needs a point at the corner.
- A link climbs at most `nav.max-step` blocks up or down per block along.
- Both ends of a `cross` or `crosswalk` link are `sidewalk` points. The crossing is level and at most
  `nav.street.max-crossing-length` blocks long.
- A `walk` link may not run through a crossing, and two crossings may not share a block.

**A palette is required by default.** `nav.waypoints.require-palette: true` makes the build check every
block against a palette. That is the only automatic proof that no walk link runs along a road. Name the
palette on the command or in `nav.waypoints.palette`.

A server without a block palette can set `nav.waypoints.require-palette: false`. The build then checks
only that each block can be stood on. Every such build warns
`road and sidewalk were not checked against any palette`. The graph has no palette, so
`/npcnav audit <graph> live` is refused for it.

**Publishing.**

- A waypoint graph is always fingerprinted and audited at publish, even with
  `nav.audit.gate-publish: false`.
- If the palette or the street numbers changed after the build, publish is refused with "changed since
  this graph was built". Build it again.
- If the points changed after the build, publish is refused. To publish the older build anyway, for
  example to roll back, use `/npcnav publish <draft> asbuilt`.
- Tags on a waypoint graph come from its points. `taghere` and `copytags` refuse a waypoint draft. Use
  `/npcnav wp tag`.

**Limits.** A set holds at most `nav.waypoints.max-points` points and `nav.waypoints.max-links` links.
The built graph is at most `nav.waypoints.max-nodes` blocks, and never more than 1024. Above that, split
the area into two regions.

**Stored sets.** Every edit keeps a full copy of the set in the database. Old copies are not removed in
this beta.

Commands. From the console, end each command with the world name.

| Command | What it does |
|---|---|
| `/npcnav wp add <region> <name> [sidewalk\|access] [link] [x y z world]` | Adds a point where you stand or at the given spot. `link` also links it to your last point. |
| `/npcnav wp move <region> <name> [x y z world]` | Moves a point |
| `/npcnav wp remove <region> <name>` | Removes a point and its links |
| `/npcnav wp link <region> <a> <b> [walk\|cross\|crosswalk]` | Adds or changes a link. The default is `walk`. |
| `/npcnav wp unlink <region> <a> <b>` | Removes a link |
| `/npcnav wp tag <region> <name> <tag> [remove]` | Edits a point's tags |
| `/npcnav wp list <region> [page]` | Lists points and links |
| `/npcnav wp check <region>` | Checks the point rules without reading the world |
| `/npcnav wp build <region> [palette] [load]` | Builds a draft |
| `/npcnav wp export <region>` | Writes the points to a file in the import folder |
| `/npcnav wp import <region> <file>` | Replaces the points from a file |

### Route files

A route file lists nodes and edges. Any tool can write one. YAML and JSON are both accepted.

```yaml
format: prosperity-nav-graph/1
default-surface: sidewalk
nodes:
  - {id: 1, at: [100, 64, 200], surface: sidewalk, tags: [spawn]}
  - {id: 2, at: [101, 64, 200]}
  - {id: 3, at: [102, 64, 200]}
edges:
  - [1, 2]
  - {from: 2, to: 3, cost: 14}
  - {from: 3, to: 2}
```

- `format` must be `prosperity-nav-graph/1`. Other top-level keys are refused.
- Every node has a positive whole-number `id`, an `at` position, and a surface: `sidewalk`, `access`,
  `road` or `crossing`. `default-surface` can only be `sidewalk` or `access`. A node with no surface and
  no `default-surface` is refused.
- An edge written as a pair, `[1, 2]`, is a step both ways. An edge written as `{from, to}` is one way.
- Every edge joins two neighbouring blocks. An edge that touches a road or crossing block runs straight
  along x or z.
- The plugin works out the cost of every road and curb step itself. A file can only set `cost` between
  two nodes that are neither road nor crossing, and only from the normal step cost up to ten times it.
  A file can make a path slower, never cheaper.
- Tags follow the same rules as other graphs, at most 8 per node. Road and crossing nodes cannot have
  tags.

**File safety.**

- Files go in `plugins/ProsperityNPC/nav-import/`, set by `nav.import.dir`. Subfolders are not read.
- A file name is up to 64 letters, digits, dots, dashes or underscores, then `.yml`, `.yaml` or `.json`.
- A file is at most `nav.import.max-bytes`, 1 MiB by default. Larger settings are capped at 1 MiB.
- YAML anchors, aliases, tags, merge keys and duplicate keys are refused.
- An import always becomes a draft after a world check. It never publishes on its own.

Commands:

| Command | What it does |
|---|---|
| `/npcnav import <region> <world> <file> [palette] [load]` | Imports a route file into a draft |
| `/npcnav export <graph>` | Writes any graph as a route file |

### Config keys

| Key | Default | What it does |
|---|---|---|
| `nav.palettes` | `{}` | Your palettes |
| `nav.headroom` | `2` | Clear blocks needed above a walkable block |
| `nav.max-step` | `1` | The largest step up or down between neighbours |
| `nav.street.crosswalk-reach` | `12` | Blocks of detour a resident accepts to use a crosswalk, 0 to 64 |
| `nav.street.max-crossing-length` | `16` | The longest road a resident will cross, 1 to 32 |
| `nav.street.crosswalk-min-share` | `0.4` | How much of a crossing must be painted to count as a crosswalk |
| `nav.audit.gate-publish` | `true` | Publish refuses unclassified graphs, changed palettes and failed audits |
| `nav.audit.palette` | `""` | The palette a live audit uses for a graph scanned with a list palette |
| `nav.waypoints.require-palette` | `true` | Waypoint builds must check blocks against a palette |
| `nav.waypoints.palette` | `""` | The palette waypoint builds use when none is named. Empty means a palette must be named. |
| `nav.waypoints.max-points` | `600` | Points per region |
| `nav.waypoints.max-links` | `1200` | Links per region |
| `nav.waypoints.max-nodes` | `1024` | Blocks in a built waypoint graph, capped at 1024 |
| `nav.import.dir` | `nav-import` | The import folder, under `plugins/ProsperityNPC/` |
| `nav.import.max-bytes` | `1048576` | The largest route file, capped at 1 MiB |

### When something is missing

- **No palette for a waypoint build.** The build is refused unless `nav.waypoints.require-palette` is
  `false`.
- **The palette or street numbers changed.** Publishing a graph built before the change is refused.
  Build or scan it again.
- **The world changed.** `/npcnav revalidate` reports `DRIFTED`. For a scanned graph it prints the area to
  scan again. For a waypoint graph it says to fix the listed points and build again. For a route file it
  says to fix the file and import it again.
- **An unclassified graph.** Publish is refused unless you add `legacy`.
- **Rolling back to a version from before waypoints.** That version cannot publish a waypoint graph. It
  could still tag one by hand. Delete waypoint drafts before you tag anything with the older version.

## Price feed

Resident shops and repairs with items need full prices for the items they sell or accept. ProsperityNPC
does not set prices. You provide a price feed: a Skript (with skript-reflect) or a plugin that publishes
a price for every material the plugin asks for, at least every 10 seconds. A price older than 10 seconds
is not used.

- Without fresh prices, resident shops take no new orders. Orders already paid can still be collected.
- Without a fresh price for every item in a repair, that repair offers cash only.
- `town.commerce-enabled` and `item-repairs.enabled` turn the two features on.

[FEATURES.md](../FEATURES.md) lists the calls the feed makes: `commercePrices()`, then `begin()`,
`put(token, material, dollars)` for each name in `materials()`, and `complete(token, version)`.

## Developer API

### What you can write

| Piece | Interface | Added in API |
|---|---|---|
| Money source | `MoneySource` | 1.0 |
| Home backend | `PropertyBackend` | 1.0, with guard hooks in 1.2 |
| Meter | `UsageMeter` | 1.1 |

The API is in the package `com.prosperitysmp.npc.api`, and meters are in
`com.prosperitysmp.npc.api.charge`. It ships inside the ProsperityNPC jar. Accounting choices are not part
of the API. [ADAPTERS.md](ADAPTERS.md) has more detail for adapter authors.

### How to package a piece

1. Make your own Bukkit plugin. In its `plugin.yml`, set `depend: [ProsperityNPC, <the plugin you
   connect>]`.
2. Compile against the API as compile-only. Never copy the API classes into your jar. The server must see
   one copy of each API class, or ProsperityNPC cannot find your piece.
3. Register your piece with Bukkit's `ServicesManager` under its interface, when your plugin enables.
   Priority is ignored.
4. Give it an id of 2 to 32 characters: lowercase letters, digits and dashes, starting with a letter.
   It cannot be a reserved id.
5. Server owners install your jar and set the config key to your id.

```java
// In your plugin's onEnable
getServer().getServicesManager().register(
        PropertyBackend.class, new MyHomeBackend(this), this, ServicePriority.Normal);
```

### Every piece

Every piece implements `Piece`:

- `id()`: the id server owners put in config.
- `apiMajor()` and `apiMinor()`: return `ProsperityNpcApi.MAJOR` and `ProsperityNpcApi.MINOR`.
- `capabilities()`: named flags and values that describe what the piece can do.
- `label()`: an optional short label, such as `unverified`, shown by diagnose.

ProsperityNPC refuses a piece whose major version differs from its own, or whose minor version is newer.
Within one major version the API only grows. Nothing you use is removed or changed, and new methods on
the interfaces you implement come with defaults.

### Rules for a money source

`MoneySource.pin()` returns the provider to use for one payment, or nothing when there is none. The
returned `Pin` has:

| Method | Rule |
|---|---|
| `stillCurrent()` | True only while it is the same provider as when it was pinned, and its plugin is enabled |
| `enabled()` | Whether the provider is enabled |
| `artifact()` | The jar file of the provider's own code. ProsperityNPC hashes it and stores the hash with each payment. |
| `balanceDollars(OfflinePlayer)` | The provider's balance as it reports it. Never rounded. Never moves money. |
| `leg(OfflinePlayer, cents, DEBIT or CREDIT)` | Exactly one money call to the provider. Returns `LegResponse.of(success, amountDollars)`, or `null` when the provider returned nothing. |

- Never catch an error from the provider inside `leg`. Let it through. ProsperityNPC decides what an
  error means.
- Never pay back a failed step, and never call a provider's own transfer method. ProsperityNPC's records
  are the only thing that coordinates the two steps.
- The interface has no transfer, mint, create, set or "has enough" method, and must not gain one.
- The `scale` capability must be `"2"`, meaning two decimal places. Any other value is refused.
- The `artifact-hashable` capability is required.
- Other capabilities are for information: `balance-double-exact`, `non-player-accounts`,
  `offline-accounts`, `main-thread-only`, `reentrant-callbacks` and `permits-overdraft`.

### Rules for a home backend

| Method | What it does |
|---|---|
| `displayName()` | The name staff and players see, for example `ARM` |
| `available()` | Whether the home plugin can be read right now |
| `installGuard(OwnershipHooks)` | Called once at startup. Returns `GuardResult.installed(mode)` or `GuardResult.failed(reason)` |
| `regionAt(Location)` | The home at a spot |
| `findRegion(worldName, regionId)` | A home by id |
| `ownerReadAt(Location, owner)` | An ownership token for the owner at a spot |
| `ownerReadRegion(worldId, regionId, owner)` | An ownership token by id |
| `observe(worldId, regionId)` | `PRESENT`, `MISSING` or `UNREADABLE`. Never throws. |
| `describe()` | Debug lines. Never secrets. |
| `guardBindings()` | The event handlers your guard needs (API 1.2) |

The guard mode is `WRITE_HOOK`, `VETO_EVENTS` or `NONE`. A third-party backend that claims
`WRITE_HOOK` is refused; only the built-in ARM hook may use it.

`OwnershipHooks` is what ProsperityNPC gives your backend:

- `isHeld(world, regionId)`: whether a payment for that home is running. Safe on any thread.
- `externalChange(world, regionId)`: report an owner change you could not stop. Main thread only.
- `markDirty(world, regionId)`: the same report from any thread. It is applied on the next server tick.
- `guarded(what, body)`: runs your code. Any error marks your backend unavailable and holds payments.
- `beginWrite(world, regionId)`: for a write hook inside the home plugin itself. Event-based backends
  never call it.

Rules:

- Pay exactly one owner per home, and say in the `owner-model` capability who that is. Refuse the zero
  UUID, server accounts and temporary owners.
- An ownership token must change when the owner changes. If a home is deleted and made again with the
  same id, the token must change too. Declare the `token-identity` capability: `object` or `generation`
  when your tokens tell those apart, `weak` when they cannot. A missing or unknown value counts as
  `weak`. With weak identity, payouts follow `property.when-unguarded`.
- Register event handlers only through `guardBindings()`. ProsperityNPC registers each one under your
  plugin and wraps it, so an error marks the backend unavailable instead of being lost. If the home
  plugin's events are not Bukkit events, wrap every handler body in `hooks.guarded(...)`.
- An event that arrives off the main thread may only call `isHeld` and `markDirty`.
- Cancel an owner change only where the home plugin has not yet taken anyone's money, and only where it
  honours the cancel.
- One event must report every enrolled home it affects, for example every subdivision of a claim.
- Region ids are at most 128 characters.
- Other capability names: `guard`, `location-lookup`, `region-by-id`, `distinguishes-missing`,
  `sees-owner-round-trip`, `needs-worldguard`, `region-id-max-length` and `main-thread-only`.

### Rules for a meter

`UsageMeter.read(MeterQuery)` returns a reading, or nothing when there is no reading. Nothing means no
charge for that line. An error is treated the same way.

- `MeterQuery` gives the world, region id, owner, and the start and end of the period.
- Build a `MeterReading` with its builder: `units` from 0 to 1,000,000,000, an optional `powered` true or
  false, and `detailSha`, 64 lowercase hex characters that hash your own evidence.
- `read` runs on the main thread. It must answer at once and never wait on a disk, a network or a lock.
- Declare the `provides` capability (`units`, `powered` or `units,powered`), a `unit-label`, and a `kind`
  (`period`, `level` or `cumulative`). A meter with any other `kind` is not used.

Only install meters you trust. A meter cannot be stopped once it is running. Its bills are still limited
by every cap, and it can never create money.

### Testing your piece

The source repository has contract test suites for money sources and home backends, `MoneySourceContract`
and `PropertyBackendContract`, in the API module's test fixtures. They are in the source tree only;
nothing is published to Maven. Run your piece through the suite for its type. Each important case in a
suite also has a deliberately broken example that the suite must fail, so you can see the suite catch a
mistake. There is no contract suite for meters yet.

### Versions and license

| API version | Adds |
|---|---|
| 1.0 | `MoneySource`, `PropertyBackend` |
| 1.1 | `UsageMeter` |
| 1.2 | `guardBindings()`, and `externalChange`, `markDirty`, `isHeld` and `guarded` on `OwnershipHooks` |

ProsperityNPC is under the GNU General Public License, version 3 only. The API is part of it, so a piece
that links the API must be under a GPL-compatible license.

## Which version added what

| Version | Adds |
|---|---|
| 0.2.0-dev.64 | `money.source`, `accounting.sink`, `property.backend`, `/npcadmin diagnose`, Vault made optional, API 1.0 |
| 0.2.0-dev.65 | Bills, taxes and meters, API 1.1 |
| 0.2.0-dev.66 | Staff waypoints and route files |
| 0.2.0-dev.67 | `worldguard-listed`, the GriefPrevention adapter source, `property.when-unguarded`, API 1.2 |
| 0.2.0-dev.68 | The Realty adapter source. No API change |
