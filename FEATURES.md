# ProsperityNPC features

Everything the plugin does, grouped by who uses it. Every feature ships switched off, except nine
safety checks that ship on. Each safety check does nothing until the feature it guards is turned
on. The "On" line says what turns each feature on. Config keys are in
`src/main/resources/config.yml`. Staff route tools (`/npcnav`) are the one exception: they work
for staff as soon as the database is connected.

Common requirements, used below:

- **Talk:** Citizens, the database, the `system` and `rendering` switches,
  `beta.encounters-enabled`, signed skins in `resident-appearance.json` (read at startup, so
  restart after changing it), a published walking route, and a housed resident or a placed one
  with `town.enabled`.
- **Money:** the `system` and `economy` switches, `economy.payments-enabled`, `centralbank.uuid`,
  Vault, the Skript accounting bridge acknowledged, and the patched ARM with
  `integration.arm-coordination: true`. This is the gate for rent, bills, repairs and other
  property payments.
- **Town:** `town.enabled` plus the named sub-flag.

## Residents

- **A cast of three residents.** Mara Vale (restorer), Ellis Ward (shopkeeper) and Nico Reed
  (courier). Each has a fixed identity, household, personality and signed skin. They survive
  restarts and upgrades. Walking residents show only their name; staff previews carry an "[NPC]"
  tag. On: staff run `/npctown seed` (needs `town.enabled`), `/residentlab seed` (needs
  `beta.resident-lab-enabled`) or `/npcadmin roster <world> <market> <class> <max-daily-rent>`
  (needs the `system` and `moves` switches and ARM coordination), plus the skin file.
- **Your own residents.** With `residents.cast-file-enabled`, residents come from
  `plugins/ProsperityNPC/residents.yml` instead of the built-in three. Each entry sets a name,
  household, generation, trade, means, age, personality and home preference, and can add two
  greeting, about and home lines each. Up to 32 residents. `examples/residents.extended.yml` in the
  repo adds six: Petra and Tobin Lind, Otto and Bea Marsh, June Hollis and Ruth Calder. The file is
  read at startup only. If it is missing or fails a check, the built-in three are used and
  `/npcadmin health` says so. Seeding saves new residents and leaves saved ones as they are; one
  that differs from the file is named and kept as saved. Trades, prices, routines and errands stay
  in code, so the file can't add or price anything. A new resident stays hidden until its own
  everyday skin is installed. Turn the cast file on before installing skins for new residents.
  With it off, those skins make the whole skin file fail and no resident is shown. To turn it off
  again, first put back a skin file with only the generic and built-in three's skins. On:
  `residents.cast-file-enabled`, then a full restart.
- **Roster skin check.** Safety check, on by default (`residents.require-skin-for-roster`). With
  the cast file on, `/npcadmin roster` skips residents that have no everyday skin and says how
  many, and `/npcadmin roster-one` won't add one. It does nothing while the cast file is off.
- **Outfits.** A resident can have work and leisure skins as well as an everyday one. An avatar
  wears the outfit for its routine when it appears, or everyday if that outfit has no skin. With
  `appearance.outfit-change-while-rendered`, an avatar already on screen changes outfit when its
  routine changes. It waits until no player is within 16 blocks
  (`appearance.outfit-change-hidden-radius`) and nobody is talking to it. At most 2 avatars change per pass
  (`appearance.outfit-changes-per-tick`). Off: outfits are only picked when an avatar appears.
- **Skin tool.** `tools/skins` in the repo draws original skins in code, makes work and leisure
  versions by changing clothing colours only, shows them on a page on your own machine, signs
  approved ones through MineSkin and builds a checked `resident-appearance.json`. Signing checks
  that the texture Mojang serves matches the approved PNG pixel for pixel. It never overwrites a
  signed skin or a manifest.
- **Shown only near players.** A resident appears when a player is within 48 blocks. At most 3
  per player and 20 on the server. Set under `rendering`.
- **Real memories.** Residents remember a repair you paid for at their home and a note you
  delivered for them, and greet you by those memories. Nothing is invented.
- **Familiarity.** Stranger, Acquaintance, Trusted (12 points over 3 or more days), then
  Established (28 points over 7 or more days). A paid repair gives 4 points and a delivered note
  gives 3. At most 4 points per resident per day. Greeting spam earns nothing, and familiarity
  never changes rent.
- **Daily routines.** Home overnight, work by trade until 3 pm game time, the market from 3 to
  5 pm, leisure until 10 pm, then home. They walk published routes and never teleport. If a walk
  fails, the resident disappears and shows up again later on the route. They stay visible at
  home time and can still be talked to.
- **Moods.** With `housing.moods-enabled`, residents are Unhappy, Neutral, Content or Happy each
  day, based on the home's condition, open problems, whether the bill was paid and the rent. Mood
  moves at most 5 points a day. It changes what they say and what the owner desk shows. It never
  moves money. If voluntary departures are on, an unhappy resident may move out.
- **They survive eviction.** An evicted or relocated resident keeps its identity and memories.
- **Skin check.** `/npcadmin health` shows whether the skin file loaded and where the residents
  came from. If the skin file is missing or rejected, no residents are shown. `/npcadmin appearance`
  lists each resident's everyday, work and leisure skins. At most 114 skins.

## Talking to residents

- **Conversations.** Right-click a resident or type `/resident talk [name]`. Private, within 5
  blocks and in sight, 90 seconds. A walking resident stops for one 90 second window every 2
  minutes. On: Talk.
- **Longer conversations.** With `ux.conversation-sliding-deadline`, the 90 second limit restarts
  on every choice. A walking resident also stays stopped while you keep choosing, for up to 3
  minutes in total (`ux.conversation-pause-cap-seconds`). Everyone talking to that resident shares
  those 3 minutes. Near the end they say they can only stay a little longer. Then they walk on and
  cannot be stopped again for 1 minute (`ux.conversation-pause-cooldown-seconds`). A resident
  walking to a new home still stops for only 90 seconds. You can walk along and keep talking while
  you stay within 5 blocks and in sight. Restart after changing it.
- **Topics.** "How is your day?", "What makes a good home?", "Our history", "Business and
  partnerships", "Supplies and hired work", "Tell me about yourself", and Goodbye.
- **"Contracts and partners" topic.** Comes after Business while contracts or partnerships are on.
  Hidden while both are off. With partnerships on, the Business line says to ask about contracts
  and partners.
- **"Any tips?" topic.** Shows one server tip a day from `town.tips` and what you are doing right
  now (your errand or training). Hidden while the list is empty.
- **Show me around.** With `town.enabled`, "How is your day?" can offer "Show me the market" or
  "Show me a quiet stop". A compass then points to that stop.
- **Private speech bubble** over the resident, seen only by you, plus clickable choices on Java.
- **Bedrock native forms** for conversations, the owner desk and confirmations. On: Floodgate and
  Citizens. `ux.bedrock-typed-fallback` prints typed commands when a form cannot open.
- **Staff tools.** `/residentlab` (On: `beta.resident-lab-enabled`) and
  `/resident preview <name> [district]`, which lasts up to 10 minutes (On:
  `beta.resident-lab-enabled` and `beta.encounters-enabled`). `/resident dismiss` removes your
  previews.

## Town life

- **Civic residents.** Staff place a resident in town without a home with
  `/npctown place <resident> <district>`. `/npctown disable <resident>` removes the placement and
  `/npctown list` shows them. On: `town.enabled`.
- **Courier errands.** Six sealed-note errands, one per player per day. Three open at Stranger and
  three need Trusted. You have 3 hours to deliver the note. Cancelling or missing it still uses up
  the day. Delivering makes both residents remember it. No money, items or XP. On: Talk, Town,
  `town.errands-enabled`.
- **Directions.** A private boss-bar compass, in a straight line, to a fixed stop. For an errand
  it points to the recipient's market stop. It stops within 5 blocks or after 5 minutes. On:
  `town.enabled` plus the `system` and `rendering` switches.
- **Route authoring (staff).** `/npcnav scanhere|scanbox|taghere|corridor|validate|publish|info|list`.
  Always on for staff with `prosperitynpc.admin`. `nav.scan-below` and `nav.scan-above` set how
  far `scanhere` looks.

## Training and resident shops

- **Job trainer.** Mara trains builders and woodcutters: +5% Jobs money and +10% Jobs XP for 30
  minutes, once a day, from Acquaintance. You must already have that job. Jobs pays it, not the
  reserve. While it runs, Mara tells you when it ends. On: Talk, `town.enabled`,
  `town.training-enabled`, the `system` and `economy` switches, Jobs.
- **Resident supplies.** Mara sells 32 stone, Ellis 4 iron ingots and Nico 4 coal, at full
  material price plus a 5% fee, at least $1. Opens at Acquaintance. A price quote lasts 2 minutes.
  Collected to your inventory or ender chest, once. On: Talk, `town.enabled`,
  `town.commerce-enabled`, the `system` and `economy` switches, `centralbank.uuid`, Vault, the
  Skript accounting bridge, and a price feed you provide: a Skript (skript-reflect) or plugin that
  calls `commercePrices()` on the main thread, then `begin()`, `put(token, material, dollars)` for
  each name in `materials()` and `complete(token, version)`, at least every 10 seconds (a price
  lasts 10 seconds). Supplies do not need `economy.payments-enabled` or the ARM patch. If today's prices
  cannot be checked, new buys stop but paid orders can still be collected.
- **Hired work.** Material price plus a 35% fee, at least $1. Ready 5 minutes later. Opens at
  Acquaintance and the batch doubles at Trusted, up to 64. One hired job per player a day, and a
  declined payment still uses it. `ux.hire-used-notice` hides the hire button once it is used and
  says when it opens again.
- **Train button stays reachable.** `ux.reserve-train-slot` drops the last errand button when
  errands fill all three slots, so Train is still there.
- **Shop limits.** Per player 8 open orders and 512 units. Per server per day 64 orders, 512
  units, $10,000 and 128 units of any one material, set under `town.commerce`. A cancelled order
  still counts.

## Contracts

- **Contracts.** A resident asks you to bring plain items, back to them or to another resident,
  before a deadline. One contract a day (`town.contracts.per-player-per-day`, up to 3). Some need
  more familiarity first. "Find" points the compass at the other resident. Cancelling or missing
  the deadline still uses the day and costs no trust. On: Talk, Town, `town.contracts.enabled`.
- **Handing over.** Talk to the right resident and choose "Hand over". The items come from your
  inventory, not the ender chest, armor or offhand. Only plain stacks count: named, enchanted or
  damaged items are skipped. The items are gone for good. Survival mode only
  (`town.contracts.require-survival`, a safety check that ships on) and only in
  `town.contracts.handover-worlds` (empty means any world).
- **Reward.** Familiarity with the residents involved, still at most 4 points per resident a day.
  No money, no items, no XP. "Our history" and the contracts topic show how many contracts you kept
  for that resident in the last 30 days.
- **Your own contracts.** `town.contracts.definitions` holds up to 64, keyed by resident UUID. The
  six shipped examples use Mara, Ellis and Nico. A bad entry or a resident who is not on the
  server is skipped with a warning. A long title is shortened on its button. Errands are separate
  and unchanged.
- **Held handovers.** If a handover's result is unclear (a crash, a save that could not be proven,
  a wrong count), it is held for staff and never retried. You cannot take another contract until
  staff settle it. Staff use `/npctown contract-resolve <contract-id> <consumed|not-consumed>
  <case-ref>`. It never gives items or money back.

## Partnerships

- **Partnering with a resident.** A property owner with an enrolled home who is Trusted with a
  resident and kept 2 contracts for them in the last 30 days can partner with them for 7 days.
  You pay in items, handed over the same way as a contract. One partner at a time, 4 partners per
  resident, one partnership handover a day. On: Talk, Town, `town.partnerships.enabled`.
- **Perks.** Ellis takes 40% and Mara 25% off their service fee. The items in the order stay at
  full price and the fee is at least $1, so a partner never pays less than full material price plus
  $1. The rate is checked again when you buy, and once more just before you pay. If the partnership
  ended or the perk was lowered in between, nothing is charged and you ask for a fresh quote. Nico helps your homes fill first in daily matching
  (2 points, half of one advertising level). That needs `town.partnerships.rank-bonus-enabled` and
  `housing.advertising-rank-enabled`, and adds nothing past the top advertising level. No perk moves
  reserve money or changes rent, caps or funding.
- **Renew or end.** "Renew" shows in the last 2 days and adds 7 days to the current end date.
  "End partnership" ends it now with no refund. An unrenewed partnership ends on its last day.
- **Only offers that do something.** An offer is shown, started or renewed only while one of its
  perks works on this server. Nico's rank perk needs both rank switches above. If the offer or a
  switch changes while you are on the confirm screen, Confirm takes nothing and you start again.
- **Desk line.** `/property status`, the desk and the Bedrock desk show your partner and their perk.
- **Your own offers.** `town.partnerships.offers`, one per resident UUID. Lowering a perk in config
  applies to running partnerships at once; raising it applies to new ones only. A handover in progress
  settles at the perks and term you confirmed, or lower if config was lowered meanwhile. If the
  material changes or the quantity goes up during it, the handover is held for staff.
- **Staff.** `/npctown partners [resident]` lists partnerships by owner UUID. `/npctown partner-end
  <player> <case-ref>` ends one with no refund. Settling a held partnership handover as consumed
  starts or renews the partnership only while partnerships are on, the resident still has an offer
  for the same material and no more of it, and the seat and home checks pass. The reply says which
  one stopped it.

## Property owners

- **Enrollment (staff).** `/npcproperty enroll|pause|resume|unenroll|list|info|reconcile|armdump`.
  Staff stand in the plot. Only ARM sell regions on the market and class allowlists, 1 to 4
  rooms, up to 512 homes. On: patched ARM plus `integration.arm-coordination`.
- **Owner desk.** `/property status` (chest on Java, form on Bedrock, `text` for chat): rooms,
  residents and moods, condition, rent, open problems with repair quotes, upgrades, maintenance,
  insurance, utilities, auto-care, a 7-day money statement and the landlord record.
  `ux.desk-pause-controls` adds a Pause button and a Resume screen, and puts the rent line on the
  Bedrock form. The Java desk always shows the rent.
- **Pause.** `/property pause` takes your home out of the NPC economy. Rent and bills stop.
  Tenants, problems and history are kept. `/property resume` puts it back.
- **Tenant list.** `/tenants list` opens the desk on your residents. `/tenants list text` prints
  each one with its evict command.
- **Rent.** `/property rent <amount>`, 50% to 150% of the reference rent. With
  `housing.rent-change-delay-days`, a change applies after that many days and each resident is
  told once.
- **Maintenance.** `/property maintain <max>`: $10, +5 condition, once a day, not at 100.
- **Upgrades.** Facilities ($150/$350/$700) raise the home's score. Advertising ($100/$250/$500)
  puts the home earlier in the move-in order, but only while `housing.advertising-rank-enabled`
  is on. With it off, advertising can still be bought and does nothing. Buy with
  `/property upgrade <advertising|facilities> <level> <max>` or the desk.
- **Utilities.** The daily bill is $1 plus $0.75 per tenant plus $0.50 per upgrade level, paid
  with `/property utility pay <max>`.
- **Daily rent.** After 04:10 Chicago time. A home is paid only if today's utility bill was paid.
  Each tenant pays the lowest of the asking rent, the tier rent or $6. Caps: $15 per home, $45
  per owner, $150 per server a day, and the reserve never drops below its floor. Never pays
  twice, never back-pays. With `economy.rent-isolation-enabled`, one home whose owner cannot be
  read is held and the rest are still paid. On: Money.
- **Funding limit.** Each home starts with a one-time $50 seed from a $500 pool. After that,
  every $1 spent on the home funds at most 90 cents of rent and appreciation.
- **Cash appreciation.** With `economy.appreciation-enabled`, a well-run, fully rated home whose
  rent was paid earns up to $3 the next day ((score - 70) x 10 cents), after rent, inside every
  cap. It waits while any home still has today's rent unpaid
  (`economy.appreciation-ledger-rent-hold-enabled`, on by default).
- **Linked accounts.** When Floodgate links a Bedrock account and a Java account, both count as
  one owner for the per-owner daily limit on rent and appreciation ($45 as shipped). Each home
  still belongs to its ARM owner, pays rent to that account and is managed only by that account.
  Other limits stay per home or per account. The link is saved when the linked player joins from
  Bedrock and stays saved if they unlink. Staff end it with
  `/npcadmin links split <uuid> <uuid> <reason>`, from the next day.
  `/npcadmin links show <player>` lists a group. A player who linked and now plays only on Java is
  found only with `linked-accounts.lookup-floodgate-owners`, which asks Floodgate once a day (an
  outside call with global linking, none with Floodgate's own local linking). Turning it off takes
  effect the next day. Saved links still count without Floodgate. The old `npc_benefit_units`
  tables are not used. On: `linked-accounts.enabled` plus Money.
- **Upkeep problems.** Pests, plumbing and break-ins, 1% to 8% a day, more likely at low
  condition. Each costs 5, 8 or 12 condition and switches off one installed upgrade (advertising
  first) until repaired. One open problem at a time. No blocks are damaged. On: the `system` and
  `incidents` switches plus the patched ARM.
- **Repairs.** $20 pests, $35 plumbing, $50 break-in, with `/property repair <id> <max>` or the
  desk. A repair gives back the lost condition and turns the upgrade back on. Tenants remember it.
- **Repairs with items.** The desk repair button asks: pay cash, or hand in plain items from your
  inventory (also `/property repair <id> items`). The item list is set per problem in config and
  priced at full price-book value: the items must be worth at least 150% of the cash price, and
  the list only grows when prices fall. An unknown or stale price means cash only. The repair does
  the same as a cash repair and counts on the landlord record. No money moves, so it adds nothing
  to the home's rent funding. Only plain stacks in the main inventory count, never the ender chest
  or named, enchanted or damaged items. Survival only (`item-repairs.require-survival`, on by
  default). If the home changes during the hand-in, the exact items come back with
  `/property repair returns`. An unclear hand-in is held for staff, who settle it with the offline
  `ItemRepairRecoveryTool`. A cash repair or auto-care for a held problem is refused before any
  money moves (`item-repairs.block-cash-while-items-held`, on by default). Auto-care stays cash
  only. On: `item-repairs.enabled`, the `system` and `economy` switches, and a price feed that
  publishes the recipe materials.
- **Insurance.** $12 plus $2 per room for 7 days. It blocks one problem per term, starts the next
  day, does not cover a problem already open, and never pays out or refunds. From the desk, or
  `/property insure <max>` with `ux.insure-command`.
- **Auto-care.** `/property autocare on <max per day>` pays the bill, one repair and maintenance
  below 80 condition. The desk offers $10, $30 or $60 a day. `/property autocare status` and
  `/property autocare off` check or stop it. On: `auto-care.enabled` plus Money.
- **Vacation protection.** With `housing.vacation-days`, homes of an owner unseen for more than
  that many days are paused for the day: no bills, rent, problems, departures or mood changes.
  Residents can still move in. The desk and the public rating show "Owner away".
- **Sales.** The buyer keeps condition, upgrades and residents. The seller's record, insurance and
  auto-care do not transfer.
- **Receipts.** Every owner payment has a maximum price. Upgrades, maintenance, repairs,
  insurance and evictions keep a receipt, looked up with `/property receipt <id>`. The id is shown
  when a payment is held for staff review. An item repair's receipt id is shown when it is done.
- **Eviction.** `/tenants evict <id> <max>` or the desk, $10, named confirmation. With
  `housing.evicted-slot-lock-days`, the room stays closed for that many days.

## Tenant market

- **Daily matching.** Staff register the cast with `/npcadmin roster`. Vacant residents fill free
  compatible rooms each day: 3 moves per server, 1 per market and 1 per home a day, 7-day cooldown.
  On: the `system` and `moves` switches plus the patched ARM.
- **One resident at a time (staff).**
  `/npcadmin roster-one <resident> <world> <market> <class> <max-daily-rent> <standard|premium>`
  sets one resident's market, room class, rent limit and tier. It never moves a current tenant.
- **Landlord record.** A rolling 29-day owner score from 25 to 75, from bills on time, repairs
  within 24 hours and tenants kept. New landlords start "Unrated". The desk shows how close the
  owner is to premium.
- **Public star rating.** `/property rating` for anyone inside an enrolled home: half-stars or
  Unrated, rooms let, and condition as Excellent, Good, Fair or Poor. No private data. On:
  `housing.public-rating-enabled`.
- **Residents leave neglected homes.** At most one resident a day leaves a neglected home, and at
  most 3 a day per server. A home is neglected when a resident's mood is below 40 (with moods on),
  or when condition is below 50 or a problem is overdue (with moods off). No money moves. On:
  `housing.voluntary-departures-enabled`, the `system` and `moves` switches, and the patched ARM.
- **Premium tenants.** Staff mark a resident premium with `/npcadmin roster-one ... premium`.
  Premium residents only move into homes with score 80, condition 80, a landlord score of 70 over
  at least 7 bill days, rent at most 125% of reference and nothing overdue. They relocate only to
  homes at least 10 points better. `housing.premium-broadcast-enabled` announces a move in one
  line, at most once every 30 minutes. `housing.premium-walk-enabled` makes the resident walk there
  when both homes share a published route. On: `housing.premium-enabled` (restart required).

## Safety and operations

- **Daily cycle.** After 04:10 Chicago time, in this order: owner-away is saved, due rent changes
  apply, then problems, moods, departures, matching and rent. A step that fails stops the steps
  after it and alerts staff. `/npcadmin settle` runs today's cycle by hand; `incidents`, `match`
  and `rent` run one step. None of them can backdate a past day.
- **Journaled payments.** One at a time. An unclear payment is frozen for staff review, never
  retried or refunded automatically.
- **Payout retirement.** `economy.payout-retirement-enabled`, on by default. Payouts that can never
  be paid (still pending, never attempted, and their day has passed or the owner changed) are
  retired so they stop holding a home's funding. No money moves. Anything attempted is left for
  staff.
- **Owner-away recheck.** `economy.away-debit-recheck-enabled`, on by default. With
  `housing.vacation-days` above 0, owner-away is checked again right before money leaves an
  account. A home that became owner-away during a payment is not charged a bill or auto-care and
  gets no rent or appreciation. Repairs and purchases the owner chooses are never held. A payout
  whose money already left always completes. At 0 it does nothing.
- **Rent change guard.** `housing.rent-change-lease-guard-enabled`, on by default. On a day a
  delayed rent change is due, the step that applies it holds each home like a payment does, so a
  home's rent never changes while a payment for it is in progress. A busy home is retried every
  second for 30 seconds. If it is still busy, the change waits for the next daily cycle and that
  home's rent is held for the day. With nothing due it holds no home and writes nothing.
- **Plot owner checks.** Every 5 minutes the plugin checks each home's owner against ARM. A home
  busy with a payment is skipped and checked again a few seconds later. While a check is updating
  a home, new payments for that home are refused as busy for a moment. `/npcadmin health` shows
  how many homes are waiting. `/npcadmin reconcile-arm` runs a check by hand.
- **Kill switches.** `system`, `economy`, `incidents`, `moves` and `rendering`, all off by default.
  `/npcadmin switch|health|reload`.
- **Health check.** `/npcadmin health` shows the switches, database, ARM and the last owner check,
  the Skript bridge, the reserve account, caps, the floor and Chronicle status. While
  `linked-accounts.enabled` is on it also shows linked accounts and whether Floodgate uses global
  or local linking. While contracts or partnerships are on it shows contracts loaded and skipped,
  handovers held for review, active partnerships and whether the rank bonus is live.
- **Staff alerts.** With `alerts.enabled`: held payments, reserve-floor deferrals, the server cap
  at 90 percent, failed daily phases, a missing ARM hook, held rent homes, held item repairs,
  held contract handovers, held paid orders and paid orders whose save check keeps refusing. Each fires once a day per home or subject. `skript/npc-alerts-prosperitysmp.sk` posts them through the server's
  watch alerts: console, online staff and the watch Discord webhook (server-specific).
- **ARM patch.** While ProsperityNPC is working on a plot (a payment, a move or a daily check),
  ARM refuses any owner change on it: buy, sell, transfer, offers, expiry, reset or delete.
  Players see ARM's normal error. Never disable ProsperityNPC on its own: ARM then refuses these
  until a full restart.
- **Offline recovery tools.** `RefundRecoveryTool`, `CommerceRecoveryTool` and
  `ItemRepairRecoveryTool` in the plugin jar, and `ChronicleReceiptOperator` in its own jar
  (`ProsperityNPC-ChronicleRecovery`). `/npcadmin itemrepair list` shows held item repairs and
  items still to give back. It changes nothing. `CommerceRecoveryTool --inspect-delivery` reports
  one held paid order: its state, the recorded counts, the stopped server's player file and the
  order's save marker. It writes nothing, and there is no in-tool decision for held orders yet.
- **dev.60.** Paid order deliveries prove the player save with a per-delivery marker
  (`town.commerce.delivery-save-marker`, on by default, inert while commerce is off).
- **dev.61.** Skins whose Mojang texture id is shorter than 64 characters now load. Mojang drops
  leading zeros from the id, so about 1 skin in 16 was wrongly refused before.
- **Accounting bridge.** NPC money shows as its own sources; payments wait for its acknowledgement.

## Chronicle and publication

- **Chronicle.** Staff preview verified move-in stories with `/npcadmin chronicle preview`.
  Stories approved by name in a private approval file are sent once to a Discord webhook, with no
  retry. `chronicle.receipt-revision-evidence` keeps true stories valid after later rent or
  upgrade edits. On: `chronicle.enabled`, `chronicle.authorization-generation`, publication with
  approved scopes, the approval and token files, then `/npcadmin chronicle dispatch` from console.
- **Occupancy snapshot.** A private JSON file showing each approved home as empty, partly let or
  full, with an optional local-only endpoint. On: `publication.enabled` with homes in
  `publication.approved-scopes` and the private `publication` folder. The endpoint also needs
  `publication.http-port` and a token file. A reload stops it until restart.

## Not built

- Staff grouping accounts that Floodgate does not link (`/npcadmin links add`).
- Linked accounts counting as one owner for vacation protection.
