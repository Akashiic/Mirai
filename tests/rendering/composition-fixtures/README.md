# Composition regressions

Run `python3 tests/rendering/run_composition_probes.py` from the project checkout.
Run with `--baseline` to extract the pinned 0.1.3 production sources and require the
specific old failures. Output goes to the adjacent `homologation-0.1.4-evidence`
directory. The script uses the retained 0.1.3 JAR for unchanged API/core classes,
the Gradle LWJGL/log4j cache and the workspace Java 8/21 toolchains.

Actual production classes compiled for this test: `LodRenderer`, the far-fade
renderer and both of its shaders, `GlAbstractShaderRenderer`, `GlShaderProgram`,
`GlShader` and `GlResourceCleanup`. They run with explicit doubles for the driver,
Minecraft wrapper, GL state scope, screen quad, memory stack, buffer-list provider
and fog-parameter factory. The real API event injector dispatches the test
listeners. Resource shaders are loaded, but the fake compiler does not execute
GLSL; the separate offscreen test owns actual pixel assertions.

`LodPassProbe` tests absent/present adapters, active/inactive shaderpacks, optional
AA, the single main-pass shader activity read, event order and cancellation,
transparent/deferred passes, partial setup failure and suppressed restore failure.
`FarFadeProbe` tests the actual composition path, mandatory inputs, allocation,
compilation and framebuffer-status failures, resize/reuse, both quad stages,
cleanup aggregation and blocked retries. Its final case installs the real
compositor into the real `LodRenderer`: an incomplete FBO must prevent the apply
event and final copy, while both scopes close.

Each Java runtime executes seven probe modes. The full far-fade mode repeats the
no-adapter render checks as setup for the combined boundary; the four focused
modes repeat individual failure cases so baseline failures identify their cause.
These assertion counts must not be presented as independent Minecraft scenarios.

The test does not start Minecraft or certify Intel/Windows, real GL state cache
restoration, full Angelica event integration or all third-party shaders.
