# Interface fault checks

Run from the project root with JDK 25 and the normal build dependencies:

```sh
./gradlew seamFaultChecks
```

On Windows, use `gradlew.bat seamFaultChecks`. To select specific checks:

```sh
./gradlew seamFaultChecks "-PseamFaults=S1,S2,S3"
```

By default, the command compiles the existing tests, runs seven healthy test groups, then
checks ten deliberate source changes: S1–S7 and S9–S11. It writes changed copies
and compiled classes under `build/reports/seam-faults/run-<id>/`; project sources
are left alone. Each test group runs in a fresh JVM. No Minecraft or database
server is started. Once dependencies are cached, `--offline` works too.

`seams.json` lists the exact edit, selected test class, expected case count and
the methods that must fail. An edit must match once. A fault counts as caught
only when the expected assertions fail against the changed class. Compilation
errors, wrong class origins, skipped or aborted tests, unrelated failures and
surviving faults fail the command. Reports contain compiler output, test output,
source hashes, loaded code origins and the individual results. Changing a test
group requires reviewing its count and expected failures here. Each JVM has a
two-minute limit. Its temporary files stay under its report folder and are
removed after a successful check; failed runs retain them for inspection.

## Billing faults

```sh
./gradlew billFaultChecks
./gradlew billFaultChecks "-PbillFaults=C2,C5,C19"
```

`bills.json` covers C1–C10, C12–C17 and C19–C26 (24 faults). It uses the same runner with existing
unit tests and selected real MariaDB integration methods. The SQL fixtures start
their own database on a temporary port, with no connection to a running Minecraft
server or its database. Allow at least 6 GB free on the report drive; use
`-PnpcBuildDir=<path>` to move build output. Reports go under
`build/reports/bill-faults/run-<id>/`.

Each distinct method selection has its own healthy baseline. Parameterized
methods list their argument types and expected invocation count. As with the
interface checks, changed counts, skips, aborts and unrelated errors fail the
command. C19 uses the fixture's deliberate plain `AssertionError("cache read")`
tripwire; only that exact type/message in the named method counts as its failure.
Other cases require the usual JUnit assertion failures.

C11 (owner-away admission) and C18 (disabled bill phase) have separate native-server
results in the [bill recording](../../docs/testing/bill-gate-recording.json). They are not
included in this command; their portable native reproduction remains unfinished.

These are component checks. S3's fake provider is loaded from test classes; the
separate [native recording](../../docs/testing/seam-check-recording.json) compares the
real EssentialsX provider and Vault API JAR hashes. S8 needs the actual accounting
worker/invalidation race; the separate [native probe](native/README.md) provides
its build and console steps. It is not run by these commands. Full native bill
reproduction, provider lifecycle, connected clients and whole-plugin load
acceptance remain separate work.

The tool uses the existing compiler and test dependencies. Its classes and fault
definitions are not packaged in the plugin JAR, and it is not part of the normal
`build` task.
