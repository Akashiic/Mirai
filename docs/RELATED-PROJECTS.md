# Projetos relacionados (encontrados no PC do dono e no GitHub)

Varredura autorizada pelo dono em 2026-10-04 (`C:\Users\User\Desktop\Codex`, `AppData\Roaming\.technic`, `Temp`, GitHub `Akashiic`).
**Legenda de confiança**: *lido* = abri e li o conteúdo; *inferido* = relação deduzida do nome/data/arquivos, conteúdo **não** analisado.
Caminhos completos e política de limpeza: [LOCAL-MACHINE-MAP.md](LOCAL-MACHINE-MAP.md).

## 1. Parte direta do AOTM (lido)

| Projeto | O que é | Onde |
|---|---|---|
| `akashic-mirai` | Repositório de trabalho original (branch/tag de todas as versões); fonte deste repositório | local |
| `akashic-mirai-disabled-quiescence-lab` | Worktree da **0.1.19 quiescence** (`afef9d7`) — a build em uso | local → `main` aqui |
| `akashic-mirai-perf-lab` | Worktree da 0.1.19 *diagnostic* (`69645ac`) | local → `akashic/mirai-0.1.19-perf-lab` |
| `akashic-mirai-gpt-0.1.17`, `…-0.1.18` | Worktrees de checkpoints 0.1.17/0.1.18 | local → branches/tags |
| `akashic-mirai-gpu-lab` | Experimentos GPU **rejeitados** (IBO compartilhado, SSAO RAW) | local → `akashic/mirai-gpu-lab` |
| `Akashic-Mirai-0.1.17/0.1.18-dev-release`, `…0.1.19-dev-diagnostic`, `…0.1.19-dev-disabled-quiescence` | Pastas de entrega (JAR, fontes ZIP, manifestos, evidências) | local → `docs/history/releases/`; binários nas Releases |
| `Akashic-Mirai-0.1.16-dev-release.zip`, `…0.1.17-dev-release.zip` | Entregas arquivadas | local (ZIPs) |
| `akashic-audit`, `-0.1.9`, `-0.1.10`, `-0.1.11` | Auditorias independentes + evidências + clones de referência (DH upstream e **Voxy**) | local → resumo em `docs/history/audit-0.1.7/` |
| `entregas` | Entregas 0.1.8–0.1.11 e *World Firewall 0.1.0-alpha.1* | local → `docs/history/releases/` |
| `Akashic-DH-EndlessIDs-Compat` | Companion de servidor (biomas estendidos) | local → `companions/` |
| `Akashic-DH-Rejected-Logout-Guard` | Companion de servidor (conexão rejeitada pelo FML derrubava o listener de logout do DH) | local → `companions/` |

## 2. Mesmo ecossistema (Forbidden Series / Rede Akashic) — relacionados por interação

Interagem com o AOTM no runtime do pack ou nas mesmas ferramentas de diagnóstico, mas **não fazem parte** deste repositório.

| Projeto | Relação | Confiança |
|---|---|---|
| `Akashic-ThePatcher-1.1.0-beta.4 … beta.34` (≈ 40 pastas, 23–25/09/2026) | Coremod/patcher do pack (a beta.34 aparece na lista de mods do log do Mirai). Beta.30–.32 tratam atlas/startup/hotspot; `beta.23-VertexCompatIsolation` e `beta.24-TerrainStagingFallback` tocam compatibilidade de vértices/terreno | inferido (nomes) + log |
| `Akashic-Hodgepodge-AsyncCompat*`, `Akashic-StartupLifecycleProfiler`, `Akashic-AtlasUploadBatcher`, `startup-atlas-audit` | Compatibilidade async do Hodgepodge e perfil de startup/atlas (relevante para AOTM-05: Hodgepodge 2.7.189 no pack) | inferido |
| `Angelica-2.2.8-vs-2.2.18-Audit`, `Angelica-2.2.18-TerrainFilter-Isolation`, `Angelica-VAO-AB-20260924`, `angelica-vao-*`, `Angelica-Translucent-World-Investigation`, `Akashic-TerrainBlendAlphaProbe`, `…TerrainSemanticProbe`, `…WorldRenderStateProbe`, `…TerrainStagingFallback` | Investigações do Angelica (cache GL, translucência, estado de render do mundo) — **base útil para AOTM-05** (ABI/regressão com Angelica 2.2.x) | inferido |
| `forestry-c2-runtime-test`, `hotspot-*`, `java8-compiler-disable-test`, `forestry-versionjson-packfix` | Quirk do compilador C2 (Forestry `Genome.getChromosomes`) em Java 8u51 — mesma questão citada em `COMPATIBILITY-STACK.md` §3 | inferido |
| `Akashic-DisconnectFix`, `Akashic-Praesidium-LogicalSide-Guard`, `Akashic-ChromatiFixes-Server-Side-Guard`, `Akashic-DragonAPI-Crucible-ChunkGeneration-Fix`, `Akashic-Enderman-Frame-Fix`, `Akashic-SplashThreadCompat`, `AkashicBackpackCompat`, `Akashic-ChromaSoundHashFix` | Patches de compatibilidade/servidor do pack (Crucible) | inferido |
| `akashic-world-firewall` | Proteção de mundo (entregue como *World Firewall 0.1.0-alpha.1* em `entregas`) | inferido |
| `akashic-index`, `akashic-index-checkpoints`, `akashic-glass-backport`, `immersiveengineering-backport`, `immersivepetroleum-backport`, `skript-reflect-dev33-backport`, `Mekanism-CE-9.10.48-AkashicPerf` | Backports/índices/perf de outros mods | inferido |
| `Forbidden-Production-0.20.0`, `Forbidden-Santuario-R03`, `Allthemodium-Legacy-Forbidden-alpha1`, `Reliquary-Forbidden-Reincarnation-alpha1`, `the-end-of-forbidden-spawn`, `The_End_of_Forbidden_Documentacao_Completa`, `fa-7.3.2.1-forbidden-build` | Conteúdo/produção do pack (Forbidden Series / *The End of Forbidden*) | inferido |
| `forbidden-mobile-launcher`, `forbidden-mobile-engineering`, `ForbiddenMobile-A57-Diagnostics-2026-09-19` | Launcher Android ARM64 (1.7.10 + Forge) | inferido (+ repo GitHub) |
| `Akashic Voice`, `AkashicFM`, `Akashic-Player-Backport-GitHub` | Voz espacial, rádio, backport de animações de jogador 1.21.1→1.7.10 (o AOTM não depende deles) | inferido (+ repos GitHub) |

## 3. Repositórios no GitHub (`Akashiic`, consultados via `gh`)

| Repositório | Visib. | Relação |
|---|---|---|
| **`Akashic-Horizons-of-The-Mirai`** | privado | **Este** repositório (criado em 2026-10-04) |
| `CubicChunks1710` | público | Fork Akashic de compatibilidade Crucible — **dono do crash AOTM-14** |
| `Akashic-CubicChunks-CrucibleCompat` | público | Compat Crucible do CubicChunks |
| `Technic-Forbidden` | público | Download do modpack (Technic) onde o JAR é instalado |
| `Akashic-Player-Backport`, `Akashic-Voice`, `Forbidden-Mobile-Launcher` | privados | Projetos irmãos com o mesmo padrão de handoff (`README`, `CLAUDE.md`, `docs/`) |
| `AkashicFM`, `Akashiic` (perfil), `All-The-Mods-Brasil`, `2021-forbiddenseries-website-v4`, `Forbidden-Series---Website`, `akashiic-solder` | misto | Fora do escopo técnico do AOTM |

## 4. Upstream e referências (não vendorizados)

* Distant Horizons: `gitlab.com/distant-horizons-team/distant-horizons` (`merge-1.7.10` @ `b4abe2f2…`), core `gitlab.com/jeseibel/distant-horizons-core` @ `791070ae…`.
* Voxy (referência conceitual apenas — **sem código**): clone local em `akashic-audit\upstream\voxy`.
* Angelica / GTNHLib / Hodgepodge / CoreTweaks / EntityCulling (GTNewHorizons e tr7zw): tags e commits citados em `COMPATIBILIDADE-0.1.8.md`.

## 5. Lacunas

* `Akashic-DH-Logout-Lifecycle-Fix-1.0.1-serveronly.jar` (exigido pelo INSTALL do Logout-Guard): **não encontrado** (AOTM-07).
* Sub-projetos do §2 foram classificados só por nome/data; abrir cada um antes de assumir relação causal.
* Raízes `Desktop\MODS`, `REDE AKASHIC` e `ABC`: varredura interrompida por tempo; não analisadas.
