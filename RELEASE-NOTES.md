# Release notes

## 0.2.0-dev.67 (BETA)

This is a beta. Test it on a copy of your server before you use it on a live one.

### What is new

- You can choose your money source, your home plugin and how payment totals are confirmed.
- Plain WorldGuard regions can be used as homes (`property.backend: worldguard-listed`).
- `/npcadmin diagnose` shows which parts are in use and why any were refused.
- Your own daily bills and taxes. Off by default.
- Staff waypoints and route files for building walking routes.
- Owner prices can be changed in config.
- Vault is now optional.

### What was fixed

- Staff waypoint crossings now build over a crosswalk that is only partly painted.
- After a sale, the old owner's unpaid bill no longer pauses the new owner's rent.
- With `bills.on-unpaid: hold-rent`, rent now waits at startup until the bills settings have loaded.
- The auto-collect count now includes only bills that were paid. Declined payments no longer count.
- A waypoint file with a bad link name could lock that region's waypoints. Import now refuses it, and the
  set stays editable.
- `/npcadmin diagnose` says a restart is required after `money.source` or `property.backend` is changed
  and only reloaded.
- A home plugin that does not say how it tells homes apart is treated as unable to block owner changes.
  Its payouts are held unless `property.when-unguarded: reread-before-payout` is set.
- Payments are held when a home plugin's adapter cannot be found, or when its homes could not be loaded
  at startup.
- Residents only move into homes enrolled under the home plugin you have set. The owner desk only shows
  those homes.
- A refused enroll (already enrolled, paused, or enrolled under another home plugin) no longer leaves that
  home stuck. The reply says which.
- Before `centralbank.uuid` is set, the log shows one warning saying payments stay held. It no longer adds
  lines that name an exception.
- The $200 bill cap now covers the whole bill for one home on one day, as well as each line. When a limit
  is reached, lines that don't fit are left off whole. With `alerts.enabled` on, staff get a
  `BILL_CAP_REACHED` alert.

### How it was tested

- 4,824 automated tests in 353 suites, with 0 failures and 39 skipped. That is 4,744 for the plugin, 12
  for the adapter API, 17 for the example adapter and 51 for the GriefPrevention adapter. The tests that
  use a database run against a real MariaDB that the tests start.
- `./gradlew seamFaultChecks` and `./gradlew billFaultChecks` break the code on purpose in 10 and 24
  places. The tests catch all 34.
- This exact jar was started from the console on a local Paper 26.1.2 test server with MariaDB 11.4.5,
  with no players connected. Five setups passed: Vault present, Vault absent, `accounting.sink: internal`,
  an example home plugin, and a reload after changing `money.source` (diagnose said a restart is
  required). The GriefPrevention setup was skipped because there is no adapter jar. The waypoint build
  checks were not run there.

[docs/testing/README.md](docs/testing/README.md) lists the hand-run checks and what they did not cover.

### Not tested yet

- No real Java or Bedrock players. Players have only been simulated.
- No load test. The 50 to 80 player case has not been measured.
- Bills have not been tried on a running server with real homes yet.
- The waypoint build checks have not been run on my test server.
- My test server has no AdvancedRegionMarket, Skript, Citizens or WorldGuard.
- Item hand-ins have not been tried on my test server. Its disk does not pass the startup check for
  player data.
- I will run the checks that compare ARM homes with 0.2.0-dev.63 on my own development server, before I
  upgrade my own server.
- Real player testing and load testing come later.

### Known limitations

- **Bills settings apply at once on reload.** `limits`, `on-unpaid`, `payable-days` and
  `auto-collect-enabled` change straight away on `/npcadmin reload`. Only new or changed policies wait for
  the next economy day.
- **Cumulative meters.** These only matter for a meter from another plugin of kind `cumulative`. Usage on
  a line left off by a limit, or on a reading the plugin did not trust, is never billed. An owner-away
  period is billed in one go on the next billed day. After a sale, the new owner's first bill can include
  usage from before the sale. No such meter ships with the plugin.
- **Switching home plugins.** Homes enrolled under one home plugin stay out of payments while another is
  set. There is no command yet to move them. Set `property.backend` back to manage them.
- **WorldGuard owner changes.** WorldGuard edits an owner list in place. An owner change and a change back,
  with no payment in between, look like no change.
- **Stored waypoint sets.** Every waypoint edit keeps a full copy of the set in the database. Old copies
  are not removed yet.
- **Build dependencies.** The build checks the checksums of the jars in `libs/`. There is no Gradle
  dependency verification file yet.

### Upgrading

1. Stop the server and back up the plugin's database.
2. Put in the new jar and start. Database migrations V41 to V56 run on every upgrade from 0.2.0-dev.63 or
   older, whatever your switches and settings say.
3. Check the log, `/npcadmin health` and `/npcadmin diagnose`.

- Once the first bill is issued, an older jar cannot read the database. From then on you can only roll
  forward. The same is true after the first custom-priced charge or purchase. To go back, restore the
  backup of both the database and the plugin.
- Owner prices (`owner-prices`, `utility-prices`, `repair-prices`) are new. Leave them as shipped to keep
  today's prices. An invalid price block at startup disables the whole plugin. Check the log after every
  restart where you changed one.
- The patched ARM and `integration.arm-coordination: true` are only needed with `property.backend: arm`,
  the default.

[docs/GETTING-STARTED.md](docs/GETTING-STARTED.md#upgrading-and-rolling-back) has the steps.

## More detail

### Swappable parts

- **Adapter API 1.2** is inside the plugin jar. Other plugins can add a money source, a home plugin or a
  bill meter. See [docs/PLUG-IN-POINTS.md](docs/PLUG-IN-POINTS.md) and [docs/ADAPTERS.md](docs/ADAPTERS.md).
- `money.source` picks the money source. `accounting.sink` picks how payment totals are confirmed
  (`skript` or `internal`). `property.backend` picks the home plugin. Leaving them out keeps Vault, ARM and
  the Skript bridge, the same as before.
- With a home plugin other than ARM, the plugin checks every home's owner after each start. Payouts wait
  until that check is done.

### Bills

- Off by default. Nothing is billed until you add a `bills:` block, set `bills.enabled: true` and set
  both `bills.limits` values.
- Bills have fixed ceilings:
  - $200 per bill line. A policy's `max-cents` can set it lower.
  - $200 for one home's whole bill on one day. This one is fixed.
  - $1,000 per owner per day. `bills.limits.per-owner-daily-max-cents` can set it lower.
  - $25,000 per server per day. `bills.limits.server-daily-max-cents` can set it lower.
- A value above its ceiling is refused.
- Automatic collection is built but off. Owners pay their own bills unless you turn it on.
- An unpaid bill has no penalty unless you set `bills.on-unpaid: hold-rent`. Then a home earns no rent
  while its current owner has an unpaid bill from an earlier day, and the owner is told why.
- Bills never fund rent.

### Home plugins that cannot block owner changes

- Some home plugins cannot stop an owner change while a payment is running. With those, rent and
  appreciation payouts are held by default (`property.when-unguarded: hold-payouts`).
- `property.when-unguarded: reread-before-payout` is an opt-in. Payouts then run after a fresh owner read
  right before each one. A change in the moment between that read and the payout is not caught.

### Adapters

- No adapter jar for another home plugin ships in this beta.
- GriefPrevention: the adapter code and its tests are included. It was only tried briefly on a local
  16.18.7 test server. It cannot be built into a jar from this tree yet.
- Residence, Lands and PlotSquared: not included.
- `worldguard-listed` has not been checked against a real WorldGuard server. If WorldGuard cannot be read,
  nothing is paid.

### Owner prices

- `owner-prices` (maintenance, upgrades, eviction and insurance), `utility-prices` (the daily utility
  bill) and `repair-prices` (pests, plumbing and break-ins) set what owners pay, in quoted whole cents.
- A missing block keeps today's prices.
- A change needs a full restart. `/npcadmin reload` reports it but keeps the prices in use.
- An invalid block at startup disables the whole plugin. It never charges a fallback price.
- See the [README](README.md#owner-prices).

### Routes

- Staff waypoints and route files build walking routes without a palette scan. See
  [docs/PLUG-IN-POINTS.md](docs/PLUG-IN-POINTS.md#pathfinding-styles).

### Guides

- [docs/GETTING-STARTED.md](docs/GETTING-STARTED.md) for new server owners.
- [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) for supported plugins and versions.

### License

GPL-3.0-only. Copyright vxMac. The adapter API is part of the plugin, so an adapter that links it must use
a GPL-compatible license.
