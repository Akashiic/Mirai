# World shutdown regression harness

The harness compiles these **production sources directly**:

- `AbstractDhServerWorld.java`
- `IDhServerWorld.java`
- `IDhWorld.java`
- `EWorldEnvironment.java`

`stubs/` supplies explicit, minimal dependency boundaries. No production file is copied, generated or modified by this harness. Stubs are outside normal project source sets and are not packaged into the mod.

Run with a JDK 9+ containing `javac` and `java` on `PATH`:

```bash
python3 testScripts/worldClose/run.py
```

Compilation uses `--release 8`. The Java test process has a **five-second external timeout**; an incomplete future that hangs `join()` fails the run instead of indefinitely blocking validation. No Gradle process, downloads or Minecraft startup is involved.

Validated with JDK 21: **29 assertions passed**.

## Cases exercised

1. `ServerPlayerStateManager.close()` throws: every level's unload callback and `close()` still run, the map is cleared, and the original manager failure is reported.
2. Two level workers throw independently (`RuntimeException` and `Error`): their futures complete exceptionally, other levels still close, both original failures survive in cause/suppressed chains, and `close()` returns by throwing rather than leaving `join()` pending.
3. A wrapper's `onUnload()` throws an `Error`, and a separate API unload listener throws a `RuntimeException`: both affected levels still execute `close()`, an unaffected level/event completes, the map is cleared, and both faults remain traceable.
4. Two wrapper keys reference the same level: exactly one unload event and one level close occur. Calling world close twice does not repeat manager or level cleanup.
5. A null server wrapper does not prevent its level from closing or produce an unload event with a null wrapper.
6. Two level close callbacks rendezvous through a two-party latch, proving that the actual world starts its shutdown workers before joining them.
7. Disconnect with a null level, an unloaded/missing dimension, or a throwing level removal callback always invokes session unregister; the callback's failure remains visible.

## What the dependency fakes do and do not establish

- The fake player manager exposes a counter and injected close failure. Its own queue/session cleanup is tested separately by `testScripts/serverQueue`; this test does not prove real manager and world integration together.
- Fake level/wrapper callbacks count calls and throw configured faults. They do not run generation workers, SQLite/native cleanup, Forge tickets, Minecraft world access, real mod callbacks or loader hooks.
- The fake API injector forwards to one controlled test callback. It does not reproduce the actual API event registry, listener order, cancellation or third-party listener behavior.
- The fake base world supplies only the inherited logger and environment. Logging backends, save structure initialization and diagnostics are outside the test.
- This validates **exception completion**, not a time budget for an arbitrary real level that blocks forever. The production world still waits for level cleanup; this harness does not claim bounded shutdown for deadlocked I/O or external callbacks.
- The fallback for `Thread.start()` failure is not fault-injected here. Java thread creation exhaustion and JVM allocation failures remain outside this run.
- The deduplication case uses two keys referencing the same Java level object. It does not certify unusual third-party `equals`/`hashCode` implementations.
- This is a targeted source-level regression run, not a full mod build or Crucible/Forge runtime certification.
