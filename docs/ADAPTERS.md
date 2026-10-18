# Writing a ProsperityNPC adapter

An adapter is a separate Bukkit plugin that connects a money or land plugin to ProsperityNPC.
The API is `com.prosperitysmp.npc.api`, version **1.2**, and ships inside the core plugin jar.
Compile against it without bundling another copy.
The shared build currently targets Java 25 and Paper `26.1.2.build.74-stable`.

The core includes Vault money (`vault`), AdvancedRegionMarket homes (`arm`), read-only listed
WorldGuard homes (`worldguard-listed`, unverified against a real WorldGuard server), and Skript or
internal accounting (`skript`, `internal`).
Third-party services extend money sources and property backends. Accounting sinks are built in.

## Register an adapter

1. Put `depend: [ProsperityNPC]` in `plugin.yml`, along with the vendor plugin your adapter requires.
   Use `compileOnly` for the API, Paper and vendor libraries. In this source tree,
   `apply from: rootProject.file('gradle/adapter.gradle')` supplies the shared build and jar checks.
2. Implement `MoneySource` or `PropertyBackend`. Use an id of 2–32 lowercase letters, digits and
   dashes, starting with a letter. The reserved ids are `vault`, `arm`, `worldguard-listed`,
   `skript`, `internal`, `flat`, `derived`, `scan`, `waypoints` and `file`.
   Return the API constants from `apiMajor()` and `apiMinor()`, and describe capabilities accurately.
3. Register the service in `onEnable`, before server startup finishes:

   ```java
   getServer().getServicesManager().register(
           PropertyBackend.class, backend, this, ServicePriority.Normal);
   ```

4. Select its id with `property.backend` or `money.source` in the core configuration. Restart and
   run `/npcadmin diagnose` to check the selected service and any refusal.
5. Run the shared contract tests and verify the packaged jar. `adapters/example` shows the test
   setup; it is a development fixture that knows no regions, not a usable property provider.

The example has completed checks for empty ownership reads and guard refusal. Its
inherited region and vendor-event contract cases are inapplicable and skip; they
are not evidence of working region ownership or protection. A real adapter must
supply the relevant region/event fixture and pass its applicable cases.

Selection happens once at startup. Priority does not break ties: duplicate ids, missing services
or incompatible API versions select nothing. There is no fallback to another backend.
The core accepts API major 1 with a minor no newer than its own. An adapter using the 1.2 guard
methods must declare minor 2. Adding, replacing or removing a provider requires a restart;
reloading settings does not bind a replacement service.

## Money sources

A source must report `scale: 2` and `artifact-hashable: true`. `pin()` captures one provider for a
transaction. The pin checks that the same registration and its plugin remain enabled, and gives
the provider's own jar for hashing, rather than an API jar.

Return the provider's raw balance double without rounding. Balance and availability reads must
not move money. Each `Pin.leg` makes exactly one provider withdraw or deposit for `cents / 100.0`.
Propagate provider exceptions unchanged; any return, including `null`, means the call was made.
The core owns admission checks, the journal and recovery. Do not add a second transfer or retry
inside the adapter.

## Property owners and guards

Property reads run on the main thread. Return the one paying principal, not trusted players or
members. Tokens identify the world, region, owner and live region incarnation. A deleted region
recreated under the same id must not validate an old token. Report weak identity or an inability
to detect a silent owner round trip honestly in capabilities.

Declare identity with the `token-identity` capability, one of `object | generation | weak`:

| Value | Meaning |
| --- | --- |
| `object` | The token holds the vendor's live region object; a recreated region is a different object. |
| `generation` | The token holds a vendor counter or id that changes when the region is recreated. |
| `weak` | A deleted region recreated under the same id cannot be told apart from the old one. |

A missing value means `weak`, and so does any value other than `object` or `generation`.

`observe` distinguishes `MISSING` from `UNREADABLE` and never throws. A provider error or unloaded
world is not evidence that a home was deleted. `available()` must also reflect whether the guard
still matches the active vendor and its relevant add-ons.

The core calls `installGuard` once, then reads `guardBindings` after a successful install:

| Mode | Behavior |
| --- | --- |
| `WRITE_HOOK` | Supported for the built-in ARM hook. A third-party claim of this mode is refused. |
| `VETO_EVENTS` | Veto held owner changes only where the vendor honors cancellation before moving money. |
| `NONE` | Observe changes without vetoing them. Rent and appreciation wait by default. |

Return Bukkit handlers through `GuardBinding.of(...)`. The core registers them under the adapter
plugin and wraps failures so a broken handler holds payments. Wrap non-Bukkit callbacks with
`hooks.guarded(...)` yourself. Use `isHeld` for a veto decision, `externalChange` to invalidate
affected regions on the main thread, and `markDirty` for changes delivered off the main thread.
Include dependent regions: changing a parent can change a subdivision's paying owner too.
Do not cancel an event the vendor ignores after it has already charged someone.

Every non-ARM backend needs a startup ownership pass before payouts. `NONE` and weak identity
follow `property.when-unguarded`, whose default is `hold-payouts`. The explicit
`reread-before-payout` setting permits payouts after a fresh owner read; it does not make vendor
owner changes atomic. A failed or unavailable backend holds admission regardless of that setting.

The GriefPrevention adapter uses veto events only without RealEstate. If RealEstate is present at
startup it uses `NONE`. A later observed change in presence holds the backend unavailable until
restart, even if presence returns to its original state.

GriefPrevention lets a later listener at `HIGHEST` priority undo the adapter's cancel before the
event finishes; the 16.18.7 recordings show this. So the veto is not what keeps a payout safe.
Safety rests on two things: the adapter's `MONITOR` handler, which runs for every transfer that
was not cancelled and moves the fence on for the claim and all its subdivisions, and the lease
check the core makes before each payout, which then refuses it. That is the same protection the
`reread-before-payout` opt-in relies on.

## Tests and packaging

Extend `MoneySourceContract` or `PropertyBackendContract` from the API test fixtures using a small
fake vendor store. Property guard cases need `GuardControls` that reproduce the vendor's actual
event order. Document applicability skips. Model tests cannot prove a vendor honors a veto;
record that behavior against the exact vendor jar separately.

The contract fixtures live in this source tree only, under `api/src/testFixtures`. Nothing is
published to Maven, so build and test an adapter inside a copy of this tree, as
`adapters/example` and `adapters/griefprevention` do.

The default `build` checks the core and example jar. Vendor modules are included with `-Padapters`.
For example, `:adapter-griefprevention:test -Padapters` runs the vendor-free model suite.
A vendor package also needs its vendor sources compiled, `verifyPinnedVendorApis` and
`verifyAdapterJar`. Its API jar must match a filename/SHA-256 pin recorded from two independent
downloads; an unpinned or mismatched jar fails the build. `-PprintVendorPins` prints the SHA-256
of each resolved vendor API jar and then fails, so it helps record a pin but never produces a jar.
The package must contain neither ProsperityNPC API classes nor vendor/library classes provided
by the server, and it carries the license text as `META-INF/LICENSE`. `jar` alone does not run
the packaging verifier; use `build` or the explicit gate.

## What this beta includes

No vendor adapter jar ships in this beta.

| Adapter | Status in this beta |
| --- | --- |
| GriefPrevention | Source plus model tests in `adapters/griefprevention`, with limited lab recordings on GriefPrevention 16.18.7. Labelled `unverified`. Its vendor pin is empty, so packaging stops at the pin check. |
| Residence, Lands, PlotSquared | Not included. This tree has no adapter for them. |

No vendor adapter should be presented as verified support on the strength of its label or model
tests alone. The example adapter is a development fixture and never a release artifact.

## License

The API is part of ProsperityNPC and is licensed GPL-3.0-only; see [LICENSE](../LICENSE). An
adapter compiles against the API, so an adapter you distribute must use a GPL-compatible license.
