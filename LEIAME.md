# Akashic: Horizons of The Mirai (AOTM) — LEIAME

Fork de estabilização do **Distant Horizons** para **Minecraft 1.7.10** (Forge 10.13.4.1614, **LWJGL2**, bytecode Java 8),
feito para o modpack **Forbidden Series** e homologado contra um backend **Crucible / Java 21**. É o backport, para a 1.7.10,
da *proposta* do Distant Horizons + Voxy: desenhar terreno distante simplificado (LODs) sem abrir mão da fidelidade visual do pack.

**Status: `development; runtime certification pending`.** A linha está visivelmente funcional no cliente do dono do projeto e os
gates offline estão verdes, mas a certificação runtime sustentada (renderer ATIVO, captura GPU, dedicado Crucible) **ainda não
está registrada**. Leia [docs/PROJECT-STATE.md](docs/PROJECT-STATE.md) e [docs/HOMOLOGATION-0.1.19.md](docs/HOMOLOGATION-0.1.19.md).

## Build em uso (verificada em 2026-10-04)

| Campo | Valor |
|---|---|
| Versão | `0.1.19-dev` — "disabled-renderer quiescence" (tag `v0.1.19-dev`) |
| Commit / branch | `afef9d7e4f085c0d1dccba66d763db22878714ca` / `akashic/mirai-0.1.19-disabled-quiescence` |
| JAR | `Akashic-Horizons-of-The-Mirai-forge17-0.1.19-dev-1.7.10.jar` — 45.408.316 bytes |
| SHA-256 | `481d6f7b0f175465b00b278c37837cf65d7af90b3ba6d065c65819bf647986ea` |
| Instalado em | `…\.technic\modpacks\forbidden-series\mods\` (idêntico byte a byte ao da pasta de release e ao build do lab) |
| Base upstream | DH `3.2.1-b-dev`, API `7.1.0`, modid `distanthorizons` preservado |

> Existe **outra** build `0.1.19-dev` (a **diagnóstica**, commit `69645ac`, SHA-256 `04b35d24…`) com o **mesmo nome de arquivo**.
> Sempre confira o SHA-256. O que está no seu Technic é a **quiescence** (`481d6f7b…`).

## O que ela faz de diferente

* **Estabilidade antes de tudo**: posse de recursos, transporte limitado, tarefas presas à sessão/mundo certos, ciclo de vida seguro por lado.
* **Zero trabalho desperdiçado**: correções de starvation/lost-wakeup do scheduler, *runtime governor*, reutilização de staging,
  e um opt-out real do renderer (**DISABLED** não constrói geometria, não faz upload GL e não ingere chunk→LOD; ao reativar há
  *catch-up* limitado a 1 chunk residente a cada 5 ticks, sem forçar load/worldgen).
* **Contrato de fidelidade visual**: nada de shader, SSAO, AA, fog, distância, resolução ou geometria muda sem estar explícito.
* **Observabilidade**: `logs/akashic-mirai/*.log` (JSONL schema 2), `/miraidiag status|trace|gpu|mark`, timers GPU assíncronos
  opt-in, telemetria `gpu_terrain`, tempos de ciclo de vida de mundo e de setup de pools.

## Como continuar daqui (Claude Code ou humano)

1. Leia `CLAUDE.md` (contrato de trabalho) e `docs/PROJECT-STATE.md` (estado, matriz de homologação, riscos).
2. Veja os **Issues fixados** no GitHub: tracker mestre, homologação runtime 0.1.19 e defeitos abertos derivados dos logs.
3. Rode o ciclo de homologação de `docs/HOMOLOGATION-0.1.19.md` §9 (Gates A/B/C) e **arquive os logs de cada rodada** em
   `evidence/` — o logger só retém ~20 MiB (5 segmentos) e as rodadas antigas são sobrescritas.
4. Para qualquer mudança: branch a partir de `main`, bump coerente de versão (gradle.properties, `ModInfo.VERSION`, nome do JAR,
   `build_info.json`), gates offline, só então pedir teste no pack real. **Nunca** afirmar ganho de FPS sem A/B no mesmo cenário.

## Mapa do repositório

| Caminho | Conteúdo |
|---|---|
| `coreSubProjects/` | Core do DH **achatado e modificado** — *não* rode `git submodule update` nele |
| `common/`, `forge17/` | Renderer e loader; `forge17` é o alvo distribuído |
| `docs/` | Docs de engenharia originais + conjunto de handoff — comece em [docs/INDEX.md](docs/INDEX.md) |
| `docs/history/` | Relatórios de entrega, manifestos e SHA-256 de releases passadas, inventário da auditoria 0.1.7 |
| `evidence/` | Evidências sanitizadas de runtime e verificação de artefato/testes (2026-10-03/04) |
| `companions/` | Patches server-side (EndlessIDs compat, guard de logout rejeitado) |
| `scripts/` | Verificador de JAR, coletor/analisadores de log (`summarize-mirai-sessions.py`), replay de testes |
| `tests/`, `testScripts/` | Harnesses headless (ABI, cor de fog, colisão fastutil, ciclo de vida de storage, …) |

## Licença e atribuição

LGPL-3.0 (herdada do Distant Horizons). Autores upstream creditados. **Nenhum código/shader/asset do Voxy foi incorporado**
(licença "All rights reserved"). Ver [docs/LICENSING-AND-ATTRIBUTION.md](docs/LICENSING-AND-ATTRIBUTION.md). Sem afiliação oficial
com os mantenedores do Distant Horizons ou do Crucible. README original do upstream: [README-UPSTREAM.md](README-UPSTREAM.md).
