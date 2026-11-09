# Child plugin lifecycle repair

The maintenance revision `2.16.1-foliafix-2` includes the book JSON repair from
`2.16.1-foliafix-1`. The embedded plugin version is `2.16.1`.

## Changes

`NightPlugin.onDisable` removes its exact instance from `NightCore.CHILDRENS`
in a `finally` block. Configuration reload retains the registration. Existing
shutdown hook order and exception propagation are preserved.

An actual live heap dump on Shiroha `26.2-DEV-96ca96b` also identified
`CraftServer.helpMap.helpTopics -> GenericCommandHelpTopic.command -> HubCommand.plugin`
as a strong path to a retired ExcellentCrates instance after registry removal.
After successful command unregistration, `CommandUtil` substitutes a native
`FormattedCommandAlias` containing strings for that command reference. It checks
the exact help topic class and command identity before writing the protected
`command` field through the existing `Reflex` helper. This depends on the verified
field structure of the tested server API.

The original topic, amended text and help indexes retain their identities.
First registration leaves help topic factory selection to the server.
Re-registration activates the cached metadata while actual execution continues
through the real command map entry. Custom help topic subclasses retain their
own lifecycle responsibility.

## Build

Requires Python 3, JDK 25, the verified `foliafix-1` JAR, and a prepared disposable
Shiroha 26.2 server containing `libraries/` and
`versions/26.2/shiroha-26.2.jar`.

```text
python tools/child-lifecycle/build_repair.py --base /path/to/nightcore-2.16.1-foliafix-1.jar --seed /path/to/test-server --javac /path/to/jdk25/bin/javac
```

The required base SHA-256 is
`0dbf807563c9f7f50eee684c5345d67c4b24b9acee8131ea01ee9f01a364ed37`.
Output is `target/nightcore-2.16.1-foliafix-2.jar`. The script verifies that exactly
`NightPlugin.class`, `NightCore.class` and `CommandUtil.class` change from that
base. All other ZIP entry contents are checked against the input. Historical
optional integration dependencies prevented the full Maven main-module build
in the verification environment.

## Runtime evidence

The independent `HotAudit.java` observer and `fixtures/` are the actual sources
used in the isolated server checks. They require the tested NightCore JAR,
server implementation and its library JARs on the compiler classpath. The
observer uses core `CustomHelpTopic` only for the factory regression fixture.
It exercises both weak plugin references and weak defining-loader references.
Only the observer holds the diagnostic weak references.

The test setup used ExcellentCrates `6.6.1-1`, PlugManX `3.2.1`, Shiroha
`26.2-DEV-96ca96b`, Java 25 and the native 26.2 protocol client `CEShiftAudit`.
Its `audit_item` fixture awards one diamond and its `audit_virtual` balance is 7.
The client generates real Shift clicks during the `csgo` opening.

Three idle unload/load cycles, one PlugMan reload and one running-opening
unload/load cycle produced five retired generations. The final result was zero
live retired instances and zero live retired classloaders. Normal disable and
both intentionally throwing lifecycle fixtures released their registrations.
Help text, permission visibility, topic identity, amendments, custom factories
and three execution labels passed their assertions.

The complete final run passed 56 of 57 assertions. One Shift click was observed
uncancelled while ExcellentCrates was already disabled and its opening was
stopped. The following player tick showed an empty inventory. That GUI assertion
remains a separately recorded failure. Earlier rounds also contained an
unattributed diamond-in-inventory signal without per-event evidence. The public
verification result retains these limitations.

`verify_results.py` reads the recorded result directory containing
`results.jsonl` and `run-status.json`, writes `validation.json`, and returns
nonzero for any failed assertion. The private test server configuration and
player databases are required to recreate the full local setup.

```text
python tools/child-lifecycle/verify_results.py /path/to/recorded-results
```

The book JSON / CombatPets regression in `tools/book-json/` passed all 22 checks
again with this revision on its original `26.2-DEV-398b90d` core. The release
verification file contains exact artifact hashes and the additional next-JVM
persistence results.
