# Akashic: Horizons of The Mirai (AOTM)

> Stabilization fork of **Distant Horizons** for **Minecraft 1.7.10** (Forge 10.13.4.1614, **LWJGL 2**, Java 8 bytecode),
> built for the *Forbidden Series* modpack and validated against a **Crucible / Java 21** backend.
> A 1.7.10 backport of the *proposal* behind Distant Horizons and Voxy: render a simplified, far-reaching LOD terrain
> without giving up the modpack's visual fidelity.

**Status: `development; runtime certification pending`.** The line is visibly working on the owner's client, offline gates
are green, but sustained real-pack certification (renderer ACTIVE, GPU capture, Crucible dedicated) is **not yet recorded**.
Read [docs/PROJECT-STATE.md](docs/PROJECT-STATE.md) and [docs/HOMOLOGATION-0.1.19.md](docs/HOMOLOGATION-0.1.19.md) before
claiming anything beyond that. Portuguese (pt-BR) version: [LEIAME.md](LEIAME.md).

## The build in use

| Field | Value |
|---|---|
| Version | `0.1.19-dev` ("disabled-renderer quiescence") — git tag [`v0.1.19-dev`](../../releases/tag/v0.1.19-dev) |
| Commit / branch | `afef9d7e4f085c0d1dccba66d763db22878714ca` / `akashic/mirai-0.1.19-disabled-quiescence` |
| Artifact | `Akashic-Horizons-of-The-Mirai-forge17-0.1.19-dev-1.7.10.jar` — 45,408,316 bytes |
| SHA-256 | `481d6f7b0f175465b00b278c37837cf65d7af90b3ba6d065c65819bf647986ea` |
| Upstream base | Distant Horizons `3.2.1-b-dev` (`merge-1.7.10` @ `b4abe2f2…`, core @ `791070ae…`), API `7.1.0`, mod id `distanthorizons` preserved |
| Target | MC 1.7.10 · Forge 10.13.4.1614 · LWJGL 2.9.1 · class major ≤ 52 (via JvmDowngrader 1.3.6) |
| Tests (re-executed 2026-10-04) | `core` 335 tests / 0 failures / 0 errors / 1 skip · `api` 5 / 0 / 0 |

> A *second* `0.1.19-dev` build exists (the **diagnostic** build, commit `69645ac`, SHA-256 `04b35d24…`). Both share the
> same jar file name. Always compare the SHA-256 — see [docs/HOMOLOGATION-0.1.19.md](docs/HOMOLOGATION-0.1.19.md).

## What AOTM changes relative to upstream

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
python scripts/verify-mirai-jar.py forge17/build/libs/Akashic-Horizons-of-The-Mirai-forge17-0.1.19-dev-1.7.10.jar \
       --expected-version 0.1.19-dev --expected-commit <sha> --expected-branch <branch>
```

Ship **only** `:forge17:shadeDowngradedApi` output. Never install beside another Distant Horizons jar.

## Licence and attribution

LGPL-3.0 (inherited from Distant Horizons; `LICENSE.txt` / `LICENSE.LESSER.txt` preserved). Upstream authors are credited in
`mcmod.info`. **No Voxy code, shader or asset is incorporated** (Voxy is "All rights reserved"); only general engineering
principles were studied. Details: [docs/LICENSING-AND-ATTRIBUTION.md](docs/LICENSING-AND-ATTRIBUTION.md). Not affiliated with the
Distant Horizons or Crucible maintainers. Original upstream README: [README-UPSTREAM.md](README-UPSTREAM.md).
