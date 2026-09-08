# Forge 1.7.10 compatibility registration regression

Run with a JDK directory containing `bin/javac` and `bin/java`:

```bash
python tests/legacy-integration/run_regression.py --jdk /path/to/jdk8
```

The harness compiles the repository's **real** `ForgeMain`,
`HodgePodgeAccessor` and `IHodgePodgeCommonAccessor` against narrow test doubles.
It models the first accessor capture performed by the common initializer's
client bindings. It deliberately invokes `initializeModCompat` again from the
common initialization stage; duplicate bindings fail the harness.

Scenarios run in separate JVMs with `-Xverify:all`:

- Physical client: Angelica, Iris, GregTech, RPLE and Hodgepodge are available
  before the simulated first consumer captures references.
- Physical client with a logical/effective server side: client integration still
  follows the physical side.
- Dedicated server: all optional graphical accessor classes and the client
  proxy are removed from the runtime classpath; only Hodgepodge binds, even when
  the loader reports graphical mod IDs as present.
- Dedicated server without Hodgepodge: its dependency classes are also removed
  from the classpath; initialization succeeds without those dependencies.

Hodgepodge cases also verify common/core aliases share one instance, startup
preserves an inactive experimental flag, the capability reflects subsequent
flag changes, negative chunk coordinates reach the helper unchanged, and the
prevent/allow calls retain their opposite semantics. Absent/inactive/enabled
startup diagnostics are checked.

The unmodified upstream at
`b4abe2f26bd1da4ce86ba5323711b2b50de84930` fails the first scenario with
`Angelica captured null before registration`. The patched files passed all four
scenarios on Temurin 8 and Temurin 21. The first three-scenario revision also
passed on Temurin 25; the test compiles against Java 8 APIs on a newer JDK.

These are **isolated Java regressions**, not Minecraft, Forge transformers,
OpenGL, shader, Crucible, performance, or full modpack tests. In particular,
this harness does not model Minecraft constructing `EntityRenderer` before
FML initialization. Its lightmap accessor must not capture an optional
integration in a static initializer; that is a separate lifecycle constraint.

API source references checked for the versions reported in the user's logs:

- Hodgepodge 2.7.189:
  `com.mitchej123.hodgepodge.config.FixesConfig.addSimulationDistance_WIP:boolean`
  and `SimulationDistanceHelper.preventChunkSimulation(World,long,boolean):void`.
- Angelica 2.2.8: `GLStateManager.getFogColor():org.joml.Vector3d`,
  `IRenderTargetExt.iris$getDepthTextureId():int`, `AngelicaConfig.enableIris:boolean`,
  `IrisApi.getInstance():IrisApi`, `isShaderPackInUse():boolean`, and
  `isRenderingShadowPass():boolean` remain declared in the official tagged source.

Matching source declarations is not verification of the user's actual JAR
hashes or runtime linkage. Hodgepodge's configuration flag does not certify
suppression under Crucible's chunk activity/ticket implementation.
