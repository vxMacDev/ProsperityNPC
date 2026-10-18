# Test recordings

These files record checks we ran by hand on a local test server, in addition to the automated tests in
the source tree. They are evidence for what was checked, not instructions. You do not need them to run
the plugin.

## The test server

- Paper 26.1.2 and Java 25, on one local machine.
- MariaDB 11.4.5.
- EssentialsX 2.22.0 through Vault, as the money source.
- GriefPrevention 16.18.7 with a private test build of the GriefPrevention adapter, as the home backend.
  That build is not the same as a released adapter, and no adapter jar ships in this beta.
- No players connected. Owners and players were synthetic test profiles.

Each recording names the database schema it ran on. The earlier ones ran before later migrations
existed. The core build each one used was a private development build, so its hash will not match a
released jar.

## What each file covers

| File | What was checked |
|---|---|
| [OWNER-PRICE-CHECKS.md](OWNER-PRICE-CHECKS.md) | A plain-language walk through all the owner price checks below |
| [owner-price-recording.json](owner-price-recording.json) | Configured maintenance and upgrade prices: refusal above the owner's maximum, payment at the shown price, and old prices kept after a restart with new prices |
| [auto-care-price-recording.json](auto-care-price-recording.json) | Scheduled auto-care paying the configured maintenance price, and staying off when its budget is too low |
| [eviction-price-recording.json](eviction-price-recording.json) | The configured eviction price |
| [insurance-price-recording.json](insurance-price-recording.json) | Configured insurance premiums, from the owner desk and the typed command |
| [utility-price-recording.json](utility-price-recording.json) | The configured utility tariff, and receipts that stay readable |
| [repair-price-recording.json](repair-price-recording.json) | Configured repair prices at startup, manual payment, and saved prices kept for old incidents |
| [repair-auto-care-price-recording.json](repair-auto-care-price-recording.json) | Auto-care paying a configured repair price within its ceiling, and keeping the price it accepted |
| [repair-material-preview-recording.json](repair-material-preview-recording.json) | The item-repair preview, and refusal before any items are taken |
| [item-storage-readiness-recording.json](item-storage-readiness-recording.json) | That item features stay held when the player-data storage cannot be checked |
| [bill-gate-recording.json](bill-gate-recording.json) | Bills: an owner-away home is not admitted to payment, and the bills step is skipped while bills are off. Two deliberate faults were each caught. |
| [seam-check-recording.json](seam-check-recording.json) | Money source selection, owner tokens, call order, an accounting timing race, and that the money source hashes the real EssentialsX jar rather than the Vault jar |

Every recording ends with a list of what it did not cover, under `limits`, `notVerified` or
`coverageLimits`. Read those lines before you rely on a result.

## What these recordings do not cover

Across all of them:

- No real Java or Bedrock players, and no owner menus used by a real client.
- No whole-plugin test under player load. The 50 to 80 player case is not measured.
- No complete daily cycle on a populated server.
- No money provider call that was interrupted part way, and no recovery after one.
- No released GriefPrevention adapter, vendor checksum pin or adapter jar.
- Bills: two of the bill fault checks (owner-away admission and the disabled bills step) have only these
  local results. A version anyone can rerun is not finished.

The automated fault checks that anyone can run are described in
[tools/faults/README.md](../../tools/faults/README.md).
