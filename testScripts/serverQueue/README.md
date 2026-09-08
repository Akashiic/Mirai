# Server message queue regression harness

This harness compiles the **production** `ServerPlayerStateManager.java` and `SessionClosedException.java` with Java 8 API/bytecode targeting. The files under `stubs/` are explicit boundary fakes for the manager's collaborators; they are never compiled into the mod. The tests exercise the real manager's transitions, locking, caps, cleanup and readiness. They do not certify actual Forge/Crucible delivery order, network codecs, Minecraft callbacks or a rendered game session.

Run with a JDK 9+ containing both `javac` and `java` on `PATH`:

```bash
python3 testScripts/serverQueue/run.py
```

Validated on JDK 21: 34 assertions, including deterministic concurrent blocked-handler and disconnect/rejoin races. Compilation uses `--release 8`. No Gradle process or dependency download is used.

## Policy implemented

| Item | Limit or behavior |
| --- | --- |
| Joined player's pending messages | At most 128 queued messages, plus at most one message currently being handled. |
| Messages before join | At most 8 per player wrapper. Extra messages are dropped, retaining the earliest handshake messages. |
| Player wrappers before join | At most 64 across the manager; additional wrappers are refused until a slot is freed. |
| Prejoin expiry | 30 seconds from first message; incoming traffic does not extend the timestamp. |
| Joined queue overflow | Clear the queued messages and close that DH network session with an explicit queue-limit reason; this manager does not kick the Minecraft connection. |
| Handler readiness | `registerJoinedPlayer` creates state but does not drain; the existing explicit `handlePluginMessagesFromQueue(state)` call enables delivery after level handlers have been registered. |
| Disconnect | Remove player state and its message queue atomically, clear queued payload references, then close session/resources outside the lifecycle lock. |
| Manager shutdown | Reject future messages and joins, clear all maps/queues, close every player even if one close fails, and report the first cleanup exception with other failures suppressed. Repeated shutdown has no effect. |
| Duplicate join | Return the existing state instead of replacing and leaking its resources. |
| Stale drain/flush | Validate both player-state and queue identity so an old session cannot enable or consume a replacement session's queue. |
| Unexpected callback failure | Release the draining flag in `finally`; a subsequent enqueue can resume retained messages. |

## Practical limits of this patch

- Caps measure **decoded message objects**, not payload bytes. The transport must independently enforce packet size, message direction, field limits and decoding policy. This patch does not impose a global memory ceiling or fix allocations made before queue admission.
- Expiry is **lazy**: checked on prejoin traffic and join registration. An idle manager can retain up to 64 expired prejoin wrappers and 512 messages until another such operation or shutdown. No background expiry thread is introduced.
- The wrapper interface has no connection-liveness query. A late callback arriving after disconnect can create a new, bounded prejoin queue for that wrapper; this has the same cap and expiry as every other prejoin arrival. A previously acquired queue cannot resurrect itself or drain into a new session.
- A handler already executing outside the lifecycle lock may finish after disconnect/shutdown begins. The manager does not interrupt game/world callbacks; it prevents subsequent polling for that old session. Handlers still need their own world/task lifetime checks.
- Queue limits are not a bandwidth or CPU-rate limiter. Continuous traffic may keep a consumer active; existing protocol/request limits remain responsible for restricting the work accepted by message handlers.
- `getDroppedMessageCount()` counts admission rejections and expired pending messages. It is not a complete count of every message discarded during close/overflow or already-closed-session traffic.
- Only the Java harness was run here. Full artifact compilation and Minecraft/Crucible integration must be checked in the project build and runtime stages.
