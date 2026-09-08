# Legacy transport regression harness

`LegacyTransportAdversarialTest` compiles the actual transport codec, ingress
queue and shared admission budget with real Netty. It does not instantiate
Forge, Minecraft, `MessageRegistry`, or the whole client renderer. Passing this
harness is not evidence of successful Forge channel registration or a live
Crucible handshake.

Run from the repository root with JDK 21 and a real Netty JAR:

```bash
mkdir -p build/transport-tests
"$MIRAI_TEST_JDK/bin/javac" --release 8 -cp "$MIRAI_TEST_NETTY" \
  -d build/transport-tests \
  forge17/src/main/java/com/seibel/distanthorizons/forge17/network/LegacyPacketCodec.java \
  forge17/src/main/java/com/seibel/distanthorizons/forge17/network/BoundedPacketQueue.java \
  coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/network/NetworkIngressBudget.java \
  tests/transport/LegacyTransportAdversarialTest.java
"$MIRAI_TEST_JDK/bin/java" -cp "build/transport-tests:$MIRAI_TEST_NETTY" \
  LegacyTransportAdversarialTest
```

Tested using both Netty 4.0.10.Final (Minecraft 1.7.10 library baseline) and
4.0.23.Final (DH build dependency), with Java 8 and Java 21. All four combinations
passed 7,895 assertions. The concurrency case performs 80,000 admission attempts;
the deterministic parser fuzz case generates 5,000 frames.

Coverage:

- The frame copy never retains/releases Forge's borrowed buffer, and rejects
  invalid sizes before allocating a payload copy.
- A direction/header rejection reaches neither the supplied message constructor
  nor decoder. The harness exercises that mechanism, not the Forge class-to-ID
  registration table.
- Truncated input and trailing bytes release partially decoded owned resources.
- An encoder exceeding its capacity cannot emit a partial frame; temporary
  buffers are released on success and failure.
- Global/per-peer queue caps, rejection suppression, disconnect cleanup,
  expiry, token-bucket exhaustion and server reset are checked.
- Byte/count admission rejects overflow and conserves permits across concurrent
  acquire/release operations, including duplicate releases.

Pending integration gates: real Forge packet encode/decode and channel
registration; full `ClientApi` shutdown/reconnect path; real server tick thread;
successful reassembly and LOD transfer between the same Mirai build on both ends.

Wire decision: this build preserves the upstream 1.7.10 `[ID byte][body]`
layout inside Forge discriminator 0. It does not silently substitute the common
`[protocol version][ID short]` layout. Frames are capped at 32,766 bytes (plus
one Forge discriminator = 32,767); serverbound requests have a separate 4 KiB
cap. LOD fragments use 30 KiB. The smaller fragments/caps require matching
Mirai builds for the supported client/server deployment; legacy upstream peers
sending large fragments are rejected. Protocol-version negotiation is pending.

## World and executor cleanup

`test_shared_api_cleanup.py` extracts the actual transition and shutdown methods
from `SharedApi` and `ThreadPoolUtil` and compiles them with narrow cleanup
doubles. It checks context during close, detachment after failures, disposal of
an unpublished replacement, rollback after startup/listener failure, independent
cleanup attempts, exception aggregation, null renderer/pools and admission
shutdown. This is not a complete core or game integration test.

```bash
python tests/transport/test_shared_api_cleanup.py "$MIRAI_TEST_JDK"
```

The harness passed 68 world-transition and 11 executor-shutdown assertions on
JDK 21 using `--release 8`.

## Provided-library ABI audit

Compile and run `LegacyLibraryAbiAudit` with an ASM 9 JAR, a JDK 8 `rt.jar`,
and the actual legacy dependency JARs. Define the variables below as absolute
paths to those local artifacts; `MIRAI_TEST_ARTIFACT` must point to the exact
JAR being evaluated.

```bash
mkdir -p build/abi-tests
"$MIRAI_TEST_JDK/bin/javac" --release 8 -cp "$MIRAI_TEST_ASM" \
  -d build/abi-tests tests/transport/LegacyLibraryAbiAudit.java
"$MIRAI_TEST_JDK/bin/java" -cp "build/abi-tests:$MIRAI_TEST_ASM" \
  LegacyLibraryAbiAudit "$MIRAI_TEST_ARTIFACT" "$MIRAI_TEST_JDK8_RT" \
  "$MIRAI_TEST_GUAVA17" "$MIRAI_TEST_LOG4J_API_BETA9" \
  "$MIRAI_TEST_LOG4J_CORE_BETA9" "$MIRAI_TEST_NETTY4010"
```

The scanner visits every non-multi-release class in the artifact, including
bundled libraries. It checks direct symbolic references to Guava, Log4j and
Netty: types, descriptors, methods, fields, bootstrap handles, inheritance,
public access and static/instance agreement. JDK 8 definitions resolve inherited
members such as `Collection.stream`, `Enum.name` and `Map.get`.

The preliminary artifact (SHA-256
`171da8f78224f54d78810dbeeb3f013e3368b9b66e17458356c2a86b2769f2a8`)
contained 12,479 scanned classes and 140 unique target symbol uses; no missing
symbols or access/static mismatches were found against Guava 17.0, Log4j
API/core 2.0-beta9 and Netty 4.0.10.Final. This finding does not certify reflection,
string-constructed names, native code or the user's unknown installed library
versions. Repeat the scan and record the hash after the final build.
