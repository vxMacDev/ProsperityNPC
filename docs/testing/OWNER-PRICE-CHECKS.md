# Owner price checks

The [recording](owner-price-recording.json) summarizes a local Paper 26.1.2 run with
Java 25, GriefPrevention 16.18.7, EssentialsX 2.22.0 and Vault. It identifies the
exact core artifact. A synthetic CraftPlayer used an in-memory transport; inventory
clicks were delivered through Bukkit events and commands through the public handlers.
Economy calls, database receipts and journal observations used the installed plugins.

| Check | Observed result |
| --- | --- |
| Maintenance maximum $17.49, cost $17.50 | Refused; all 89 table counts and both balances unchanged |
| Owner desk maintenance | Displayed and paid $17.50; condition 75 to 80; applied receipt and both provider legs confirmed |
| Owner desk facilities level 1 | Displayed and paid $185; applied receipt and both provider legs confirmed |
| Replay each retired inventory | Payments enabled; no new rows or money movement |
| Restart Paper and MariaDB with changed prices | New settings loaded; old paid amounts, balances, funding and table counts retained |
| Read public receipts after restart | Original $17.50 and $185 remained visible |
| Request maintenance again at the new $25 price | Already applied; no further charge or global recovery hold |

The two accounts began with $1,000 each and ended at $797.50/$1,202.50. Recorded
cash sinks were 20,250 cents, with no payouts or unfunded charges. Cleanup archived
the eight fixture tables, restored all 89 baseline table counts, drained both test
balances and removed the fixture property, claims and player. Sequences, worlds and
third-party identity indexes were outside the count-restoration check.

This run used schema 48 and a private GP test artifact. Vendor dependency/package
acceptance, connected Java/Bedrock clients, extended auto-care lifecycle, interrupted
provider calls, the populated daily cycle and realistic player-load measurements remain
separate checks. The values here summarize observations; automated regressions live
in the test source.

A later boundary review found that the accumulated upgrade audit column still used
a signed 32-bit integer. Migration 49 widens it to BIGINT, and repair snapshots accept
the corresponding long values. MariaDB regressions cover three supported payments
whose total crosses the old limit, replay, repair snapshots and repeated migration.
Price settings also reject amounts that exceed the existing exact-cent transfer
precision. The schema-48 recording covers the amounts in the table above.

## Scheduled auto-care

A [separate recording](auto-care-price-recording.json) used core
`3cf4750bbd0fd0e7df9513e36845259bd9ece48659deaf8462b021784d787aee`, schema 49,
and the normal two-minute Bukkit scheduler. The observer read task deadlines, queue,
cursor and busy state. No cycle was invoked manually and no scheduler field changed.

Utilities were paid explicitly first with `/property utility pay 1.00`. With condition
75 and a saved $15 auto-care ceiling, the next eligible scheduled scan left wallets,
condition and all 89 table counts unchanged: $17.50 maintenance exceeded the ceiling.
After the owner saved a $30 ceiling, the next scheduled pass paid exactly $17.50,
raised condition to 80 and stored a spent claim bound to the applied purchase and
confirmed journal observations. Public OFF then persisted a disabled consent while
retaining the spent claim and paid history.

The two accounts began at $1,000 each and ended at $981.50/$1,018.50, including the
manually paid $1 utility bill. Funding recorded 1,850 cents in cash sinks, with no
payouts or unfunded charges. Cleanup validated and archived eleven fixture tables,
restored all 89 baseline counts and drained the known test balances.

This used one synthetic owner, a vacant home with no incidents, vacation handling
off, a UTC clock and a late settlement time to isolate auto-care. The synthetic
connection, extended lifecycle cases, provider interruption, clients, populated
daily cycle and realistic load have the limits stated in the recording.

## Eviction

The [eviction recording](eviction-price-recording.json) used core
`15546ad01ad921d3db754df55c84a2ce6aeab19d372505f9e6d74c8ff018124a`, schema 50.
The public command refused a $23.74 maximum against a $23.75 cost without creating
a payment or changing either wallet or any of the 89 table counts. The text command
suggestion and normal owner-desk confirmation both used $23.75.

The confirmation paid $23.75 through Essentials/Vault, retained the resident profile,
vacated the tenancy at the next revision and stored one hashed paid-departure fact.
A retired confirmation replay with payments enabled changed no money or rows. After
a full Paper/MariaDB restart on a $30 price, the accepted receipt and public receipt
message kept $23.75. A fresh request for that vacated tenancy was refused without
another payment. The two wallets remained at $976.25/$1,023.75, with 2,375 cents in
recorded sinks and no payouts or unfunded charges.

Cleanup validated and archived the ten fixture tables, restored all 89 schema-50
baseline counts, drained both test wallets and removed the claims/player. Original
plugin configuration, locale and identity indexes were restored; the forward schema
and GP claim sequence remain. This used a synthetic connection, manual inventory
events and an isolated settlement clock. Client, load, scheduled-cycle, recovery and
vendor acceptance limits remain separate checks.

## Insurance

The [insurance recording](insurance-price-recording.json) used core
`ce540cf5919befed69d5b97715df37a8903ef257f271d4a16d31137277ca43be`, schema 51,
and MariaDB 11.4.5. Startup applied the nine migration statements and preserved
the other 88 table counts. The accepted-price foreign key was present.

With the public insure command enabled, a $17.76 maximum was refused against the
configured $17.77 premium without changing wallets or any of the 89 table counts.
The normal owner-desk button displayed and paid $17.77. The applied receipt and
both journal legs matched that price; the saved capacity-two coverage started on
the next economy day and ended seven economy days later. Replaying the retired
view with payments enabled created no extra payment or coverage.

After a full Paper/MariaDB restart on a $23.99 premium, the public receipt, paid
amount, coverage dates and funding retained the accepted $17.77 terms. A new
request for the same period was refused. The native desk showed the existing
future term rather than offering another purchase.

Vault reported a tiny binary representation difference in the owner's dollar
balance. The existing TransferProof cent rule and journal proof confirmed balances
of 98,223 and 101,777 cents, conserving the original 200,000 cents; the raw values
are retained in the recording. Recorded cash sinks were 1,777 cents, with no
payouts or unfunded charges.

Cleanup validated and archived eight fixture tables, restored all 89 schema-51
baseline counts and returned both synthetic accounts to exact zero, including a
provider-native reset of a sub-noise fixture remainder after withdrawal. Original
core/config/locale/identity indexes/cache were restored; the forward schema and
GP sequence remain. This proves the stated price and restart cases with a
synthetic connection and isolated clock. Future scheduled prevention, clients,
load and extended lifecycle checks remain separate.

## Utilities

The [utility recording](utility-price-recording.json) used the configured flat
$17.77 tariff with the normal two-minute auto-care scheduler. A public $17.76
maximum was refused without a bill, payment or wallet change. The owner desk
showed $17.77. A $15 consent ceiling left all table counts and money unchanged;
raising consent to $30 let the next normal scan pay exactly $17.77. The utility
receipt was PAID, the claim SPENT, and both provider legs were verified.

A full Paper/MariaDB restart on a $23.99 tariff retained the original $17.77 bill,
claim, funding and wallets. Changed and invalid reload settings left the active
tariff unchanged. A retired-view replay created no extra charge.

This check found that the general receipt command omitted utilities. A small
read-only lookup fix then passed 168 focused tests and packaging checks. The
saved native fixture was restarted on that package without another payment or
seed: the public receipt showed $17.77 while both money controls were off. With
the system enabled, the cold-start desk showed the saved $17.77 maximum without
a reload. The earlier full build belongs to the payment package, as identified
in the recording.

Cleanup archived ten fixture tables, restored all 89 schema-53 baseline counts,
returned both synthetic wallets to exact zero and restored original lab files.
Forward schema/sequence and ordinary world changes remain. These are synthetic
connection, native provider and scheduled-price checks; connected clients,
whole-day simulation, realistic player load and vendor acceptance remain separate.

## Repair settings

`repair-prices` sets the cash-equivalent cost when a new incident is generated:

```yaml
repair-prices:
  pests-cents: "2000"
  plumbing-cents: "3500"
  break-in-cents: "5000"
```

Each value is quoted positive integer cents, at most 100,000,000 ($1,000,000).
Missing keys keep their shipped default. Bukkit treats blank/null YAML entries as
omitted keys, so they use defaults too. Unknown keys and invalid values reject the
block. A full server restart loads changes. Reload reports changed or invalid
settings and keeps the active prices. Invalid startup settings disable the plugin
before database initialization.

New incidents save their accepted price. Existing incidents, pending repairs,
receipts, hand-ins and auto-care claims keep that price after settings change.
Material value must still cover the saved cash cost and configured value ratio;
an unavailable or underpriced recipe cannot become a cheaper route. Auto-care also
needs the owner's saved consent and spending limit. Incident chances, damage and
insurance coverage do not change with these price settings.

The automated checks exercise configuration parsing, generated snapshots, replay,
cash/material repair effects, service history, auto-care binding and insurance
prevention. The [repair recording](repair-price-recording.json) identifies the
schema-56 package used for the following native checks.

Invalid pricing disabled the plugin before database initialization. Valid pricing
applied migrations 54–56, and valid/invalid reloads retained the active price. A
selected test seed generated one PESTS incident through IncidentRollRepository
using the active $23.75 setting. This was a native API probe, not a normal daily
incident cycle. The public $23.74 maximum was refused without changing wallets or
any of the 89 table counts or logical rows. The owner desk displayed and paid
$23.75; both provider legs, the applied receipt and service history agreed.
Material repairs were disabled, so that button paid cash directly.

A retired-view replay created no extra payment. After a full Paper/MariaDB restart
on $35, the incident, receipt, service fact, wallets and saved database rows kept
the original $23.75 terms. The public receipt remained readable with money controls
off; the cold owner desk opened with the system on and economy off, without reload.
Cleanup archived the 13 fixture tables, restored all 89 baseline counts and logical
row digests, returned both synthetic wallets to exact zero and removed the claims,
player and known profile files. Forward sequences and ordinary world changes remain.

A [separate scheduled recording](repair-auto-care-price-recording.json) used a
fresh home and accounts on the same package. After paying the $1 utility bill
explicitly, a saved $20 auto-care ceiling left the open $23.75 repair unpaid on
the next eligible normal scan. No claim, purchase, upkeep or money movement
occurred; all 89 table counts and logical row digests were unchanged. Maintenance
was configured at $40 in this fixture to keep fallback upkeep outside the ceiling.

Raising consent to $30 let the next normal two-minute scan pay $23.75 once.
The spent CASH_REPAIR claim and applied purchase kept the configured 2,375-cent
marker and shared request ID. Both native provider legs and the service fact
agreed. Public OFF retained the spent claim and saved a disabled consent. A full
Paper/MariaDB restart on $35 retained those terms, wallets and database rows;
the public receipt still showed $23.75 with money controls off. Cleanup validated
and archived all 14 fixture tables, restored the 89 baseline counts and logical
row digests, drained both synthetic wallets and removed the known fixture files.

These checks used a selected-seed incident API probe and a synthetic connection.
Native material custody, new-incident generation after the price change, connected
clients, populated daily cycles, realistic load and extended recovery remain
separate acceptance work.

The [material preview recording](repair-material-preview-recording.json) then
generated a fresh PESTS incident at $35, retaining a custom marker even though
that price equals PLUMBING's shipped default. At one cent per item the recipe hit
the quantity cap, and the public command created no collection or take. At $1
per item it scaled to 35 paper and 18 baked potatoes: $53 of materials against
the required $52.50 value floor.

The valid hand-in stopped before its intent or take. Native saved-file inspection
confirmed the items, owner, versions and before-take marker, but this Windows
lab's Java filesystem provider supplied no stable file identities for the save
path. The existing durability check refused that baseline. The runtime had
reported ready too early. Delivery and repair readiness now check the real
player-data directory once during startup, and the material choice uses that
readiness before offering a priced hand-in. The original saved-file proof remains
required for every actual take or delivery. Restart after fixing an unsupported
storage path. Successful native material custody and returns remain pending; the
preview recording does not establish them. The known fixture was archived and
removed, with all 89 baseline table counts and logical row digests restored.

A [read-only startup control](item-storage-readiness-recording.json) checked the
corrected package on that same storage. NBT support and both recovery barriers
were ready; delivery and repair readiness stayed false, with one operator warning.
No player, property, item or payment fixture was created, and all 89 table counts
and logical row digests stayed unchanged. All original lab file hashes were
restored afterward. This verifies the unavailable-storage case; it does not prove
a successful hand-in or delivery on suitable storage.
