# CLAUDE.md — Akashic: Horizons of The Mirai (AOTM)

Guidance for Claude Code (and any other agent) continuing this project. Read this file first, then
`docs/PROJECT-STATE.md`, then the pinned GitHub Issues.

## What this project is

A **stabilization fork of Distant Horizons 3.2.1-b-dev for Minecraft 1.7.10** (Forge 10.13.4.1614, **LWJGL 2.9.1**, Java 8
runtime classes) for the **Forbidden Series** modpack (Technic launcher) and a **Crucible / Java 21** backend. The owner
calls it *Akashic: Horizons of The Mirai* / *Akashic of The Mirai* / **AOTM** / "Mirai". It is the 1.7.10 backport of the
proposal of Distant Horizons + Voxy: render far LOD terrain cheaply with LWJGL2 while keeping the pack's look.

* Mod id `distanthorizons`, config `DistantHorizons.toml`, API `7.1.0`, protocol 15 — **all preserved on purpose**.
* Upstream pin: `merge-1.7.10` @ `b4abe2f26bd1da4ce86ba5323711b2b50de84930`, core @ `791070ae3647c1791b057f6269431434369da128`
  (`MIRAI-UPSTREAM.json`).
* **Build in use** (owner's client): `0.1.19-dev` "disabled-renderer quiescence", commit
  `afef9d7e4f085c0d1dccba66d763db22878714ca`, jar SHA-256 `481d6f7b…986ea` (tag `v0.1.19-dev`). Status: *development; runtime
  certification pending*. Owner: Gabriel (GitHub `Akashiic`, "Rede Akashic"). **Talk to the owner in Portuguese (pt-BR)**;
  code, identifiers and commit messages are English; the original docs under `docs/` are Portuguese.

## Repository layout

| Path | Contents |
|---|---|
| `coreSubProjects/{core,api}` | Modified DH core, **flattened into this repo** (see below) |
| `common/`, `forge17/` | Renderer (LWJGL2 GL backend, shaders) and Forge 1.7.10 loader; `forge17` is the shipped target |
| `buildSrc/`, `gradle/`, `versionProperties/` | Multi-version Gradle machinery; **always select `-PmcVer=1.7.10`** |
| `tests/`, `testScripts/` | Headless harnesses (Angelica ABI audit, fog colour, fastutil collision, storage lifecycle, …) |
| `scripts/` | `verify-mirai-jar.py`, `replay-mirai-tests.py` (Java 8/21 replay), `collect-mirai-logs.ps1`, `analyze-mirai-gpu.py`, `summarize-mirai-sessions.py` |
| `docs/` | Per-version `CHANGELOG-*`, `PLANO-E-DIAGNOSTICO-*`, audits, **plus** `INDEX`, `PROJECT-STATE`, `TIMELINE`, `HOMOLOGATION-0.1.19`, `RELATED-PROJECTS`, `DECISIONS-AND-REJECTIONS`, `BUILD-AND-RELEASE`, `COMPATIBILITY-STACK`, `ISSUES-BACKLOG`, `LICENSING-AND-ATTRIBUTION`, `LOCAL-MACHINE-MAP` |
| `docs/history/` | Delivery reports/manifests/SHA-256 sums per release; 0.1.7 audit inventory |
| `evidence/` | Sanitised runtime summaries and artifact/test verification |
| `companions/` | Server-side Forge patches that fix DH × other-mod problems on the Crucible backend |

## Git topology — read this

* History here is **real and linear from 0.1.7**: `195f83f` (0.1.7 sources restored from the supplied ZIP) → `fc3cab1` (0.1.8)
  → … → `a77e157` (0.1.18) → `69645ac` (0.1.19 *diagnostic*) → `afef9d7` (0.1.19 *quiescence*). Annotated tags `v0.1.N-dev`.
  History **before 0.1.7 is lost/flattened** (hashes of 0.1.0–0.1.7 survive in `MIRAI-UPSTREAM.json`).
* `main` = `akashic/mirai-0.1.19-disabled-quiescence` (superset of the diagnostic build) + handoff-docs commits on top.
* `akashic/mirai-0.1.19-perf-lab` = diagnostic build (`69645ac`). `akashic/mirai-gpu-lab` = **rejected GPU experiments** (shared
  IBO bind probe, SSAO RAW variants) — evidence only, never a base.
* `coreSubProjects` is a **normal directory** (the original `.gitmodules` entry is vestigial). **Never run
  `git submodule update`** on it: it would replace the modified core with upstream.
* The two 0.1.19 builds share a jar file name. Identify by SHA-256 / `build_info.json` (`info_git_commit`).

## Build / verify (Windows host)

```text
Gradle 9.4.1 wrapper, daemon toolchain JDK 25; Java 8 + 21 must be discoverable; Python >= 3.11 for scripts.
gradlew.bat "-PmcVer=1.7.10" --max-workers=4 --no-parallel build
gradlew.bat "-PmcVer=1.7.10" --max-workers=4 --no-parallel :core:test :api:test      # ~1m16s warm
python scripts/verify-mirai-jar.py <jar> --expected-version V --expected-commit SHA --expected-branch BR
```

* **PowerShell gotcha**: unquoted `-PmcVer=1.7.10` is split at the dot and Gradle silently builds the default **1.20.1** target.
  Quote it: `"-PmcVer=1.7.10"`. Prefer a clean `git worktree` at the commit so `build/` and `build.properties` do not leak.
* Ship **only** `:forge17:shadeDowngradedApi`. Intermediate `-all`, `-dev`, `-sources` jars are not releases.
* Offline gates are *necessary, not sufficient*: they never start Minecraft, Crucible or OpenGL.
  See `docs/BUILD-AND-RELEASE.md` for the full gate list (ABI audit vs real Angelica/GTNHLib jars, Java 8/21 replay, shader
  byte-identity, identity test, jar contract).

## Non-negotiable engineering rules (learned the hard way)

1. **Visual fidelity contract.** Do not change shaders, SSAO (`sampleCount=6`, `radius=4.0`, `strength=0.2`, `minLight=0.25`,
   `bias=0.02`, 5×5 bilateral blur), AA, fog, fade, LOD distance, resolution, transparency or emitted geometry as a
   performance strategy. A "win" that costs pop-in, LOD delay, 1% lows, input latency, sustained memory or fidelity is a regression.
2. **Evidence over claims.** Separate *technically gated* from *accepted on the real pack*. **No FPS claim without an A/B** in the same
   world/camera/config. Say what ran with real dependencies vs doubles vs not at all.
3. **Timing semantics.** Every `*_WALL` metric is elapsed wall time incl. waits; nested metrics overlap — **never add** parent +
   children; `p95_bucket_ms` is a histogram bucket ceiling; `GL_*_UPLOAD_BYTES` is attempted payload, not VRAM; GPU stage times
   are async `GL_TIMESTAMP` completion intervals, not GPU busy time.
4. **Prove equivalence before optimising.** Bit-exact tests (`Float.floatToIntBits`), same ordering/FIFO, same comparator. Reject
   variants that can change iteration order (a reused `HashSet` was rejected for exactly this reason).
5. **Ownership and lifecycle.** Release in `finally`; closes are idempotent; late completions must not publish into a closed tree or
   the next world; never remove a backpressure `join()` without a byte-bounded replacement.
6. **Identity sync.** `gradle.properties mod_version`, `ModInfo.VERSION`, jar name, `build_info.json`, `mcmod.info`, changelog and
   `MIRAI-UPSTREAM.json` must agree — `:api:verifyReleaseVersion` + `verify-mirai-jar.py` enforce part of it.
7. **One Distant Horizons jar only.** Never install AOTM beside another DH/Mirai jar; never re-enable upstream self-update.
8. **No Voxy code.** Voxy is "All rights reserved". Study ideas, write original code. Keep upstream notices/LGPL files intact.
9. **Lab code stays in lab branches/worktrees** (probes, auto-connect, gate telemetry). Release source stays clean.
10. **Never touch the owner's pack unless asked.** Back up the current jar outside `mods`; never leave two Mirai jars in `mods`.
11. **Privacy.** Mirai logs never contain chat/credentials/JVM args/address/player/world name, but they list installed mods and
    may contain encoded section positions. Commit only sanitised summaries (`scripts/summarize-mirai-sessions.py`).
12. **Rollback switches** (JVM props): `-Dakashic.mirai.disabledRendererQuiescence=false`, `-Dakashic.mirai.runtimeGovernor=false`,
    `-Dakashic.mirai.diagnostics=false`; opt-ins: `-Dakashic.mirai.renderTaskStacks=true`, `-Dakashic.mirai.experimentalServerGeneration=true`
    (dedicated server; distant generation is OFF by default).

## Do NOT resume from

* `akashic/mirai-gpu-lab` experiments (SSAO RAW loop/specialized/recurrence variants changed pixels; shared IBO neutral on GPU).
* Anything that removes the upload `join()`, reuses the requeue `HashSet`, or adds Voxy-style GPU culling/persistent arenas
  without a new design + A/B (see `docs/DECISIONS-AND-REJECTIONS.md`).
* The pre-0.1.7 history (flattened; only hashes remain).

## Where to start

1. `docs/PROJECT-STATE.md` → homologation matrix, known issues, risks.
2. Pinned GitHub Issues: master tracker, runtime homologation of 0.1.19, open defects derived from real logs.
3. Typical next work: run **Gate A/B/C** from `docs/HOMOLOGATION-0.1.19.md` §9 (DISABLED quiescence → reactivation → renderer-ACTIVE
   GPU capture) and archive the logs; investigate issue "biome wrapper assert on re-enable" and "duplicate/excessive LOD transfer";
   then decide `0.1.20` scope from evidence, not from taste.
4. Local machine map (paths on the owner's PC, releases, companions, audits): `docs/LOCAL-MACHINE-MAP.md`.
