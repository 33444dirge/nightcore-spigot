# Item NBT version regression

The repair preserves the existing `ItemNbt` base-32 NBT format and records the
runtime `DataVersion` at the item root. Unversioned data only bypasses the old
migration when native text is identifiable and the complete current item codec
roundtrip equals the original NBT. Ambiguous unversioned JSON literals retain
the legacy compatibility path.

## Build the repair

Historical optional integration dependencies prevent a full Maven build in the
verification environment. The maintenance build compiles the seven repair
classes from this checkout and retains every unrelated production entry.
It never packages compile-only integration stubs.

Requires Python 3, JDK 25, the original production NightCore JAR, and an already
prepared **disposable Shiroha 26.2 test server** containing `libraries/` and
`versions/26.2/shiroha-26.2.jar`.

```text
python tools/book-json/build_repair.py --base /path/to/original-nightcore.jar --seed /path/to/test-server --javac /path/to/jdk25/bin/javac
```

The accepted production base SHA-256 is
`4fbefc9a7f79b32d827fce542553f6143b03b33cb68801eae46ac101cd3562fe`.
Output and build details are under `target/`.
ZIP entry timestamps may differ from a previously built release; compare the
compiled entry contents when checking a rebuild.

## Run the server regression

The runner copies the prepared test server into a separate directory, binds only
to loopback, uses fresh SQLite configuration, installs the probe, and shuts the
copy down after the checks. It never starts the seed server. Pass the actual
CombatPets 2.7.2-13 JAR to compile the probe; `--with-combatpets` also enables its
real `DataHandler.GSON` / `PetDataSerializer` integration checks.

```text
python tools/book-json/run_probe.py --seed /path/to/test-server --plugin /path/to/repaired-nightcore.jar --combatpets /path/to/CombatPets-2.7.2-13.jar --with-combatpets --java /path/to/jdk25/bin/java --javac /path/to/jdk25/bin/javac
```

Run the same command twice to verify the saved book and pet JSON are read by a
new JVM. Results are in `target/book-json-test/runtime/plugins/BookVersionProbe/`
and the runner exits unsuccessfully for startup failures, timeouts, or failed
checks. The probe is only for an isolated test server: it calls server shutdown.

Verified on Shiroha `26.2-DEV-398b90d` (DataVersion 4903), Java 25.0.3 and
CombatPets 2.7.2-13: **22 checks passed**, including styled/plain/JSON-literal
pages, 20 roundtrips, source-version precedence, old JSON text migration,
shulker/bundle/projectile items, PDC/enchantments, custom-data discrimination,
unknown-field fallback and actual next-JVM file reads. No book JSON migration
errors were emitted in the accepted run.

The original production guide-book NBT was unavailable. Old-version samples are
controlled format fixtures; no original-book, live-player GUI, or full production
plugin-stack acceptance is claimed.
