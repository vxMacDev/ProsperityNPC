# AdvancedRegionMarket ownership hook

ProsperityNPC only enrolls properties, admits tenants and takes owner payments when it can
stop AdvancedRegionMarket (ARM) from changing a region's owner in the middle of one of its
transactions. Stock ARM 3.5.5 has no way to do that, so these features need a patched ARM.

This folder holds the patch:

- `NpcTransactionGuard.java`: a new class, `net.alex9849.arm.NpcTransactionGuard`. ProsperityNPC
  registers itself with it at startup.
- `ownership-writers.patch`: wraps ARM's ownership-changing code paths (buy, sell, set owner,
  transfer, offers, expiry, reset, delete, region add and remove) in
  `NpcTransactionGuard.begin(region)`. Owner changes made outside ARM, for example with
  WorldGuard commands, are not covered, so keep those for staff.
- `package-overlay.py`: swaps the patched classes into a copy of the stock ARM 3.5.5 jar.

## Building the patched ARM

This is the route we tested. It recompiles only the changed classes and swaps them into the
released jar, so everything else in ARM stays byte-for-byte the same.

1. Get the stock `advancedregionmarket-3.5.5.jar` (the same one `libs/README.md` asks for).
   `package-overlay.py` checks its SHA-256.
2. Check out ARM tag 3.5.5, commit `c728c755525226e1d2bfc51af7e5e3803d301c08`, from
   https://github.com/alex9849/advanced-region-market
3. From that checkout's root, apply the patch: `git apply ownership-writers.patch`
4. Copy `NpcTransactionGuard.java` to
   `advancedregionmarket/src/main/java/net/alex9849/arm/`
5. Get `worldguard-bukkit-7.0.18.jar` (WorldGuard 7.0.18 for Bukkit).
6. From this project's root, compile the changed classes:
   `./gradlew armOverlayClasses -ParmSource=<checkout>/advancedregionmarket/src/main/java -PworldGuardJar=<path to worldguard-bukkit-7.0.18.jar>`
7. Build the patched jar:
   `python arm-compat/package-overlay.py libs/advancedregionmarket-3.5.5.jar build/arm-overlay-classes advancedregionmarket-3.5.5-npc.jar`
   It reports 11 replaced classes, 1 new class and 1105 unchanged entries.
8. Install that jar in place of stock ARM, set `integration.arm-coordination: true` in
   ProsperityNPC's `config.yml`, and do a full restart.

A full build of ARM from the patched checkout was not tested. It also labels itself with the
upstream POM version (3.5.4-SNAPSHOT), not 3.5.5.

## What to expect

At startup ProsperityNPC logs "Cooperative ARM ownership hook registered" when the hook works.
If it logs "ARM ownership coordination unavailable", property features stay held.

While a ProsperityNPC transaction is in progress on a region, the patched ARM refuses an
ownership change on that region by throwing an error before anything changes. Players see
ARM's normal error handling, not a friendly message.

Once ProsperityNPC has registered the hook, disabling ProsperityNPC on its own makes ARM refuse
these operations until a full server restart. Never unload or disable ProsperityNPC by itself.

Test your ARM buy, sell and transfer flows on a copy of your server first.

## License

ARM (AdvancedRegionMarket by alex9849, https://github.com/alex9849/advanced-region-market) is
licensed under the Apache License 2.0. Upstream added that LICENSE in commit d1ddcdf (2025-08-01),
after the 3.5.5 commit this patch targets. `ownership-writers.patch` contains excerpts of ARM source
under that license; its full text is in `LICENSE-ARM`. The patch modifies ARM source files, which stay
under ARM's license. If you distribute a patched ARM jar, mark the changed files as modified,
as section 4(b) of that license requires. `NpcTransactionGuard.java` and `package-overlay.py`
are part of this project (GPL-3.0-only). Apache-2.0 code may be combined with GPL-3.0 code, so a
patched ARM jar you share as a whole falls under GPL-3.0.
