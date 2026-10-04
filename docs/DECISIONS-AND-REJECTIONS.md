# Decisões, rejeições e escopo candidato da 0.1.20

Registro do **porquê** — para que ninguém (humano ou agente) reabra por acaso uma ideia que já foi medida e descartada, nem
desfaça uma decisão de contrato. Cada item aponta a fonte original em `docs/`. Estilo: ADR curto.

## 1. Decisões que valem como contrato

| ID | Decisão | Motivo | Fonte |
|---|---|---|---|
| D-01 | Manter `modid=distanthorizons`, `DistantHorizons.toml`, API 7.1.0 e protocolo 15 | Compatibilidade com configs, dados LOD já gravados e com outros mods/servidores; o nome público é só *Akashic: Horizons of The Mirai* | `MIRAI-UPSTREAM.json`, `ARQUITETURA-MIRAI.md` |
| D-02 | Backend gráfico **somente LWJGL2** (as classes LWJGL3 opcionais do upstream vão no JAR mas não são selecionadas) | 1.7.10 / Java 8 / LWJGL 2.9.1 no cliente real | `COMPILACAO.md` |
| D-03 | **Nenhum código do Voxy**; ideias podem ser estudadas, o código é original | Voxy é "All rights reserved"; DH é LGPL-3.0 | `VOXY-DH-0.1.8.md`, `LICENSING-AND-ATTRIBUTION.md` |
| D-04 | **Contrato de fidelidade visual**: shaders, SSAO, AA, fog, fade, distância, resolução, transparência e geometria não mudam como estratégia de desempenho | Pedido explícito do dono: preservar o ambiente visual do pack | `COMPATIBILIDADE-0.1.8.md` (resultado executivo), `CHANGELOG-0.1.18-dev.md` |
| D-05 | `coreSubProjects` é **diretório normal** com o core modificado (nunca `git submodule update`) | O core upstream não tem as modificações Mirai | `COMPILACAO.md` |
| D-06 | Empacotar via `shadowJar → remapJar → downgradeJar → shadeDowngradedApi` (JvmDowngrader 1.3.6) e entregar bytecode major ≤ 52 | Runtime Java 8 real; código intermediário usa `--release 17` | `COMPILACAO.md` |
| D-07 | Geração distante **OFF** por padrão; `distant_generation` só experimental em servidor dedicado (`-Dakashic.mirai.experimentalServerGeneration=true`) | Risco para o backend Crucible/Java 21 | `CHANGELOG-0.1.0-dev.md` |
| D-08 | Diagnóstico próprio (`/miraidiag`, JSONL schema 2); timers GPU **só durante captura explícita** (`/miraidiag gpu 10–120`) | Medir antes de otimizar; observer desligado não pode criar trabalho GL | `DIAGNOSTICO-MIRAI.md`, `CHANGELOG-0.1.9-dev.md` |
| D-09 | `rendererMode=DISABLED` = **quiescência**: não cria render state no startup, não tica o quadtree, suprime chunk→LOD, `SyncOnLoad` e sync remoto novo; o que já estava em voo termina | Logs reais com DISABLED ainda mostravam `CHUNK_SUBMITTED`, `DB_COMMITTED` e `LOD_UPDATE_WALL` | `history/releases/0.1.19-dev/PATCH-REPORT.md` |
| D-10 | Backlog de chunks suprimidos = **coordenadas primitivas, ≤ 4096, mundo por `WeakReference`**; catch-up ≤ 1 chunk residente / 5 ticks, `chunkExists` antes, sem load/ticket/worldgen | Evitar retenção de `Chunk`, explosão na reativação e geração induzida | idem |
| D-11 | O render state antigo é reconstruído **uma vez** na reativação; sem shutdown pesado ao escolher DISABLED | Evitar hitch no instante da troca | idem |
| D-12 | Toda otimização exige **prova de equivalência** (bit a bit/ordem/FIFO) e A/B; microbenchmark não vira alegação de FPS | Várias hipóteses "óbvias" falharam (ver §2) | `PESQUISA-GPU-0.1.10.md`, `PLANO-E-DIAGNOSTICO-0.1.18.md` |
| D-13 | Chaves de rollback por JVM prop (`disabledRendererQuiescence`, `runtimeGovernor`, `diagnostics`) | Reverter comportamento sem trocar o JAR | `CLAUDE.md` regra 12 |
| D-14 | Código de laboratório (probes, auto-connect, telemetria de gate) vive em worktrees/branches de lab; o release fica limpo | Evitar vazamento de instrumentação experimental para produção | `PLANO-E-DIAGNOSTICO-0.1.18.md` |

## 2. Ideias avaliadas e **rejeitadas** (não retomar sem novo desenho + A/B)

| ID | Ideia | Veredito e evidência | Fonte |
|---|---|---|---|
| R-01 | Pular AA quando a profundidade DH é 1 | **Rejeitada**: o AA roda depois da composição do far fade; removeria AA também do céu/Minecraft já compostos | `PESQUISA-GPU-0.1.10.md` |
| R-02 | SSAO: reutilizar pesos espaciais / especializar limites do bilateral / fundir exponenciais / adiar leitura AO | **Rejeitada na 0.1.10**: ganho ≈1–3 %, 8–37 canais mudaram 1/65535, adiar a leitura *piorou* (3,8→6,7 ms). *Nota:* o que entrou na 0.1.13 foi só a **pré-computação dos pesos Gaussianos** (30→4 avaliações/pixel) com igualdade bit a bit no modelo — não confundir | `PESQUISA-GPU-0.1.10.md`, `CHANGELOG-0.1.13-dev.md` |
| R-03 | CAS: desligar o blend de substituição; trocar `inversesqrt`/recíproco por `sqrt` | **Rejeitada**: saída idêntica mas sinal inconsistente/alta dispersão; a simplificação algébrica mudou até 1/65535 | `PESQUISA-GPU-0.1.10.md` |
| R-04 | Reutilizar o `HashSet` de requeue do tick | **Rejeitada**: a história de capacidade do `HashSet` pode mudar a ordem de iteração → *readiness* do tick seguinte | `CHANGELOG-0.1.18-dev.md` |
| R-05 | Variantes do SSAO RAW (`raw-loop6`, `raw-specialized6`, `raw-recurrence`) | **Rejeitadas**: alteraram 113 / 50 / milhares de pixels R16F; `raw-step6` preservou bits mas sem ganho consistente. Evidência só na branch `akashic/mirai-gpu-lab` | `CHANGELOG-0.1.18-dev.md` |
| R-06 | IBO compartilhado por container | **Revertida em produção**: saída RGBA8 idêntica, GPU neutra, custo CPU de submissão pequeno/ruidoso e dependente de haver vários VBOs por container. Só reabrir se `gpu_terrain` mostrar `max_vbos_per_container` relevante | `CHANGELOG-0.1.18-dev.md`, `PLANO-E-DIAGNOSTICO-0.1.18.md` |
| R-07 | Persistir `timing.gl_state` como metadata do log | **Removida**: deslocava metadata em segmentos sintéticos pequenos (teste de rotação reprovou); escopo informado só no evento `trace start` | `CHANGELOG-0.1.15-dev.md` |
| R-08 | Cache global de estado GL do Akashic | **Rejeitada**: Angelica já cacheia `glGetInteger` (GLSM) e os outros renderizadores compartilham o estado; sem protocolo de invalidação seria incorreto | `COMPATIBILIDADE-0.1.8.md` |
| R-09 | Capturar `ArrayIndexOutOfBoundsException` no render para mascarar o crash CoreTweaks/CubicChunks | **Rejeitada**: o acesso que lança está no fork CubicChunks; corrigir lá (ou usar a API de altura) | `COMPATIBILIDADE-0.1.8.md` |
| R-10 | Desligar fog/SSAO/transparência/culling do usuário ou reduzir distância como "otimização" | **Rejeitada** pelo contrato D-04 | idem |
| R-11 | Remover o `join()` de backpressure do upload | **Proibido** sem substituto limitado em bytes | `CLAUDE.md` regra 5 |
| R-12 | Aumentar workers automaticamente ao detectar outros mods | **Rejeitada**: sem medição | `COMPATIBILIDADE-0.1.8.md` |
| R-13 | GPU culling / arenas persistentes ao estilo Voxy | **Não iniciar** sem novo desenho, prova de equivalência e A/B no pack real (e sem copiar código Voxy — D-03) | `VOXY-DH-0.1.8.md` |
| R-14 | Saída antecipada do TAA | **Adiada**: exige teste temporal completo, comparação pós-CAS/RGBA8 e cenas reproduzíveis; não resolve o SSAO predominante | `PESQUISA-GPU-0.1.10.md` |
| R-15 | Declarar "stutter eliminado" pela 0.1.19 | **Não afirmar**: o aviso `Clientside chunk ticking` pertence ao ciclo de chunks do cliente (outros mods participam) | `PATCH-REPORT.md` 0.1.19 |

## 3. Escopo candidato da 0.1.20 (decidir **com dados**, não por gosto)

Ordem sugerida; cada item já tem Issue em [ISSUES-BACKLOG.md](ISSUES-BACKLOG.md). Nada aqui está implementado.

1. **Fechar a homologação 0.1.19 antes de qualquer feature**: Gate A repetido, Gate B (≥ 2 reativações), Gate C (renderer ATIVO + `/miraidiag gpu 60`),
   A/B visual. Sem isso a 0.1.20 parte de uma base não certificada.
2. **Defeito de bioma na reativação** (`AssertFailureException` em `WrapperFactory.getPlainsBiomeWrapper:163`): reproduzir com reativação
   imediata e com espera; checar interação com o registro de biomas do EndlessIDs e com o companion EndlessIDs-Compat (revalidar contra `481d6f7b…`).
3. **"Duplicate or excessive LOD transfer"** (`FullDataPayloadReceiver.receiveChunk:93`): capturar o servidor emissor (retransmissão?) e
   decidir se o guard é suficiente ou se o cliente deve deduplicar antes.
4. **Atribuir os hitches longos** (6,5 s / 19,7 s): `/miraidiag trace` com as subfases de `LOD_UPDATE_WALL` já presentes na 0.1.19; se os
   contadores Mirai estiverem quiescentes, o problema é de outro mod/mundo e o Mirai não deve "consertar" por cima.
5. **Re-auditar ABI** contra Angelica 2.2.20 / GTNHLib 0.11.51 / Hodgepodge 2.7.189 (versões reais em runtime) e estender `tests/angelicaabi`.
6. **Folga de heap** (pico 78 % de 6 GiB só com renderer DISABLED): medir com renderer ATIVO; só então decidir ajuste de workers/governor.
7. **Retenção de logs**: arquivar por rodada (script `collect-mirai-logs.ps1` + `summarize-mirai-sessions.py`), ou subir o teto de segmentos.
8. **Desambiguar identidade**: nova build com versão distinta (`0.1.20-dev`); manter `0.1.19-dev` congelada.

**Não entram** na 0.1.20 sem evidência nova: qualquer item de §2; mudanças de shader; "otimizações" de SSAO/AA/fog.
