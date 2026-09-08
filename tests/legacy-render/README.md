# Legacy fog and RPLE regressions

```bash
python tests/legacy-render/run_regression.py --jdk /path/to/jdk8
python tests/legacy-render/run_regression.py --jdk /path/to/jdk21
```

This compiles the real `MixinEntityRenderer.java` and invokes its private
callback methods reflectively using narrow doubles for the event bus and
render helper. Cases verify:

- Sky (`-1`) and terrain (`0`) passes both post the original event exactly once.
- A listener's true/false cancellation result is returned unchanged.
- Terrain fog intervention happens after listeners; sky omits that intervention.
- A listener exception is propagated without subsequently invoking the helper.
- The renderer can be constructed and called with RPLE absent, then observes
  the accessor after FML registers it and updates its lightmap once per callback.

Patched source passed on Temurin 8 and 21 with `-Xverify:all`; compilation uses
Java 8 APIs. The unmodified pinned source fails the fog case because no event is
posted, and fails the independently selectable RPLE case because the static
accessor remains null after registration. Select `--case fog` or `--case rple`
and optionally `--source-root /path/to/baseline` to isolate those checks.

These results validate method bodies and deferred accessor resolution. They do
not validate Mixin transformation, `ordinal=1`, captured local variables, Forge
patch conflicts, graphics drivers, visible fog, shaders, or GPU state restoration.
Those require running the packaged mod in Minecraft with the relevant mods.

The early vanilla construction order is visible in the official FML 1.7.10
`Minecraft.java.patch`: `new EntityRenderer` precedes `finishMinecraftLoading`.
`FMLClientHandler.finishMinecraftLoading` subsequently calls `Loader.initializeMods`.

- https://raw.githubusercontent.com/MinecraftForge/FML/1.7.10/patches/minecraft/net/minecraft/client/Minecraft.java.patch
- https://raw.githubusercontent.com/MinecraftForge/FML/1.7.10/src/main/java/cpw/mods/fml/client/FMLClientHandler.java
