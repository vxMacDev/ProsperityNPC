# Native accounting check

This separate lab plugin reproduces S8 with the actual accounting worker and
checks the active Vault money pin against its provider's JAR. It uses the same
Paper API and libraries as the core. Build it from the project root:

```sh
./gradlew nativeFaultProbeJar
```

Use `gradlew.bat` on Windows. The output is
`build/libs/ProsperityNPC-FaultProbe-0.1.jar`. It is outside the normal build and
is never bundled in ProsperityNPC.

Use a disposable test server and fresh MariaDB schema. The recorded setup uses
Paper 26.1.2 build 74, Java 25, MariaDB 11.4.5, Vault 1.7.3-b131 and EssentialsX
2.22.0. Install the core and probe JARs. No property backend, players, properties, funded accounts or
payment fixture is needed. Configure the core with:

```yaml
accounting:
  sink: internal
money:
  source: vault
centralbank:
  uuid: '00000000-0000-0000-0000-000000000001'
economy:
  payments-enabled: false
bills:
  enabled: false
item-repairs:
  enabled: false
switches:
  system-enabled: false
  economy-enabled: false
  incidents-enabled: false
  moves-enabled: false
  rendering-enabled: false
```

Merge these keys into the normal core configuration; keep its database settings.
The reserve UUID only identifies projection reads. Do not create or fund an
account for it. Give the test database user permission to lock its own tables.

The probe disables itself unless the server starts with this JVM flag before
`-jar`:

```sh
java -Dprosperitynpc.fault-lab=true -jar paper.jar --nogui
```

After the first normal startup finishes migrations, take a database snapshot and
back up the core/plugin configuration before running the checks.

Keep the server empty and use its console or loopback RCON:

```text
npcfault status baseline-status
npcfault accounting-race baseline-race
npcfault status baseline-fresh
```

Each command writes a new JSON file under
`plugins/ProsperityNPC-FaultProbe/`. Labels cannot be reused. Before the race,
the status must show `PASS`, `ready: true` and `inFlight: false`. A busy or
unready projection is an invalid setup; wait for ordinary ticks and use a new
label. The probe also refuses enabled switches/payments/bills/item repairs or
connected players.

The race holds the event-table lock, submits a real read through the existing
private tick, observes that SQL waiting for the lock, calls public invalidation
again, releases the lock, then consumes the actual completed old-generation
read. It briefly blocks the main thread to coordinate those steps. No generation,
completion, snapshot, clock or readiness field is replaced. The normal core must
report `PASS`, `successfulOldCompletion: true`, and
`afterOldCompletion.ready: false` with no snapshot. The later status must show
a newer ready snapshot from ordinary ticks. Status also records loaded class/JAR
hashes and compares the money pin with the concrete provider's own code source.

To check the detector, use a second source checkout:

```sh
git apply --check tools/faults/native/S8.patch
git apply tools/faults/native/S8.patch
./gradlew jar
```

The patch removes only the generation comparison. Stop the
test server before replacing its core JAR with this deliberately broken build.
Restart with the same disabled settings and repeat the three console commands
with new labels. The race must report `FAILED`, `successfulOldCompletion: true`,
`afterOldCompletion.ready: true` and exactly:

```text
Old accounting generation became ready after invalidation
```

`INVALID`, a failed read, compilation/startup failure or a different assertion
does not prove the detector caught S8. Compare the actual installed/loaded hashes
with both builds. Stop the server afterwards, remove the probe and mutant, and
restore the normal core/configuration. The probe creates only reports; archive
them and check that the test database's rows remain unchanged.

This checks private tick consumption on an empty server. It does not prove a
populated daily cycle, full scheduler/shutdown races, provider interruption,
paid transfers, connected-client behavior or whole-plugin performance.
