# GriefPrevention 16.18.7 recording

`events.json` came from the official plugin on Paper 26.1.2 build 74 with the
adapter registered through the core's normal startup. The identities are synthetic.
Money and movement switches were off. The file records vendor API calls, not player
commands or a RealEstate installation.

The transfer summary captures three observed facts: GP honours a cancelled
transfer, its MONITOR event runs before the owner changes, and the adapter skips
cancelled events. Post-call assertions checked whether each change applied.
`GpSubject` uses that order; its claim IDs and coordinates are normalized fixtures.
Compatibility failures and the RealEstate-presence flag are separate model inputs.
The backend tests also change that flag after guard installation in both directions.
Reads stay unavailable after the change, including a round trip, until a new backend is started.

`events.json` includes creation, transfer, deletion and explicit API ID reuse.
`quirks.json` adds seven real event snapshots: another listener's cancellation,
checked staff resizing, depth extension and a later HIGHEST listener overriding
the actual veto before MONITOR. Applied changes invalidated a held subdivision
lease; cancellation elsewhere left its owner and lease unchanged. The model now
replays that override flow, including the contract case that fails when an
overridden veto is not followed by a lease bump.

The resize ran GP's checked method with a real synthetic CraftPlayer, a no-op
memory transport and a private staff permission attachment. It was not a client
shovel interaction. The lower-level `DataStore.resizeClaim` sends no change event;
an initial fixture using that method incorrectly expected a lease bump. No adapter
change followed from that fixture failure.

`lifecycle.json` records two actual adapter disable/enable cycles. Disabling
removed its service and both transfer handlers. Re-enabling registered one service
but left the core unavailable with no handlers; a normal Paper restart restored
one service, two handlers and the guard. This used an empty enrolled set and did
not exercise an in-flight payment.

`paid-bill.json` records one configured-bill payment using real local Essentials
accounts through Vault, normal GP enrollment, bill issuance, inventory handlers
and internal accounting. The displayed $1.00 maximum moved balances from $10/$10
to $9/$11; a stale confirmation moved nothing. GP vetoed a parent transfer while
the actual preparation query waited and held a subdivision lease. The proved
receipt and accounting recorded the payment; rent funding stayed unchanged.
The test used dated policy history, a synthetic CraftPlayer and dispatched
inventory clicks. It did not test a real client or scheduled populated daily cycle.

`bill-refusals.json` adds public-command maximum and insufficient-funds refusals,
both without payment rows or money movement. Removing permission while invoice
preparation waited produced a proved before-call decline and left the invoice
unpaid. Restoring permission and confirming a fresh view paid it once; both the
declined and paid receipts validated. Provider failures and restart recovery are
separate cases that this recording does not cover.

`bill-close-restart.json` records runtime close while invoice preparation waited
on a SQL lock. The committed payment survived a full Paper and MariaDB restart with
no transfer intent or money movement. A public command originally held the whole
payment runtime; the fix refuses that invoice and releases the new request's lease.
The same runtime then paid another home's bill through Essentials/Vault while the
original pending row stayed unchanged. Automatic collection was off and startup
switches stayed off until explicit test actions. This does not resolve the original
row or test a process crash during a provider call.

These records do not cover off-main delivery, mutation at MONITOR or the RealEstate
add-on's payment flow. GP supplies no transfer exception reasons in these fixtures,
so the contract's never-cancelled loop is empty and is not evidence for resize or
delete behavior.

The model suite does not run a server or reproduce provider payments. The native
records have the scopes stated above; they do not establish complete vendor,
client or payment-lifecycle support. Vendor checksum pins and adapter packaging
also need their own verification.
