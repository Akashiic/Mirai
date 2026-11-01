# Mirai

> **Mirai** (formerly *Akashic: Horizons of The Mirai*, AOTM) is a stabilization fork of **Distant Horizons** for
> **Minecraft 1.7.10** (Forge 10.13.4.1614, **LWJGL 2**, Java 8 bytecode), built for the *Forbidden Series* modpack and a
> **Crucible / Java 21** backend, maintained by Rede Akashic. A 1.7.10 backport of the *proposal* behind Distant Horizons and
> Voxy: render a simplified, far-reaching LOD terrain without giving up the modpack's visual fidelity.

**Status: `1.0-BETA` — offline gates green, runtime certification pending.** Nothing in 1.0-BETA has run in Minecraft, Crucible
or on a real GPU yet; read [docs/PROJECT-STATE.md](docs/PROJECT-STATE.md) and [docs/CHANGELOG-1.0-BETA.md](docs/CHANGELOG-1.0-BETA.md)
before claiming anything beyond that. Portuguese (pt-BR) version: [LEIAME.md](LEIAME.md).

## Current release line

| Field | Value |
|---|---|
| Version | `1.0-BETA` (release build: `ModInfo.IS_DEV_BUILD=false`) |
| Artifact | `Mirai-forge17-1.0-BETA-1.7.10.jar` — reproducible: its SHA-256 is printed by CI (`jar.sha256`) for each commit |
| Coremod | `com.seibel.distanthorizons.DistantHorizonsTweaker` (the class Angelica probes) |
| Upstream base | Distant Horizons `3.2.1-b-dev` (`merge-1.7.10` @ `b4abe2f2…`, core @ `791070ae…`) **plus** hand-ported upstream fixes listed in `MIRAI-UPSTREAM.json` |
| Preserved | mod id `distanthorizons`, `DistantHorizons.toml`, API `7.1.0`, network protocol `15` |
| Target | MC 1.7.10 · Forge 10.13.4.1614 · LWJGL 2.9.1 · class major ≤ 52 (via JvmDowngrader 1.3.6) |
| Gates | core + api 362 tests · jar contract · ABI vs real Angelica 2.2.20/2.2.43, GTNHLib, Hodgepodge · Angelica detection · GLSM call allowlist |

The previous builds (`0.1.19-dev` quiescence `481d6f7b…`, diagnostic `04b35d24…`) are documented in
[docs/HOMOLOGATION-0.1.19.md](docs/HOMOLOGATION-0.1.19.md) and `MIRAI-UPSTREAM.json`.

## What Mirai changes relative to upstream

* **Stability first**: resource ownership, bounded transport, session/world-correct tasks, side-safe lifecycle, recovery paths.
* **Zero wasted work**: scheduler starvation/lost-wakeup fixes, runtime governor, staging reuse, a hard **DISABLED** renderer
  opt-out (no render builds, no GL uploads, no chunk→LOD pipeline while off; bounded catch-up when re-enabled).
* **Visual fidelity contract**: no change to shaders, SSAO, AA, fog, distance, resolution or geometry unless explicitly stated.
* **Observability**: `logs/akashic-mirai/*.log` (JSON-lines, schema 2), `/miraidiag status|trace|gpu|mark`, opt-in async GPU
  timers (`GL_TIMESTAMP`), `gpu_terrain` telemetry, world-lifecycle and thread-pool setup timings.
* **Integration**: Angelica (ABI-audited), GTNHLib, Hodgepodge, UniMixins; private relocation of fastutil/JOML.

## Repository map

| Path | What it is |
|---|---|
| `coreSubProjects/` | **Flattened** (modified) DH core — *do not* run `git submodule update` on it |
| `common/`, `forge17/`, … | Loader/renderer code; `forge17` is the shipped target |
| `docs/` | Original Portuguese engineering docs (per-version changelogs, plans, audits) **plus** the handoff set — start at [docs/INDEX.md](docs/INDEX.md) |
| `docs/history/` | Delivery reports, manifests and SHA-256 sums of past releases, 0.1.7 audit inventory |
| `evidence/` | Sanitised runtime evidence and artifact/test verification (2026-10-03/04) |
| `companions/` | Server-side companion patches (EndlessIDs compat, rejected-logout guard) |
| `scripts/` | Jar verifier, log collector/analyzers (`summarize-mirai-sessions.py`), replay harness |
| `tests/`, `testScripts/` | Headless harnesses (ABI audit, fog colour, fastutil collision, storage lifecycle, …) |
| `CLAUDE.md` | Contract and context for AI agents continuing this work |

## Branches and tags

`main` = `akashic/mirai-0.1.19-disabled-quiescence` + the handoff docs. Every release has an annotated tag `v0.1.N-dev`
(`v0.1.7-dev-restored` … `v0.1.19-dev`, plus `v0.1.19-dev-diagnostic`). `akashic/mirai-*` branches keep the real, linear
history 0.1.7 → 0.1.19 (history before 0.1.7 was lost and flattened — see [docs/TIMELINE.md](docs/TIMELINE.md)).
`akashic/mirai-gpu-lab` holds rejected GPU experiments (kept as evidence, not a base).

## Build

```text
JDK 25 for the Gradle daemon (toolchain), JDK 8 + 21 discoverable, Python 3.11+ for the verifier.
gradlew.bat "-PmcVer=1.7.10" build        # PowerShell: QUOTE the -P argument (see docs/BUILD-AND-RELEASE.md)
python scripts/verify-mirai-jar.py forge17/build/libs/Mirai-forge17-1.0-BETA-1.7.10.jar \
       --expected-version 1.0-BETA --expected-commit <sha> --expected-branch <branch>
```

Ship **only** `:forge17:shadeDowngradedApi` output. Never install beside another Distant Horizons jar.

## Licence and attribution

LGPL-3.0 (inherited from Distant Horizons; `LICENSE.txt` / `LICENSE.LESSER.txt` preserved). Upstream authors are credited in
`mcmod.info`. **No Voxy code, shader or asset is incorporated** (Voxy is "All rights reserved"); only general engineering
principles were studied. Details: [docs/LICENSING-AND-ATTRIBUTION.md](docs/LICENSING-AND-ATTRIBUTION.md). Not affiliated with the
Distant Horizons or Crucible maintainers. Original upstream README: [README-UPSTREAM.md](README-UPSTREAM.md).
