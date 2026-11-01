# Mirai — LEIAME

**Mirai** (antes *Akashic: Horizons of The Mirai*, AOTM) é o fork de estabilização do **Distant Horizons** para **Minecraft
1.7.10** (Forge 10.13.4.1614, **LWJGL2**, bytecode Java 8), feito pela Rede Akashic para o modpack **Forbidden Series** e para um
backend **Crucible / Java 21**. É o backport, para a 1.7.10, da *proposta* do Distant Horizons + Voxy: desenhar terreno distante
simplificado (LODs) sem abrir mão da fidelidade visual do pack.

**Status: `1.0-BETA` — gates offline verdes, certificação runtime pendente.** Nada da 1.0-BETA rodou ainda em Minecraft, Crucible
ou GPU real. Leia [docs/PROJECT-STATE.md](docs/PROJECT-STATE.md) e [docs/CHANGELOG-1.0-BETA.md](docs/CHANGELOG-1.0-BETA.md).

## Linha de release atual

| Campo | Valor |
|---|---|
| Versão | `1.0-BETA` (build de release: `ModInfo.IS_DEV_BUILD=false`) |
| JAR | `Mirai-forge17-1.0-BETA-1.7.10.jar` — reprodutível: o SHA-256 de cada commit sai no CI (`jar.sha256`) |
| Coremod | `com.seibel.distanthorizons.DistantHorizonsTweaker` (a classe que o Angelica procura) |
| Base upstream | DH `3.2.1-b-dev` + correções do upstream portadas à mão (lista em `MIRAI-UPSTREAM.json`) |
| Preservados | modid `distanthorizons`, `DistantHorizons.toml`, API `7.1.0`, protocolo `15` |

> A build **instalada** no Technic continua sendo a `0.1.19-dev` quiescence (`481d6f7b…`) até você trocar. Ao trocar: backup do
> jar atual fora de `mods` e **um único** jar DH/Mirai na pasta. Histórico das 0.1.19 em [docs/HOMOLOGATION-0.1.19.md](docs/HOMOLOGATION-0.1.19.md).

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
