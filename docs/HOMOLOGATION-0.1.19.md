# Homologação — Akashic Mirai 0.1.19-dev (disabled-renderer quiescence)

Registro de homologação do build **efetivamente em uso** no cliente do Gabriel. Data da análise: **2026-10-04**. Método: reler
artefatos, fontes e logs reais sem alterar o pack; reexecutar o que é barato e determinístico; separar "o que os relatórios de
release afirmam" de "o que foi conferido de novo aqui"; registrar limites. Este documento **não** certifica produção.

## 1. Veredito

| Nível | Resultado |
|---|---|
| Identidade do artefato e rastreabilidade ao código-fonte | **Aprovado** — verificado de novo |
| Gates offline (testes, ABI, shaders) | **Aprovado** — testes re-executados; demais gates conforme relatório de release |
| Carga e operação no cliente real (Forge 1.7.10, Technic) | **Aprovado para teste controlado** — 2 sessões completas, shutdown normal, 0 descartes de log |
| Quiescência com `rendererMode=DISABLED` | **Aprovado com boa evidência** — contadores de render/upload = 0 durante ≈52 min de gameplay focado |
| Reativação / catch-up `DISABLED→DEFAULT` | **Parcial** — 1 ciclo de ≈30 s, progressivo e sem crash, mas com hitch inicial e 1 erro de bioma |
| Renderer ATIVO sustentado, GPU, A/B visual | **Não evidenciado nos logs retidos** (o dono relata funcionar visivelmente) |
| Servidor dedicado Crucible / Java 21 | **Não testado** para esta build |
| Produção / "stable" | **Não autorizado** (status oficial permanece `development; runtime certification pending`) |

## 2. Artefatos identificados

Todas as cópias abaixo foram lidas e tiveram o SHA-256 recalculado (`evidence/verification-2026-10-04/artifact-and-test-verification.json`).

| Cópia | SHA-256 | `info_git_commit` | Conclusão |
|---|---|---|---|
| Pasta de release `…\Akashic-Mirai-0.1.19-dev-disabled-quiescence\` | `481d6f7b…986ea` | `afef9d7e…` | Release **quiescence** |
| Build do lab `…\akashic-mirai-disabled-quiescence-lab\forge17\build\libs\` | `481d6f7b…986ea` | `afef9d7e…` | Idêntico ao release |
| **Instalado** `…\.technic\modpacks\forbidden-series\mods\` | `481d6f7b…986ea` | `afef9d7e…` | **O que roda no pack = quiescence** |
| Pasta `…\Akashic-Mirai-0.1.19-dev-diagnostic\` | `04b35d24…ed2b45` | `69645ace…` | Build **diagnóstica** (branch `perf-lab`) — outra |
| Build do lab `…\akashic-mirai-perf-lab\forge17\build\libs\` | `04b35d24…ed2b45` | `69645ace…` | Idêntico ao diagnóstico |

Os logs reais confirmam: `identity.version=0.1.19-dev`, `identity.commit=afef9d7e…`, `identity.branch=akashic/mirai-0.1.19-disabled-quiescence`
(sessões `fee21e04` e `2f3a6ba1`). Portanto **o log, o JAR instalado e o código-fonte contam a mesma história**.

Propriedades do JAR quiescence: 45.408.316 bytes; 13.068 entradas; ZIP íntegro (`testzip` = nulo); classes major **46 (111), 51 (197),
52 (12.231)** — nenhuma acima de Java 8; `mcmod.info` = *Akashic: Horizons of The Mirai* `0.1.19-dev` / MC 1.7.10;
manifesto com `FMLCorePlugin`, `FMLAT: 1_7_10.distanthorizons_at.cfg`, `MixinConfigs`, `Lwjgl3ify-Aware: true`,
`JvmDowngrader-Version: 1.3.6`; 22 recursos de shader empacotados.

## 3. Verificações executadas nesta homologação

| ID | Verificação | Método | Resultado |
|---|---|---|---|
| V1 | Cadeia de custódia dos JARs | SHA-256 das 5 cópias (tabela §2) | 3 idênticas (quiescence) + 2 idênticas (diagnostic) |
| V2 | Integridade/formato do JAR | `scripts/verify-mirai-jar.py --expected-version 0.1.19-dev --expected-commit afef9d7e… --expected-branch akashic/mirai-0.1.19-disabled-quiescence` | `checks_passed: true`, 0 falhas |
| V3 | Fonte ≡ release | `git archive afef9d7` vs `Akashic-Mirai-0.1.19-dev-disabled-quiescence-sources.zip`, hash por arquivo | **1.423 = 1.423 arquivos, 0 diferenças** |
| V4 | Estado do git | `git status` no checkout principal e em 5 worktrees (quiescence-lab, perf-lab, gpt-0.1.17, gpt-0.1.18, gpu-lab) | Todos limpos; histórico linear 0.1.7→0.1.19 (21 commits) |
| V5 | Testes unitários | `gradlew "-PmcVer=1.7.10" :core:test :api:test` em **worktree destacado limpo** no `afef9d7` | `BUILD SUCCESSFUL`; core **335** testes/57 suítes (1 skip, 0 falhas, 0 erros); api **5**/2 suítes — igual a `VERIFY.json` |
| V6 | Varredura de segredos | `git grep` por tokens/chaves/senhas nos arquivos rastreados | Nenhum achado; 2 docs citam caminho local de usuário |
| V7 | Telemetria runtime real | `scripts/summarize-mirai-sessions.py` sobre `logs/akashic-mirai/*.log` (5 segmentos) | Ver [RUNTIME-EVIDENCE-2026-10-03.md](RUNTIME-EVIDENCE-2026-10-03.md) |
| V8 | Companions server-side | Leitura de `README`/`VALIDATION`/`SHA256SUMS` | Validados offline contra a build **diagnostic** (`04b35d24…`), não contra a quiescence |

Não foram executados nesta homologação (por exigirem o jogo/servidor ou muito tempo): replay Java 8/21 de todos os testes, ABI audit
contra os JARs do pack, build completo `shadeDowngradedApi`, ensaio com Crucible, captura GPU, comparação visual.

## 4. Gates offline do release (relatório `PATCH-REPORT.md`/`VERIFY.json`, não repetidos aqui)

Core 335 testes / API 5 · `common:compileJava` e `forge17:compileJava` PASS (alvo 1.7.10) · build final PASS incluindo
`downgradeJar` e `shadeDowngradedApi` · integridade ZIP PASS · majors 46/51/52 · identidade empacotada PASS em Java 8u362 e Java 21.0.11 ·
ABI Angelica 2.1.5-dev PASS · ABI Angelica 2.2.8 + GTNHLib 0.11.41 PASS · **45/45 recursos de shader byte-idênticos** à 0.1.19 diagnóstica
· `runtime_gate: false`. Evidência bruta: `docs/history/releases/0.1.19-dev/`.

## 5. Evidência runtime (resumo)

Fonte: 5 segmentos retidos de `logs/akashic-mirai` (todos 0.1.19-dev/`afef9d7`), hora local = UTC−3.

| Sessão | Janela (local) | Em mundo | Modo do renderer | Fim |
|---|---|---|---|---|
| `fee21e04` | 18:45 → 20:46 (≈2 h) | 1.440 de 1.442 amostras (≈52 min com foco no jogo) | `DISABLED` quase todo o tempo; **`DEFAULT` por ≈30 s** (20:11:46 → 20:12:16) | `normal_shutdown`, `dropped_total=0` |
| `2f3a6ba1` | 20:48 → 21:11 (≈23 min) | 214 de 217 amostras (≈4,5 min com foco) | `DISABLED` | `normal_shutdown`, `dropped_total=0` |

Principais números (detalhe e ressalvas no documento de evidência):

* **DISABLED** (maior parte): `LOD_RENDER_WALL` = 0 e **nenhum upload GL de terreno** fora do instante de reativação (os 4 `GL_DATA_UPLOAD_WALL`
  e os 8 `GL_STORAGE_UPLOAD_WALL` da sessão 1 ocorrem entre 23:11:46Z e 23:12:01Z); tarefas GL triviais (`RENDER_TASK_WALL`, µs a poucas dezenas de ms) ainda existem; `QUIESCENT_CHUNK_DEFERRED` 8.339 / 2.457,
  `QUIESCENT_PARTIAL_UPDATE_SUPPRESSED` 327 / 128, `QUIESCENT_REMOTE_SYNC_SUPPRESSED` 90; `CHUNK_SUBMITTED` 34 e `DB_COMMITTED` 722 (ambos só na sessão 1, que teve a janela ativa e mais tráfego de rede).
* **Reativação**: `VBO Render Count` 13/25 → 21/44 → 31/84 → 37/99 → 51/135 → 59/147 em seis janelas de 5 s; `QUIESCENT_CATCHUP_SUBMITTED` = 39;
  `GL_STORAGE_UPLOAD_WALL` 8 chamadas (média 582 µs, máx 1,3 ms) e `GL_DATA_UPLOAD_WALL` 4 (3 no bloco que fecha em 23:11:46Z — o instante do toggle — e 1 na primeira janela ativa; média 5,2 ms, máx 17,9 ms).
* **Custo observado nas janelas ativas**: 564 frames, intervalo médio **56,4 ms (≈17,7 FPS-equivalente)**; primeira janela com
  `LOD_RENDER_WALL` máx **1,61 s** e frame máx **1,77 s**; demais janelas: `LOD_RENDER_WALL` médio ≈10,4–11,3 ms (máx 22–68 ms).
  Isso é **fase de catch-up logo após reativar**, com reconstrução do render state — não é desempenho em regime.
* **Demais janelas (renderer fora do passe)**: intervalo médio 18,9 ms (≈53 FPS-equivalente, inclui menu/alt-tab/carregamentos), pior frame 6,5 s (sessão 1) e 19,7 s (sessão 2).
* **JVM**: heap pico 4,71 GiB de 6 GiB; 899 GCs/63,2 s na sessão 1 (≈0,9 %); 286 GCs/19,0 s na sessão 2.
* **Erros**: 3 relatos `DH_ERROR_REPORTS` — ver §6.

## 6. Achados e anomalias (viraram Issues)

1. **`AssertFailureException` em `WrapperFactory.getPlainsBiomeWrapper:163`** — 2026-10-03T23:11:47Z (≈1 s após `DEFAULT`), thread de rede
   (`AbstractFullDataNetworkRequestQueue.handleNetResponse` → `FullDataSourceV2DTO.createDataSource` → `FullDataPointIdMap.deserialize` →
   `IWrapperFactory.deserializeBiomeWrapperOrGetDefault`). `getPlainsBiomeWrapper` falha quando `BiomeWrapper.deserialize("minecraft:plains")`
   lança `IOException`. Causa raiz **não determinada**; hipóteses: nível/wrapper de bioma ainda não inicializado durante a reativação, ou
   interação com EndlessIDs (estende IDs de bioma). Impacto observado: 1 resposta de rede descartada (`NETWORK_CANCELLED=1`, `errors_NETWORK=1`).
2. **`IllegalArgumentException("Duplicate or excessive LOD transfer")`** em `FullDataPayloadReceiver.receiveChunk:93` — ocorreu em ambas as
   sessões, em `DISABLED`. O código lança quando chega o 1º fragmento de um `bufferId` já existente ou quando `buffersById.size() ≥ maxTransfers`.
   É o guard de segurança funcionando; falta entender **por que** o servidor/cliente gera duplicata (retransmissão após cancelamento? recusa
   de fragmentos suprimidos pela quiescência?).
3. **Hitches longos**: 6,5 s e 19,7 s de `FRAME_INTERVAL`, com contadores do Mirai a zero → não atribuíveis ao Mirai sem `/miraidiag trace`.
4. **Retenção**: apenas ≈20 MiB de log; sessões anteriores (0.1.16–0.1.18) não estão mais disponíveis.
5. **Nomes ambíguos**: duas builds `0.1.19-dev` com o mesmo nome de arquivo.
6. **README/MIRAI-UPSTREAM.json estavam em 0.1.18-dev** no commit `afef9d7` (corrigido no commit de documentação desta consolidação).
7. **ABI**: auditorias não cobrem Angelica 2.2.20 / GTNHLib 0.11.51 / Hodgepodge 2.7.189 (versões reais do pack).

## 7. O que *não* se pode afirmar

* Que o renderer ativo da 0.1.19 está **estável** ou **mais rápido** — não há série retida com `DEFAULT` sustentado nem A/B.
* Que o stutter do modpack foi eliminado — o `PATCH-REPORT.md` já adverte: o aviso `Clientside chunk ticking` pertence ao ciclo do cliente.
* Que os companions server-side funcionam com **esta** build (foram validados contra a diagnostic).
* Que o build é reprodutível byte a byte em outra máquina.

## 8. Por que o relato "bem visivelmente funcional" é plausível

Os logs mostram, no único ciclo de reativação retido, o contador de VBOs elegíveis/desenhados subir de forma monotônica
(13/25 → 59/147), uploads GL concluídos sem falha Java (`GL_UPLOAD_JAVA_FAILURES` não aparece) e nenhum crash/`VerifyError`. Isso é
compatível com LODs reaparecendo progressivamente na tela. Falta o registro objetivo (vídeo/screenshot/GPU) para promover isso a evidência.

## 9. Protocolo para fechar a homologação (Gates A/B/C)

Pré-requisitos: fechar o MC; backup do JAR atual fora de `mods`; **um só** JAR Mirai; manter `DistantHorizons.toml`/cache; mesmo
mundo/rota dos testes anteriores; não mudar distância, resource pack, Angelica ou mods durante o A/B. Arquive `logs/akashic-mirai/*.log`
**e** `logs/latest.log` ao fim de **cada** rodada (o logger só guarda ≈20 MiB).

| Gate | Passos | Critério de aprovação | Reprova se |
|---|---|---|---|
| **A** — DISABLED | `rendererMode=DISABLED`; `/miraidiag status`; `/miraidiag trace 60` andando/voando pela rota; comparar `Clientside chunk ticking` com baseline (0.1.18) | Após drenar o que estava em voo: `LOD_RENDER_WALL`=0, uploads=0, `CHUNK_SUBMITTED`≈0, `QUIESCENT_*`>0 | Upload/render contínuo com DISABLED; fila crescendo; exceção de lifecycle |
| **B** — Reativação | Mesmo mundo: `DEFAULT` pela UI do DH; `/miraidiag mark renderer_enable`; voltar a `DISABLED`, esperar, **repetir** | LODs voltam progressivamente; `QUIESCENT_CATCHUP_SUBMITTED` cresce devagar (≤1 chunk/5 ticks); sem relog; sem buraco permanente; **sem `AssertFailureException`** | Crash, VBO id 0, buraco permanente, explosão de milhares de chunks |
| **C** — GPU/visual | `DEFAULT`; fechar o chat; `/miraidiag gpu 60` parado e depois deslocando; vídeo/screenshots da mesma cena em 0.1.18 vs 0.1.19 | `gpu_sample` + `gpu_terrain` na mesma captura; nenhuma regressão visual reproduzível; p95 de frame não pior que baseline | Mudança visual, pop-in, LOD atrasado, crescimento ilimitado de fila/memória |

Rollback imediato: voltar ao JAR anterior ou `-Dakashic.mirai.disabledRendererQuiescence=false`.

## 10. Reprodução desta verificação

```text
# identidade/formato
python scripts/verify-mirai-jar.py <jar> --expected-version 0.1.19-dev --expected-commit afef9d7e4f085c0d1dccba66d763db22878714ca \
       --expected-branch akashic/mirai-0.1.19-disabled-quiescence
# testes (worktree limpo no commit; aspas no -P são obrigatórias no PowerShell)
git worktree add --detach <dir> afef9d7e4f085c0d1dccba66d763db22878714ca
gradlew.bat "-PmcVer=1.7.10" --max-workers=4 --no-parallel --console=plain :core:test :api:test
# telemetria
python scripts/summarize-mirai-sessions.py --log-dir <...>/logs/akashic-mirai --out-json s.json --out-md s.md
```

Evidências arquivadas: `evidence/verification-2026-10-04/` e `evidence/runtime-2026-10-03/`.
