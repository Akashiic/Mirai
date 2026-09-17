# SSAO RAW native equivalence probe

This harness exercises the production `ssao/gl/ao.frag` path without Minecraft, using LWJGL2 and the installed Intel OpenGL driver.

It renders four deterministic 1280x720 depth scenes into the same `R16F` intermediate format used by `GlDhSSAORenderer`, reads back raw half-float bits, and requires pixel-level equivalence before a shader candidate can be considered safe.

The timing phase uses alternating A/B order, three warmups per program, 12 paired samples per scene, four draws per timestamp interval, and asynchronous `GL_TIMESTAMP` queries. Timings are synthetic GPU intervals, not Minecraft FPS claims.

Production uniforms are fixed to the values used by `GlDhSSAOShader`: 6 samples, radius 4.0, strength 0.2, minimum light 0.25, bias 0.02, and fade distance 1600 blocks.

Run with:

```text
python tests/rendering/ssaoraw/run.py
```

Current driver observed by the probe: Intel(R) UHD Graphics, OpenGL 4.6.0 build 27.20.100.9268. See `evidence/` for the exact reports.
## Current findings

- `raw-step6`: replacing `uRadius / uSampleCount` with `uRadius / 6.0` was bit-identical in all four R16F scenes, but showed no repeatable performance improvement.
- `raw-loop6`: replacing the dynamic loop limit with `i < 6` changed 113 and 50 R16F pixels in the two complex scenes. Rejected.
- `raw-specialized6`: combining constant divisor and constant loop reproduced the same 113/50 pixel changes and no consistent gain. Rejected.
- `raw-recurrence`: replacing per-sample sin/cos with rotational recurrence changed 7,872 and 3,097 R16F pixels in the complex scenes. Rejected despite small isolated timing reductions.

No shader candidate from this probe is approved for production at this checkpoint. The result deliberately preserves the current visual path while narrowing future research.
