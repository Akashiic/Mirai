# Shared IBO bind probe

Purpose: test the exact optimization attempted in `GlDhTerrainShaderProgram`: when `useSingleIbo()` is true, keep the shared quad index buffer bound across the VBOs of one LOD container instead of unbinding/rebinding it for every VBO.

The probe uses LWJGL 2.9.4 on an offscreen Pbuffer, exact RGBA8 readback, CPU command-submission timing, and GL timestamp timing. It does **not** certify Minecraft FPS.

The corrected probe preserves the production call order: the first VBO is bound before the shared IBO; subsequent VBOs reuse that IBO. `group=1` is the control because baseline and candidate issue the same GL calls.

Five repeated runs are stored in `evidence/`. All tested groups produced byte-identical RGBA8 output. GPU timing was effectively neutral. CPU submission generally improved when multiple VBOs shared one IBO, but the magnitude was small/noisy and the `group=1` control itself varied by a few percent.

Decision: **do not promote from this microbenchmark alone**. Promotion requires real `gpu_terrain` telemetry showing that production containers frequently have multiple VBOs, ideally 4+, because the optimization removes `2 * (vboCount - 1)` EBO state calls per container and does nothing useful for a single-VBO container.

The evidence records the SHA-256 of the exact uncommitted production diff used during measurement.
