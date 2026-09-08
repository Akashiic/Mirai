# Client world shutdown regression harness

The harness compiles these **production sources directly**, without copying their algorithms:

- `DhClientWorld.java`
- `IDhClientWorld.java`, `IDhWorld.java`, `EWorldEnvironment.java`
- `IServerKeyedClientLevel.java`

Dependencies under `stubs/` are explicit fault-injection boundaries. They are outside normal project source sets and are not distributed in the mod. No production source is modified by the harness.

Run with `javac` from JDK 9+ and the target `java` runtime available on `PATH` (or provide `JAVAC` / `JAVA`):

```bash
python3 testScripts/clientWorldClose/run.py
```

Compilation uses `--release 8`. Execution has an external **five-second Java process timeout**, so a future left pending by an uncaught shutdown exception fails validation instead of blocking indefinitely. No Gradle process, dependencies download or game bootstrap is involved.

Validated with JDK 8u504 and JDK 21.0.12.1: **121 assertions passed on each runtime**. The 0.1.2 core source at `0b72fbafce8bb6dd89c19082a67e016989cfd98c` fails the negative control because it accepts level creation after world close.

## Cases covered

| Scenario | Verified outcome |
| --- | --- |
| Network shutdown throws `Error` and plugin reset throws `RuntimeException` | Both stages execute, every level closes, original failures remain traceable, and all world maps/sets are cleared. |
| Level workers independently throw `RuntimeException` and `Error` | Futures complete exceptionally, unrelated levels still close, both failures remain in cause/suppressed chains, and `join()` does not remain pending. |
| Wrapper unload and API unload listener fail | Affected and unaffected levels still execute their cleanup; failures remain visible. |
| Multiple map aliases reference one level | Exactly one level close, one wrapper unload and one unload event. |
| World close called twice, including after a failed first close | Network, plugin, timer and level cleanup are not repeated. |
| Null level wrapper | The level still closes and no null wrapper is sent to the unload listener. |
| Two cleanup workers rendezvous at a barrier | Both workers start before the production world waits for completion. |
| Maps and pending level-init objects seeded before shutdown | Level map, wrapper-set map and requested-level set are empty afterward, including failures. |
| Closed world access | Existing lookups return null; a new wrapper cannot invoke a constructor; unload cannot repeat cleanup. |
| Construction racing world close | The save structure cannot close during construction; the constructed level is included in shutdown and closes once. |
| Load callback requests world close | Ownership is visible before the callback; access is denied immediately; waiting chunks are skipped; shutdown finishes after exiting the lifecycle monitor. |
| Shutdown worker acquires lifecycle monitor | The worker completes, proving the caller does not join while retaining the monitor in the reentrant load-close case. |
| Load listener throws | The newly owned level is removed and closed; subsequent world shutdown cannot close it again. |
| Load listener unloads the level, then unload listener re-enters unload | Ownership is removed before callbacks; cleanup executes once and no closed level is returned. |
| Wrapper unload throws | The level still closes and ownership is removed before propagating the failure. |
| Constructor requests world close | Deferred shutdown takes ownership of the returned object and closes it before any waiting chunks are delivered. |
| Timer cancellation | Cancel occurs before network teardown; the real JDK timer rejects another schedule after shutdown. |

## Boundaries and remaining limits

- `ClientNetworkState` and `ClientPluginChannelApi` are fakes exposing cleanup counters and injected failures. This validates their **caller orchestration**, not their real internal networking, worker or plugin-state cleanup.
- Level and wrapper callbacks are fakes. Actual LOD generation, GPU/native resources, databases, Minecraft objects and mod callbacks are not executed.
- The fake `TimerUtil` returns a tracking subclass of **real `java.util.Timer`**: production scheduling and `super.cancel()` are executed. `clientTick()` is a no-op boundary; this does not certify synchronization against a real tick already executing when cancellation begins. JDK timer cancellation prevents future executions without waiting for an in-flight task.
- API event dispatch forwards to one controlled test callback, without the real listener registry or third-party listener ordering.
- Original shutdown fixtures seed maps using reflection. New cases also exercise real `getOrLoadLevel`, publication, callback failure and `unloadLevel` orchestration. Keyed wrapper fakes avoid the real one-second level negotiation delay; actual protocol negotiation and reconnect networking remain outside this harness.
- The five-second runner timeout detects a broken exceptional-future path. It does not establish a production shutdown deadline for callbacks, drivers or I/O that block forever.
- The fallback for a failure to start a Java thread is not fault-injected. JVM allocation failures and thread exhaustion remain outside this run.
- Logger, save structure and base-world diagnostics are compile-time boundary fakes. This is not a full artifact build or a Minecraft/Crucible runtime certification.


A prior-source negative control can be run without modifying production files:

```bash
python3 testScripts/clientWorldClose/run.py --world-source /absolute/path/DhClientWorld.java
```

The test runner retains an external five-second JVM deadline. It does not certify a runtime deadline for Minecraft callbacks or I/O. Level construction and ordinary unload remain serialized under the lifecycle monitor; extension callbacks must obey the normal client-thread contract and must not block waiting for another thread to mutate this same world. The change specifically prevents world-shutdown worker joins from retaining that monitor.

The lock-order regression reproduces the real inactivity timer's `synchronized(wrapper)` → `world.unloadLevel` ordering. The access marker executes before acquiring the world lifecycle monitor, avoiding the inverse order. A negative control moving `markAccessed` inside the monitor fails with `Inactivity cleanup inverted wrapper/world lock order`. The wrapper identifier is captured before construction and reused throughout publication and cleanup.
