# Estado do projeto — Akashic: Horizons of The Mirai (AOTM)

Snapshot: **2026-10-04**. Este documento é o ponto de retomada: o que existe, o que está provado, o que *não* está, e o que fazer
em seguida. Quando algo aqui contradisser um documento antigo de `docs/`, **este** vale (os antigos descrevem o estado na data
em que foram escritos). Evidência detalhada: [HOMOLOGATION-0.1.19.md](HOMOLOGATION-0.1.19.md) e
[RUNTIME-EVIDENCE-2026-10-03.md](RUNTIME-EVIDENCE-2026-10-03.md).

## 1. Resumo executivo

* O AOTM é um fork de estabilização do Distant Horizons 3.2.1-b-dev para **Minecraft 1.7.10 / Forge 10.13.4.1614 / LWJGL2 /
  Java 8**, com nome público *Akashic: Horizons of The Mirai*. Mantém o modid `distanthorizons` e o `DistantHorizons.toml`.
* A linha **0.1.8 → 0.1.19** (setembro/2026) é toda de estabilização e eliminação de trabalho redundante **sem reduzir qualidade
  visual**, mais um sistema de diagnóstico próprio (`/miraidiag`, logs JSONL, timers GPU opt-in).
* A build **em uso** no cliente do Gabriel é a **0.1.19-dev "disabled-renderer quiescence"** (commit `afef9d7`), instalada em
  `…\.technic\modpacks\forbidden-series\mods\`. O artefato foi verificado byte a byte contra o release e contra o código-fonte.
* Estado de maturidade: **`development; runtime certification pending`**. Gates offline: verdes e re-executados nesta data.
  Evidência runtime retida: sólida para **renderer DISABLED**, **parcial** para **reativação/catch-up**, **ausente** para
  **renderer ATIVO sustentado**, GPU capture, A/B visual e Crucible dedicado.
* O dono reporta que o renderer está "bem visivelmente funcional" — **observação válida e consistente com o catch-up visto nos
  logs (VBO 13/25 → 59/147)**, mas ainda não registrada como evidência (sem vídeo/screenshot/A-B arquivados).

## 2. Identidade do build em uso

| Campo | Valor |
|---|---|
| Versão / tag | `0.1.19-dev` / `v0.1.19-dev` |
| Commit | `afef9d7e4f085c0d1dccba66d763db22878714ca` (`Mirai_0.1.19_disabled_renderer_quiescence`) |
| Branch de origem | `akashic/mirai-0.1.19-disabled-quiescence` |
| JAR | `Akashic-Horizons-of-The-Mirai-forge17-0.1.19-dev-1.7.10.jar`, 45.408.316 bytes |
| SHA-256 do JAR | `481d6f7b0f175465b00b278c37837cf65d7af90b3ba6d065c65819bf647986ea` |
| ZIP de fontes (release) | `Akashic-Mirai-0.1.19-dev-disabled-quiescence-sources.zip`, SHA-256 `67ff126973cce330f76ca6b30d8174dd268bfa48a860b5fefb22e2c5d9e94848` — **1.423 arquivos, 0 diferenças** vs. a árvore git no `afef9d7` |
| Build "irmã" (não instalada) | `0.1.19-dev` *diagnostic*, commit `69645ac…`, SHA-256 `04b35d2484229ca1…` (mesmo nome de arquivo!) |
| Base upstream | DH `3.2.1-b-dev`, API `7.1.0`, protocolo 15 (`MIRAI-UPSTREAM.json`) |

## 3. O que a 0.1.19 contém (acumulado)

Resumo por camada; a cronologia completa está em [TIMELINE.md](TIMELINE.md).

* **Scheduler/threads** (0.1.12): `PriorityTaskPicker` sem starvation e sem *lost wakeup*, ocupação global sem race, *runtime
  governor* (reduz concorrência de background sob pressão de heap/GC/frametime; nunca acima do configurado).
* **Lifecycle de buffers/LODs** (0.1.10–0.1.11): câmera ausente em handoff, ownership de buffers, seções fechadas recusam
  resultados tardios, upload com backpressure e liberação de workers ao fechar a árvore.
* **Render/GPU** (0.1.13–0.1.14): SSAO fase 1 com pesos Gaussianos pré-calculados (bit-exato no modelo), *scratch reuse* de
  matrizes/vetores no render thread.
* **Beacons/shutdown** (0.1.16–0.1.17): forense de `OLD_PICKER_SHUTDOWN`, race de beacon culling em troca de mundo, refresh
  não bloqueante (cooldown de 2 s sem `sleep` no worker).
* **Staging/DISABLED** (0.1.18): reuso de staging do quadtree/beacons; `rendererMode=DISABLED` bloqueia novos builds/uploads
  (admissão, início de worker, submissão GPU); telemetria `gpu_terrain` só durante captura GPU.
* **Quiescência** (0.1.19): com `DISABLED`, nem o pipeline anterior à GPU roda — sem `ClientRenderState`/quadtree no startup,
  sem tick do quadtree, sem eventos chunk→LOD, sem `SyncOnLoad`/sync remoto novo; backlog primitivo ≤ 4096 coordenadas; ao
  reativar, *catch-up* de no máx. 1 chunk residente a cada 5 ticks, com `chunkExists` antes (sem load/ticket/worldgen).
* **Diagnóstico** (0.1.6+): ver `docs/DIAGNOSTICO-MIRAI.md`; 0.1.19 acrescentou subfases de `LOD_UPDATE_WALL` (*trace only*:
  `LOCK_WAIT, EXISTS_CHECK, LOAD, MERGE, SKYLIGHT, ENCODE, DB_SAVE, NOTIFY`).

## 4. Matriz de homologação (0.1.19-dev quiescence)

| # | Gate | Estado | Evidência | Lacuna |
|---|---|---|---|---|
| G1 | Identidade/integridade do artefato | **PASS** | SHA-256 idêntico nas 3 cópias; ZIP íntegro; classes major 46/51/52; `mcmod`/`build_info`/manifesto = 0.1.19-dev/`afef9d7`; `verify-mirai-jar.py` PASS | — |
| G2 | Fonte ≡ binário | **PASS** | ZIP de fontes ≡ `git archive afef9d7` (1.423/1.423); `build_info.json` aponta `afef9d7` | Build não reproduzido byte a byte em outra máquina |
| G3 | Gates offline | **PASS** | Core 335 testes (1 skip) e API 5, **re-executados em worktree limpo** em 2026-10-04; relatório de release: ABI Angelica 2.1.5 e 2.2.8+GTNHLib 0.11.41, 45/45 shaders idênticos | ABI **não** auditada contra Angelica 2.2.20 / GTNHLib 0.11.51 (versões reais em runtime) → Issue |
| G4 | Carrega no cliente real | **PASS** | 2 sessões (≈2 h + ≈23 min) com `identity.version/commit/branch` logados, `normal_shutdown`, `dropped_total=0` | — |
| G5 | DISABLED quiescente (Gate A) | **PASS (evidência boa)** | ≈52 min de gameplay focado em DISABLED: `LOD_RENDER_WALL`/uploads GL = 0, `QUIESCENT_CHUNK_DEFERRED` 8.339+2.457, `QUIESCENT_PARTIAL_UPDATE_SUPPRESSED` 327+128, `QUIESCENT_REMOTE_SYNC_SUPPRESSED` 90 | Sem comparação de stutter vs baseline (`Clientside chunk ticking`) arquivada |
| G6 | Reativação/catch-up (Gate B) | **PARCIAL** | 1 ciclo DISABLED→DEFAULT→DISABLED de ≈30 s: VBO 13/25→59/147, `QUIESCENT_CATCHUP_SUBMITTED=39`, sem crash | Só 1 ciclo; 1º frame com hitch (`LOD_RENDER_WALL` máx 1,6 s); frames médios 56 ms nas janelas ativas; `AssertFailureException` de bioma 1 s após reativar |
| G7 | Renderer ATIVO sustentado + GPU (Gate C) | **NÃO EVIDENCIADO** | — (logs antigos foram sobrescritos pela rotação) | Rodar `/miraidiag gpu 60` com `DEFAULT` parado e em deslocamento |
| G8 | Qualidade visual vs baseline (A/B) | **NÃO REGISTRADO** | Relato do dono ("visivelmente funcional") | Capturas/vídeo no mesmo cenário (0.1.17/0.1.18 vs 0.1.19) |
| G9 | Servidor dedicado Crucible/Java 21 | **PENDENTE** | Companions validados offline contra a build *diagnóstica* | Teste real; revalidar companions contra `481d6f7b…` |
| G10 | Sessão longa, memória/filas estáveis | **PARCIAL** | Heap pico 4,71 GiB de 6 GiB (78 %), 899 GCs/63 s em ≈2 h (≈0,9 % do tempo), filas drenando | Sem série temporal analisada com renderer ATIVO |

## 5. Problemas conhecidos / abertos (espelham os Issues do GitHub)

Detalhes e critérios de aceite em [ISSUES-BACKLOG.md](ISSUES-BACKLOG.md).

1. **Gate C** (renderer ativo + GPU) e **A/B visual** nunca foram registrados para 0.1.19.
2. `AssertFailureException` em `WrapperFactory.getPlainsBiomeWrapper:163` ao receber dados de rede logo após reativar o renderer
   (cadeia: `FullDataPointIdMap.deserialize` → `deserializeBiomeWrapperOrGetDefault`). Hipótese: wrapper/nível ainda não pronto
   ou registro de bioma EndlessIDs. **Não confirmada.**
3. `IllegalArgumentException("Duplicate or excessive LOD transfer")` em `FullDataPayloadReceiver.receiveChunk:93` (2 ocorrências,
   2 sessões, ambas em `DISABLED`). Hipótese: retransmissão/duplicata do servidor; não afeta o estado (a exceção é o guard).
4. **Hitches longos**: `FRAME_INTERVAL` máx 6,5 s (sessão 1) e 19,7 s (sessão 2); com contadores Mirai quiescentes → provável
   carga de mundo/outro mod; precisa de `/miraidiag trace` para atribuir. `Clientside chunk ticking` >100 ms já foi observado.
5. **Pressão de heap**: pico 4,71 GiB de 6 GiB (78 %) na sessão longa; governor ativo (configured=2, effective=2, heap_pct≈74 em
   amostra). Sem evidência de OOM, mas a folga é pequena para o pack com renderer ativo.
6. **ABI/dependências**: auditorias de ABI cobrem Angelica 2.1.5/2.2.8 e GTNHLib 0.11.41; o pack roda **Angelica 2.2.20,
   GTNHLib 0.11.51, Hodgepodge 2.7.189**.
7. **Companions** (EndlessIDs compat, rejected-logout guard) validados contra builds 0.1.16/0.1.17/0.1.19-*diagnostic*; o
   `Akashic-DH-Logout-Lifecycle-Fix-1.0.1` citado no INSTALL **não foi localizado** neste PC.
8. **Retenção de logs**: ~20 MiB; as rodadas de 0.1.16–0.1.18 já foram sobrescritas. Arquivar logs por rodada.
9. **Identidade ambígua**: duas builds `0.1.19-dev` com o mesmo nome de JAR.

## 6. Ambiente de referência (do log real de 2026-10-03)

* **Cliente**: Technic *forbidden-series*; Java **1.8.0_51** (Oracle HotSpot, reporta "Windows 8.1"), 8 processadores lógicos,
  heap máx 6 GiB (`-Xmx6G`), RAM total ≈ 15,9 GiB. GPU Intel UHD (driver 27.20.100.9268, OpenGL 4.6, LWJGL 2.9.1, shaderpack
  inativo — dados do log 0.1.10; reconfirmar).
* **Pilha de mods relevante**: Forge 10.13.4.1614, **Angelica 2.2.20**, **GTNHLib 0.11.51**, **Hodgepodge 2.7.189**,
  CoreTweaks 0.3.4.7-GTNH, ArchaicFix 0.8.0, EntityCulling (`@VER@`), UniMixins 0.3.1, EndlessIDs 1.7.4, PlayerAPI 1.5.0,
  Backhand 1.8.13, Akashic ThePatcher 1.1.0-beta.34.
* **Config DH efetiva**: raio LOD 128, qualidade MEDIUM/MEDIUM, resolução BLOCK, transparência COMPLETE, SSAO e AA ligados,
  DH fog ligado, vanilla fade DOUBLE_PASS, 2 workers / razão 0,5 (pico de 6/1,0 numa fase), `distant_generation=false`,
  `sync_on_load=true` (4096), banda 500.
* **Servidor**: Crucible (Java 21) como backend híbrido; lobby/proxy sem DH (o cliente Mirai entra em destino sem o mod).
* **Quirk de Java 8u51**: o crash do compilador C2 em `forestry.core.genetics.Genome.getChromosomes` exige
  `-XX:CompileCommand=exclude,forestry/core/genetics/Genome.getChromosomes` (documentado desde 0.1.7; o Mirai não injeta isso).

## 7. Dívidas e riscos

| Risco | Impacto | Mitigação |
|---|---|---|
| Sem evidência de renderer ATIVO sustentado | Não dá para certificar a linha | Gate C + arquivar logs/vídeo |
| História pré-0.1.7 perdida | Rastreabilidade parcial | Hashes em `MIRAI-UPSTREAM.json`; ZIPs de release preservados |
| Retenção curta de logs | Perda de evidência | Script `scripts/summarize-mirai-sessions.py` + `evidence/` por rodada |
| Dependências de terceiros evoluem (Angelica 2.2.x) | ABI/regressão visual silenciosa | Re-rodar `tests/angelicaabi` contra os JARs reais do pack |
| Heap 6 GiB apertado | GC/pausas | Governor; medir com renderer ativo; considerar ajustar workers |
| Licença/visibilidade | Distribuição do JAR | Repo privado por padrão; LGPL satisfeita ao entregar fontes com o binário (Issue) |

## 8. Próximos passos recomendados (ordem)

1. **Fechar a homologação 0.1.19**: Gate A repetido + Gate B (2 reativações) + Gate C (GPU parado/deslocamento) + A/B visual;
   arquivar logs em `evidence/runtime-<data>/` com o summarizer.
2. **Investigar os 2 defeitos de log** (bioma na reativação; transferência duplicada) com reprodução controlada.
3. **Re-auditar ABI** contra Angelica 2.2.20 / GTNHLib 0.11.51 / Hodgepodge 2.7.189.
4. **Eliminar a ambiguidade 0.1.19**: próxima build deve ter versão/sufixo distinto (ex.: `0.1.20-dev`).
5. **Decidir o escopo 0.1.20 com dados**: candidatos em [DECISIONS-AND-REJECTIONS.md](DECISIONS-AND-REJECTIONS.md) §3.
6. **Servidor Crucible**: testar dedicado com o mesmo JAR; revalidar companions contra `481d6f7b…`.
