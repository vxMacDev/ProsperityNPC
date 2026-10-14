# Local dependencies

These jars are not on a Maven repository this build uses. They are third-party binaries,
so they are not committed. Put them in this folder before building.

Required to compile:

| jar | used for | where to get it |
|---|---|---|
| `advancedregionmarket-3.5.5.jar` | region ownership reads | AdvancedRegionMarket 3.5.5 release on SpigotMC (stock is fine for compiling) |
| `Citizens-2.0.43-b4220.jar` | resident avatars | Citizens CI, build 4220: https://ci.citizensnpcs.co/job/Citizens2/ |
| `floodgate-2.2.5-b138-fc99cfc.jar` | Bedrock forms | Floodgate for Spigot/Paper, version 2.2.5 build 138 (commit fc99cfc), from GeyserMC downloads |

Optional, tests only:

| jar | where to get it |
|---|---|
| `adventure-api-4.26.1-relocated.jar` | Citizens' relocated copy, under `plugins/Citizens/lib/net/kyori/adventure-api/4.26.1/` after Citizens has started once |
| `adventure-key-4.26.1-relocated.jar` | same, under `.../adventure-key/4.26.1/` |
| `examination-api-1.3.0-relocated.jar` | same, under `.../examination-api/1.3.0/` |
| `examination-string-1.3.0-relocated.jar` | same, under `.../examination-string/1.3.0/` |

Rename each file to the name in the first column and use the exact build listed.
`build.gradle` checks the SHA-256 of the Floodgate jar before compiling, and of the four
test jars when all four are present. Citizens writes its relocated jars with its own
timestamps, so your copies may not match the pinned hashes, and a mismatch stops the test
run. If that happens, leave the four test jars out: the build then skips the Citizens
linkage test and everything else still runs. Add all four or none; a partial set fails
the build. Release builds should run with `-PrequireCitizensLinkage`, which fails instead
of skipping when the four jars are missing.

None of these are packaged into the plugin jar. The server provides AdvancedRegionMarket,
Citizens and Floodgate at runtime.
