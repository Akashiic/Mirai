# Linha do tempo — AOTM 0.1.0 → 0.1.19

Datas de commit para 0.1.8+ (2026, fuso do autor). Para 0.1.0–0.1.7 o histórico git original **não existe mais**: o repositório foi
reconstruído a partir do ZIP publicado da 0.1.7 (`195f83f`, 1.349 arquivos conferidos contra o manifesto interno); os hashes originais
sobrevivem em `MIRAI-UPSTREAM.json`. O primeiro inventário de log do pack é de **2026-09-05** (`docs/inventory-log-20260905.json`), então a 0.1.0 é de
no máximo poucos dias antes. Cada versão tem tag `v0.1.N-dev` neste repositório (exceto 0.1.0–0.1.6, anteriores ao git).

Legenda de status: **RC-teste** = aprovada pelo autor para teste controlado ("approved_for_controlled_runtime_test; stable_pending_runtime").
Nenhuma versão foi certificada para produção.

## Fase 0 — Fundação e endurecimento (0.1.0 → 0.1.7, pré-git)

| Versão | Tema | Mudanças-chave (de `docs/CHANGELOG-*`) | Identidade conhecida |
|---|---|---|---|
| 0.1.0-dev | Fork e contrato | Nome/identidade Akashic, atualizador upstream bloqueado, accessors Forge 1.7.10 antes dos consumidores, posse de chunks por solicitação de geração, transporte com whitelist/limites/remontagem limitada, ciclo de vida de sessão/mundo, padrões conservadores (raio 64, ≤2 workers, razão 0,5, geração distante OFF) | root `20a5a46e`, core `07f970c7`, jar `43f6f070…` |
| 0.1.1-dev | Hotfix de crash | Colisão `it.unimi.dsi.fastutil` 8.2.1 (Mirai) × 8.5.18 (GTNHLib) → `NoSuchMethodError`; relocalização privada da fastutil | root `42f53255`, core `8a8bc319`, jar `a4f9a212…` |
| 0.1.2-dev | Fog × Angelica | `NoSuchMethodError` em `AngelicaAccessor.getFogColor()` por relocalização do JOML; leitura RGB via `FloatBuffer` do estado do Angelica; proteção do `.lang` em `processResources` | — |
| 0.1.3-dev | Estabilização pós-auditoria | 14 achados (INT/S/R/NET/G): rejeição no handler servidor, cache por instância de mundo, callbacks LevelInit presos à sessão, JDBC/migrador V1 cooperativo, restauração de estado GL, quotas idempotentes | — |
| 0.1.4-dev | Composição e cobertura | Composição/AA conscientes de shaderpack ativo, fade GLSL com `smoothstep` crescente, limite de distância por cobertura contígua de chunks, checksum DTO recalculado, hashes confirmados por gravação | raiz `dde9e879`, core `954b57e6` |
| 0.1.5-dev | Rede e preparação | Sync de LODs já armazenados quando o cache cliente está vazio, `DhFrustumBounds` sem alocações, identidade de mundo para chunks pendentes, `/dh prepare loaded` | raiz `5e10599f`, core `a1be0dbc` |
| 0.1.6-dev | Diagnóstico | `logs/akashic-mirai/latest.log` JSONL, `/miraidiag status|mark|trace|stop`, `/dh diagnostics`, contadores/histogramas | raiz `9213d55a`, core `32e66620`, jar `c1d29f3f…` |
| 0.1.7-dev | Nuvens e sync | Nuvens reaproveitam câmera, intenções de sync limitadas (4096), cancelamentos classificados, métricas de upload GL e passe cliente. Nota Forestry/C2 em 8u51 | entregue `d8afab15` / fontes restauradas `2ae4158f` → `195f83f`; jar `b46cac2c…` |

## Fase 1 — Estabilização com git real (0.1.8 → 0.1.19)

| Versão | Data | Commit | Tema | Mudanças-chave | Testes (core+api) | JAR (bytes / SHA-256) | Status |
|---|---|---|---|---|---|---|---|
| 0.1.8-dev | 09-08 | `fc3cab1` | Dados vazios e trabalho GL redundante | Sentinela `EMPTY_DATA_POINT`; 1º upload `BUFFER_STORAGE` reusa o ID GL (256 gens/128 dels → 128/0 em 128 cargas); culling de nuvens sem 12 vetores/chamada; fila de render com 1 callback pendente e orçamento normal | 242+5 | 45.347.821 / `da4e7978…` | RC-teste |
| 0.1.9-dev | 09-08 | `fa91834` (`c5f1026`) | Observação GPU opt-in | `/miraidiag gpu 10–120 s`, pares assíncronos `GL_TIMESTAMP`, 6 slots, estágios (OPAQUE, SSAO, AA, FOG, FAR_FADE…), schema 2 | 278+5 | 45.366.457 / `7bc77b4b…` | RC-teste |
| 0.1.10-dev | 09-08 | `987f3e7` | Câmera e ciclo de vida de buffers | NPE de câmera ausente em handoff; vetor de câmera por chamada; liberação garantida de buffers; seção fechada recusa resultados tardios | n/d | 45.368.860 / `7a5e4a8c…` | RC-teste |
| 0.1.11-dev | 09-08 | `1484fc0` | Fechamento da árvore | Worker termina sem esperar a fila GL ao fechar a árvore; `LOD_RENDER_LIST_WALL`; eventos `world_lifecycle` | n/d | 45.373.618 / `473a4a66…` | RC-teste |
| 0.1.12-dev | 09-09/10 | `a077dd4`, `48b728f` | Scheduler | Starvation do `PriorityTaskPicker`, race de ocupação global, lost wakeup, **runtime governor**, sync-on-load sem crescer a 4096, chunk lifecycle | n/d | não arquivado | RC-teste |
| 0.1.13-dev | 09-12 | `985d0f8` | SSAO sem perda | Pesos Gaussianos espaciais pré-calculados (30→4 avaliações/pixel), igualdade bit a bit no modelo; subestágios `SSAO_RAW`/`SSAO_APPLY` | core 295 | não arquivado | RC-teste |
| 0.1.14-dev | 09-12 | `50dd042` | Alocações no render thread | *Scratch reuse* de matrizes/vetores (SSAO, TAA, fog, terrain, meta, genérico), 3×2.048 iterações bit-exatas | n/d | não arquivado | RC-teste |
| 0.1.15-dev | 09-12 | `e89f350` | Observabilidade | `GLState` por escopo (save/restore), fases de `setupThreadPools`; **nenhum** otimização — custo do `GLState` mostrou ser baixo | n/d | `758bb373…` (via manifesto 0.1.16) | RC-teste |
| 0.1.16-dev | 09-12 | `716ba89` | Forense de shutdown + beacons | Estado por executor e stacks de `DH-*` só no shutdown; race de beacon culling em troca de mundo corrigida | core 308 | 45.396.631 / `bb0748c9…` | RC-teste |
| 0.1.17-dev | 09-14 | `cffe7fd` | Refresh de beacons sem `sleep` | `BeaconRefreshScheduler` (cooldown de 2 s após o fim real, sem thread nova); remove 586–1.077 ms de espera no shutdown | n/d | 45.400.031 / `8196b434…` | RC-teste |
| 0.1.18-dev | 09-17 | `a77e157` | Staging + DISABLED | Reuso de staging (quadtree/beacons); `DISABLED` bloqueia builds/uploads (3 fronteiras); `gpu_terrain`; probes SSAO/IBO só em lab | core 329 | 45.401.331 / `29e549b1…` | RC-teste |
| 0.1.19-dev *diagnostic* | 09-17 | `69645ac` (`66f3353`) | Subfases de `LOD_UPDATE_WALL` | Trace-only: `LOCK_WAIT, EXISTS_CHECK, LOAD, MERGE, SKYLIGHT, ENCODE, DB_SAVE, NOTIFY` | — | 45.402.547 / `04b35d24…` | Diagnóstica |
| **0.1.19-dev *quiescence*** | 09-17 | **`afef9d7`** | Quiescência com renderer DISABLED | Ver `docs/PROJECT-STATE.md` §3 e `docs/history/releases/0.1.19-dev/PATCH-REPORT.md` | **core 335 + api 5** | **45.408.316 / `481d6f7b…`** | **Em uso; RC-teste** |

Observações:

* As versões 0.1.12–0.1.15 não têm pasta de release arquivada neste PC (só os commits/branches); o SHA do JAR 0.1.15 aparece no manifesto da 0.1.16.
* A 0.1.19 *quiescence* contém a *diagnostic* (é seu descendente direto).
* A branch `akashic/mirai-gpu-lab` (a partir de `a8d5ac0`/0.1.18) guarda os experimentos **rejeitados** (probe de IBO compartilhado, três variantes de SSAO RAW).

## Marcos de evidência real (não confundir com gates offline)

| Data | Marco | Onde |
|---|---|---|
| 2026-09-05 | Inventário do log do pack | `docs/inventory-log-20260905.json` |
| 2026-09-08 | Auditoria independente do snapshot 0.1.10 (602 blocos de 5 s) | `docs/ANALISE-LOGS-0.1.10.md` |
| 2026-09-12 | Rodada 0.1.11/0.1.12: GC/background sob controle, perfil gráfico GPU-bound (SSAO ≈ 24 ms numa captura sobreposta) | `docs/CHANGELOG-0.1.13-dev.md` |
| ≈2026-09-14/17 | Sessões 0.1.16 com `DISABLED`: 1.551 snapshots `VBO 0/0` mas 18.865 uploads concluídos → motivou 0.1.18/0.1.19 | `docs/CHANGELOG-0.1.18-dev.md` |
| 2026-10-03 | Sessões 0.1.19 quiescence (≈2 h + ≈23 min), 1 ciclo de reativação | `docs/RUNTIME-EVIDENCE-2026-10-03.md` |
| 2026-10-04 | Verificação independente do artefato + re-execução dos testes | `docs/HOMOLOGATION-0.1.19.md` |
