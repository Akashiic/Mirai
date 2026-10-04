# Evidência de runtime — sessões de 2026-10-03 (build 0.1.19-dev quiescence)

Fonte: `logs/akashic-mirai/{previous-4,previous-3,previous-2,previous-1,latest}.log` do pack *forbidden-series* (JSONL, schema 2),
coletados em 2026-10-04 e resumidos por `scripts/summarize-mirai-sessions.py`. Saída arquivada (sanitizada; não contém jogador,
endereço, nome de mundo, chat nem argumentos da JVM — o logger nunca os grava): `evidence/runtime-2026-10-03/sessions-summary.{json,md}`.
**Logs brutos não são versionados** (retêm inventário de mods e posições codificadas de seções).

Leitura obrigatória das métricas (de `docs/DIAGNOSTICO-MIRAI.md`): todo `*_WALL` é tempo de parede com esperas; tempos aninhados se
sobrepõem e **não se somam**; `p95_bucket_ms` é teto de bucket de histograma; `GL_*_UPLOAD_BYTES` é payload tentado; "FPS-equivalente"
abaixo é só `1/FRAME_INTERVAL` médio ponderado — **não é benchmark**.

## 1. Sessões

| | Sessão `fee21e04` | Sessão `2f3a6ba1` |
|---|---|---|
| Janela UTC | 2026-10-03 21:45:31 → 23:46:43 | 2026-10-03 23:48:50 → 2026-10-04 00:11:30 |
| Janela local (UTC−3) | 18:45 → 20:46 | 20:48 → 21:11 |
| Segmentos | previous-4, -3, -2, -1 | latest |
| Identidade | `0.1.19-dev` · `afef9d7e…` · `akashic/mirai-0.1.19-disabled-quiescence` · Forge 10.13.4.1614 | idem |
| Ambiente | Java 1.8.0_51 Oracle, 8 CPUs, heap máx 6 GiB, RAM 15,9 GiB | idem |
| Em mundo / com foco | 1.440 de 1.442 amostras (5 s) / 626 amostras ≈ 52 min | 214 de 217 / 54 amostras ≈ 4,5 min |
| Dimensões | 0 (1.437), 8 (3) | 0 (213), 8 (1) |
| Modo do renderer | `DISABLED` → `DEFAULT` (23:11:46Z) → `DISABLED` (23:12:16Z) | `DISABLED` (todo o tempo) |
| VBO Render Count (máx) | **59 / 147** | 0 / 0 |
| Fim | `normal_shutdown; queued=0 dropped_total=0` | idem |
| Heap pico / GCs | 4,71 GiB / 899 GCs, 63,2 s | 3,56 GiB / 286 GCs, 19,0 s |

Mods relevantes (idênticos nas duas): Angelica **2.2.20**, GTNHLib 0.11.51, Hodgepodge 2.7.189, CoreTweaks 0.3.4.7-GTNH, ArchaicFix 0.8.0,
EntityCulling `@VER@`, UniMixins 0.3.1, EndlessIDs 1.7.4, PlayerAPI 1.5.0, Backhand 1.8.13, Akashic ThePatcher 1.1.0-beta.34.

Config DH efetiva (do log): raio 128; MEDIUM/MEDIUM; resolução BLOCK; transparência COMPLETE; SSAO, AA e DH fog ligados; vanilla fade
DOUBLE_PASS; cave culling ligado; 2 workers × razão 0,5 (numa fase 6 × 1,0 → 1 × 0,5); `distant_generation=false`; `sync_on_load=true` (4096).

## 2. Tempos de parede agregados por sessão

| Métrica | S1 count | S1 média µs | S1 máx µs | S2 count | S2 média µs | S2 máx µs |
|---|---:|---:|---:|---:|---:|---:|
| `FRAME_INTERVAL` | 384.383 | 18.900 | 6.498.409 | 56.568 | 19.854 | 19.749.943 |
| `FRAME_EVENT_SPAN` | 384.384 | 12.887 | 1.766.842 | 56.569 | 13.153 | 9.915.509 |
| `CLIENT_RENDER_PASS_WALL` | 384.217 | 56 | 1.730.302 | 56.351 | 101 | 2.029.360 |
| `RENDER_TASK_QUEUE_WALL` | 384.217 | 0 | 47.918 | 56.351 | 1 | 56.994 |
| `RENDER_TASK_WALL` | 449 | 437 | 47.744 | 1 | 52.524 | 52.524 |
| `LOD_RENDER_WALL` | **393** | 14.834 | 1.608.654 | **0** | — | — |
| `LOD_RENDER_LIST_WALL` | 393 | 369 | 19.089 | 0 | — | — |
| `LOD_UPDATE_WALL` | 760 | 9.751 | 162.571 | 0 | — | — |
| `GL_STORAGE_UPLOAD_WALL` | 8 | 582 | 1.295 | 0 | — | — |
| `GL_DATA_UPLOAD_WALL` | 4 | 5.235 | 17.863 | 0 | — | — |
| `NETWORK_REQUEST_WALL` | 247 | 82.930 | 455.568 | 0 | — | — |

`CLIENT_RENDER_PASS_WALL` conta o passe completo do cliente até com retorno antecipado (renderer desligado): média de 56–101 µs em
`DISABLED` é o custo residual do gancho; os máximos de 1,7–2,0 s vêm da janela de reativação (S1) e de transições/carregamentos (S2).

## 3. Contadores relevantes (soma na sessão; só os ≠ 0)

| Contador | S1 | S2 | Leitura |
|---|---:|---:|---|
| `QUIESCENT_CHUNK_DEFERRED` | 8.339 | 2.457 | Chunks novos suprimidos antes de `ChunkWrapper` (guardados como coordenadas) |
| `QUIESCENT_PARTIAL_UPDATE_SUPPRESSED` | 327 | 128 | Partial LODs do servidor descartados antes do decode |
| `QUIESCENT_REMOTE_SYNC_SUPPRESSED` | 90 | 0 | Sync remoto não admitido |
| `QUIESCENT_CATCHUP_SUBMITTED` | **39** | 0 | Chunks residentes reenviados após reativar (≤1 a cada 5 ticks) |
| `CHUNK_SUBMITTED` | 34 | 0 | Pipeline chunk→LOD efetivamente executado (janela `DEFAULT`) |
| `DB_COMMITTED` | 722 | 0 | Gravações no cache |
| `COLD_REQUEST` / `NETWORK_SENT` / `NETWORK_RECEIVED` | 247 | 0 | Pedidos de LOD ao servidor (cache frio) |
| `NETWORK_FRAGMENT_BYTES` | 5.083.682 | 1.400.612 | Fragmentos de LOD recebidos |
| `GL_DATA_UPLOAD_BYTES` / `GL_STORAGE_UPLOAD_BYTES` | 786.912 / 248.064 | 0 | Payload tentado |
| `DH_ERROR_REPORTS` | 3 | 2 | Ver §5 (S1 = 3 registros de erro; S2 = 1 registro — o contador não é 1:1 com erros únicos) |

## 4. A janela com renderer ativo (única retida)

Seis janelas de 5 s em `fee21e04` (UTC):

| Janela | Frames | Intervalo médio | Frame máx | `LOD_RENDER_WALL` médio / máx | Uploads (storage/data) | VBO |
|---|---:|---:|---:|---:|---|---|
| 23:11:51 | 61 | 109,0 ms | 1.770 ms | 36,2 ms / **1.608,7 ms** | 2 / 1 | 13/25 |
| 23:11:56 | 75 | 67,2 ms | 118 ms | 10,4 ms / 26,9 ms | 0 / 0 | 21/44 |
| 23:12:01 | 84 | 60,2 ms | 99 ms | 10,6 ms / 22,4 ms | 6 / 0 | 31/84 |
| 23:12:06 | 72 | 69,2 ms | 119 ms | 10,6 ms / 26,6 ms | 0 / 0 | 37/99 |
| 23:12:11 | 82 | 61,4 ms | 113 ms | 11,3 ms / 67,8 ms | 0 / 0 | 51/135 |
| 23:12:16 | 190 | 26,6 ms | 103 ms | 12,4 ms / 18,3 ms | 0 / 0 | 59/147 |
| **Total** | **564** | **56,4 ms (≈17,7 FPS-eq.)** | 1.770 ms | — | 8 / 1+ | — |

Nota: os 4 `GL_DATA_UPLOAD_WALL` da sessão não estão todos nas seis janelas — 3 caem no bloco que fecha em 23:11:46.315Z (média 6,1 ms,
máx 17,9 ms), isto é, no próprio instante do toggle para `DEFAULT`; 1 cai na primeira janela. Os 8 `GL_STORAGE_UPLOAD_WALL` estão na
1ª (2) e 3ª (6) janelas. Tarefas GL triviais (`RENDER_TASK_WALL`, p.ex. 223 tarefas de ≈1 µs às 23:42:32Z, ou 1 tarefa de 17–34 ms às 22:26–22:29Z)
também ocorrem em `DISABLED`; não são builds/uploads de terreno.

Interpretação honesta: é a **fase de catch-up** logo após `DISABLED→DEFAULT` (render state reconstruído uma única vez, quadtree
reaprendendo, VBOs sendo repovoados), não um regime estacionário. O custo do passe LOD ficou em ≈10–12 ms de parede por frame com
frames de 60–70 ms — o restante do tempo do frame está fora do passe DH (vanilla, outros mods, GPU/driver numa Intel UHD). Dá para
afirmar que o renderer **voltou a desenhar** progressivamente; **não** dá para afirmar o desempenho sustentado. Referência anterior
(auditoria 0.1.9/0.1.10, renderer ativo, GPU opt-in): PASS GPU médio ≈ 28 ms com SSAO ≈ 7,8 ms e AA ≈ 7,1 ms (`docs/ANALISE-LOGS-0.1.10.md`).

## 5. Erros registrados

| UTC | Sessão | Tipo | Topo da pilha | Observação |
|---|---|---|---|---|
| 2026-10-03T23:11:47.238Z | S1 | `LodUtil$AssertFailureException` (canal `NETWORK` e `DH_LOG`) | `WrapperFactory.getPlainsBiomeWrapper:163` ← `IWrapperFactory.deserializeBiomeWrapperOrGetDefault:59` ← `FullDataPointIdMap.deserialize:309` ← `FullDataSourceV2DTO.readBlobToDataMapping:747` ← `createDataSource:169` ← `AbstractFullDataNetworkRequestQueue.handleNetResponse:327` | ≈1 s após `DEFAULT`. `NETWORK_CANCELLED=1`. Uma única ocorrência |
| 2026-10-03T23:21:52.708Z | S1 | `IllegalArgumentException` | `FullDataPayloadReceiver.receiveChunk:93` ("Duplicate or excessive LOD transfer") | `DISABLED` |
| 2026-10-04T00:00:49.053Z | S2 | `IllegalArgumentException` | idem | `DISABLED` |

Nenhum `VerifyError`, `NoSuchMethodError`, VBO id 0, crash de driver ou erro de upload Java foi registrado.

## 6. Limitações desta evidência

* Só **um** ciclo de reativação de ≈30 s; nenhuma sessão com `DEFAULT` sustentado foi retida (a rotação de ≈20 MiB sobrescreve rodadas antigas).
* Sem captura GPU (`gpu_sample`/`gpu_terrain` ausentes), sem vídeo/screenshots, sem baseline pareado.
* O log vanilla (`logs/latest.log`) não foi analisado aqui (stutter de `Clientside chunk ticking` não comparado).
* Máximos de frame (6,5 s / 19,7 s) não foram atribuídos; com contadores Mirai ≈0 eles não são, a princípio, do Mirai.
