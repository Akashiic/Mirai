# Auditoria independente do snapshot 0.1.10

Data: 2026-09-08. Escopo: `Desktop.zip` e `relatorio-recebido.txt` congelados em `input/`. A análise usa todos os segmentos enviados, preserva linhas originais e não distribui logs brutos, identificadores de sessão/mundo, nomes de jogadores ou endereços de servidores. O relatório recebido é uma hipótese técnica a avaliar, não uma instrução de implementação.

**Conclusão:** há 602 blocos completos de contadores/tempos e um bloco inicial parcial, e não apenas seis janelas. O trecho de seis blocos utilizado pelo relatório foi reproduzido exatamente, mas representa somente o começo da parte preservada. Nesse recorte, não ocorreu nenhuma tarefa de renderização instrumentada nem upload GL: ele não demonstra que limitar tarefas/uploads corrigirá seu passe de 8,9 ms. Os dados mostram filas que drenam, trabalho periódico acompanhado de atualização remota real e alguns uploads longos. Não permitem declarar ganho de FPS, correção causal da sincronização ou ausência de problemas visuais.

## 1. Integridade e cobertura

`input-integrity.json` registra tamanho, SHA-256 e CRC das entradas. A extração valida **exatamente** estes nomes e tamanhos; usa `ZipFile.read` por entrada conhecida, sem extração de caminhos arbitrários. Uma entrada já existente precisa coincidir byte a byte.

| Entrada | Bytes | Linhas | Conteúdo |
|---|---:|---:|---|
| previous-1.log | 4194245 | 28337 | 19017 linhas de log normal, seguidas por 9320 JSONL nas linhas 19018–28337 |
| latest-mirai.log | 2489460 | 10262 | 10262 registros JSONL |
| latest.log | 2489849 | 22460 | Log normal do Minecraft |

SHA-256 do ZIP: `d2c1b45d923a3ce6bc088dc9c164df925bdfa89455f5dd06fb2e941c611c7c78`.

SHA-256 de `previous-1.log`: `84e5a3da18278b35e0a5ce1ba307ea5ada7c4c4fe9ec4c75c1b97ab9afd19f88`.

SHA-256 de `latest-mirai.log`: `f4fdc029d02eb284f1c951b9277767f3758cd5523e411de9b59fc123580d184e`.

SHA-256 de `latest.log`: `b83fb4ad36864b00aad95f82358d8677329551a0c8735a2acee76110cd40e52b`.

Os dois segmentos JSONL contêm **19582 registros válidos de uma sessão**, sem duplicata exata nem rejeição de JSONL. O prefixo normal de `previous-1.log` e `latest.log` tem 16784 linhas iniciais iguais; depois diverge. Não se somam cegamente suas ocorrências. A origem do arquivo híbrido não foi demonstrada e não é atribuída ao logger.

Cobertura JSONL: `elapsed_ms=991204…4019384`, UTC `19:10:38.350…20:01:06.530`, ou **16:10:38.350…17:01:06.530** no fuso UTC−3. São 50 min 28,180 s preservados, começando cerca de 16 min 31 s depois do início da sessão. A parte anterior está ausente. A primeira linha já é `FRAME_INTERVAL`, sem seu contador precedente; seus 17 tempos são preservados como bloco parcial, fora da soma dos 602 blocos completos. Estes começam em P:19050 e terminam em M:10232; P = `previous-1.log`, M = `latest-mirai.log`.

O log normal `latest.log` não é UTF-8 estrito: a leitura de apresentação usa substituição de 364 caracteres inválidos. Os bytes originais permanecem intactos; padrões técnicos ASCII, timestamps e linhas continuam verificáveis. O analisador GPU genérico chama as linhas normais de `malformed_json_lines`; isso é consequência de lhe fornecer logs mistos, não prova de corrupção de JSONL.

## 2. Identidade e ambiente

M:15–18 identifica `0.1.10-dev`, commit `987f3e7941a471e7b5e0f9c943117fba9ff356d0`, Forge `10.13.4.1614`. A coincidência de identidade não é verificação do hash do JAR instalado, que não foi enviado neste ZIP.

M:2–8 informa Java Oracle 8u51, oito processadores e nome de SO reportado pela JVM como Windows 8.1. M:58 registra CubicChunks `v0.1.21-akashic-crucible-0.1.0-dev.7`, diferente do **dev.6** da rodada 0.1.9. Angelica 2.2.8, Hodgepodge 2.7.189 e CoreTweaks 0.3.4.7-GTNH aparecem em M:60–61/124; EntityCulling informa `@VER@` em M:141.

M:240–282 preserva radius 128, qualidades HIGH/HIGH, resolução BLOCK, transparência COMPLETE, SSAO/AA/fog DH ligados, quatro workers, vsync ligado, Intel UHD, driver OpenGL `27.20.100.9268`, LWJGL 2.9.1 e shaderpack inativo. O suporte ao timer aparece como OpenGL33/64 bits/true. Isso comprova a capacidade detectada, não a existência de amostras preservadas.

Todos os 603 snapshots de cliente estão na dimensão 0 e resolução 1920×1001. Os 603 snapshots VBO mantêm total 697, com render count entre 234 e 253. Não há trajetória/posição/câmera suficiente para afirmar cena idêntica; total de VBO constante tampouco garante geometria ou conteúdo idênticos.

## 3. GPU e trace: captura anterior ao trecho recuperado

Em `latest.log`:

| Linha | Horário local | Evidência |
|---:|---|---|
| 21074 | 16:04:50 | Trace solicitado por 60 s |
| 21111 | 16:06:21 | GPU solicitada por 60 s; disponibilidade será reportada no próximo passe |
| 21161 | 16:08:07 | `trace=false`, `samples=164`, `gpu=off`, `dropped=0` |
| 21173 | 16:08:33 | Comando de parada GPU; logging baseline continua |
| P:19018 | 16:10:38.350 | Começo do JSONL sobrevivente |

Logo, tanto a solicitação quanto a parada antecedem a cobertura JSONL. `samples=164` é a contagem de stack samples reportada pelo comando; não são 164 medidas GPU. Nenhum `gpu_sample`, `gpu_capture` ativo ou resultado de indisponibilidade foi preservado nos três segmentos. Todos os contadores GPU nos 602 blocos completos são zero, inclusive falhas e drops. Os dados são compatíveis com descarte/retenção incompleta da parte anterior; não demonstram defeito do profiler nem que a captura solicitada realmente produziu amostras.

**Resultado GPU desta rodada: indisponível**, não 0 ms. Não há conjunto para agrupar por origem/foco/cena ou comparar SSAO/AA. Os logs locais adicionais verificados pela coordenação pertenciam à sessão 0.1.9 ou a uma sessão mais antiga; não foram usados como substitutos da captura 0.1.10.

A comparação anterior continua sendo a auditoria pareada da 0.1.9: 559 amostras aceitas, 280 com LOD e snapshot focado na **origem**, PASS médio 28,044 ms, SSAO 7,834, AA 7,121, OPAQUE 3,302, FAR_FADE 4,220, FOG 3,200 e COMPOSITE 1,139 ms. O relatório recebido cita outro conjunto (por exemplo PASS ~27,2); não fornece seleção reproduzível equivalente. Os hashes dos resumos anteriores e as estatísticas pareadas estão incorporados em `baseline_0_1_9` no resumo atual.

CPU wall e intervalos GPU se sobrepõem e não se somam. PASS não mede todo o frame GPU nem utilização exclusiva da GPU; pode incluir falta de comandos. PASS contém os estágios; somar PASS novamente aos estágios conta trabalho duas vezes. A auditoria anterior verificou que a soma dos subestágios **das mesmas amostras** cabe em PASS, sem resto negativo. Portanto não se deve explicar a soma de médias de populações diferentes dizendo simplesmente que os subestágios se sobrepõem.

## 4. Tempos CPU e foco: recorte correto, extrapolação incorreta

Médias ponderadas por `count`, em ms. Os histogramas fornecem limites de p95, não p95/p99 exatos nem 1% low.

| Seleção | Frames/invocações | FRAME_INTERVAL | FRAME_EVENT_SPAN | CLIENT_RENDER_PASS_WALL | LOD_RENDER_WALL |
|---|---:|---:|---:|---:|---:|
| Seis primeiros blocos visíveis, incluindo o parcial | 575 | 52,540 | 27,410 | 8,914 | 8,773 |
| Todos os 602 blocos completos | 38646 | 78,354 | 15,905 | 4,527 | 4,293 |
| Três blocos com snapshot final focado | 274 | 55,142 | 24,793 | 6,782 | 6,692 |
| Dois blocos com snapshots focados nas duas fronteiras | 181 | 55,721 | 25,029 | 6,822 | 6,722 |
| Seis últimos blocos completos | 302 | 100,002 | 5,343 | 1,621 | 1,598 |

`FRAME_EVENT_SPAN` tem 38647 eventos no agregado completo, um a mais que `FRAME_INTERVAL`; cada média utiliza seu próprio denominador. A média dos seis blocos recebidos foi reproduzida até as casas decimais. São o bloco parcial P:19018–19034 e os cinco completos que começam em P:19050, 19082, 19114, 19146 e 19178. O último bloco completo disponível é muito posterior, não esse recorte.

**593/603** snapshots têm GUI aberta, sem foco e display inativo; outros sete têm GUI aberta e display ativo. Só três têm gameplay focado: M:502/534/566 (`elapsed=2490013/2495055/2500096`). Os blocos associados terminam em M:503/535/567. O primeiro atravessa mudança de contexto; os dois seguintes têm snapshots focados nas fronteiras, mas não uma prova contínua de foco/posição durante todo o intervalo.

O script associa cliente/JVM à fronteira temporal mais próxima, com tolerância máxima explícita de 1000 ms. A ordem física não basta: M:502 contém o snapshot **antes** do contador M:503, enquanto em outros pontos o snapshot vem depois. As distribuições do erro de pareamento ficam em `snapshot_pairing_diagnostics`. Os tempos são associados ao bloco de contadores preservando a ordem física dentro desse lote.

O inverso dos 55,721 ms dos dois blocos focados é aproximadamente 17,95 FPS nessa seleção de apenas 10,082 s, não média de gameplay da sessão nem A/B. 432 blocos terminam com cap 10 registrado e têm intervalo médio 99,538 ms. Os seis finais ficam próximos de 100 ms. O log normal confirma mudanças de cap/pacing em suas linhas 21322, 21413–21425, 21543, 21831–21850 e 21967. Portanto o agregado global não é benchmark de desempenho.

**Teste decisivo da hipótese de governor:** nos seis blocos que sustentam a recomendação de 8,9 ms, `RENDER_TASK_WALL count=0` e todos os uploads GL têm `count=0`; `RENDER_TASK_QUEUE_WALL` médio é apenas **0,003626 ms**, máximo 0,499 ms. Na seleção dos dois blocos focados, uploads também são zero e o drain médio é 0,003525 ms. Adiar essas tarefas não explica a redução pretendida daquele custo contínuo. Um governor exige outra evidência e validação, especialmente se alterar frequência de culling ou efeitos visuais.

## 5. Filas, sincronização e trabalho periódico

| Pool | Snapshots fila zero / total | Máximo observado | Evidência do máximo |
|---|---:|---:|---|
| Render_Load | 570/603 | 227 | P:20129, elapsed 1162231, quatro em execução |
| LOD_Builder | 601/603 | 3 | P:20096, elapsed 1157217 |
| File_Handler | 569/603 | 70 | P:20131, elapsed 1162231 |
| Network | 596/603 | 2 | P:23941, elapsed 1760935 |
| World_Gen_Import | 603/603 | 0 | P:19044 até M:10258 |

Os dez episódios de Render_Load voltam a fila zero observada após 5,093–35,288 s desde o primeiro snapshot não zero. O período entre inícios é **300,110 s em média**, mínimo 296,607 e máximo 301,805 s. A resolução de amostragem é ~5 s; isso não é a latência individual de cada tarefa. `File_Handler` mostra picos nos mesmos episódios. `average_task_wall_ms` é média móvel das 200 tarefas concluídas, não média acumulada da sessão, CPU time nem tempo de espera do Client Thread.

`periodic-bursts-table.md` e `periodic-bursts-sanitized.json` detalham todos os dez episódios, com último zero, início, retorno a zero, deltas de requests, bytes, commits, pools, uploads, GC e buffers. **Todos** têm tráfego recebido real e commits. As somas de DB_COMMITTED por episódio são 232, 115, 612, 79, 207, 81, 279, 76, 63 e 77: **1821, todos os commits da cobertura**. Os LOD_UPDATE são 235 no primeiro e iguais aos commits nos demais, total 1824. Isso impede concluir, a partir da periodicidade isolada, que são reconstruções redundantes sem novos dados. Há atualização remota observável; ainda falta saber se o conteúdo resultante mudou e quem originou cada atualização.

Toda a cobertura registra `CHUNK_SUBMITTED=0`, mas isso não elimina atualização remota: há 198 cold requests, 275 refresh requests, 473 envios/recebimentos, 10226647 bytes de fragmentos, 81 respostas refresh unchanged e 1821 commits. Comparar número de commits com tarefas de Render_Load/File_Handler exige conhecer a granularidade; eles não contam a mesma unidade.

Em **603/603** snapshots, `sync_deferred=0`, `support=FULL`, `ready=true`, `sync_allowed=true`, `generation_allowed=false`, conexão não fechada. `handoff` é sempre configurado 4, recebido −1, efetivo 0; o render state está ativo e a dimensão não é ignorada. A rede e o renderer progridem: Render_Load concluídos 4393→7607, Builder 425→838 e Network 2535→3008. Fila zero não implica toda atividade parada; por exemplo File_Handler termina com uma tarefa em execução.

Na auditoria 0.1.9, o máximo deferred era **1111**, não apenas 1091, e todos os 434 snapshots positivos tinham `support=NONE`, `ready=false`. O estado mudou de forma material. Fila zero atual é evidência de drenagem **neste estado de conexão**, não prova de que a 0.1.10 consertou uma fila travada da 0.1.9. A rodada anterior já tinha muitos snapshots de fila zero. Tampouco 227 contra `>=256` demonstra melhoria sem mesma cena, carga, cache, foco e dependências.

## 6. Picos, uploads, GC e memória

Nos 602 blocos completos, o drain de tarefas tem média **0,181 ms por passe**; 17217 tarefas individuais têm média 0,400 ms. Há 5397 uploads `GL_STORAGE_UPLOAD_WALL`, média **1,192 ms por chamada**, com **2493694272 bytes** acumulados. Bytes acumulados de transferências não são memória residente nem VRAM.

P:22028–22046 (`elapsed=1463922`) é um alvo concreto: upload máximo **100,333 ms**, tarefa 100,347 ms, drain 100,807 ms e passe cliente máximo 104,936 ms. Na mesma janela, GC aumentou 32 ms. As medidas são compatíveis com um upload longo dentro da tarefa/passe, mas os máximos não identificam sozinhos a mesma invocação nem separam CPU ativa, driver, espera e pausa JVM. Uma chamada GL já iniciada é indivisível para um budget entre tarefas; reduzir quantidade futura não garante limitar seu máximo.

O maior `FRAME_INTERVAL` completo é **440,559 ms**, P:19787. Nesse bloco, o passe cliente máximo é 28,615 ms (P:19793) e GC delta é zero (P:19804). Portanto o pico não pode ser integralmente atribuído ao passe instrumentado ou a GC detectado naquela janela. O maior passe cliente é **205,962 ms**, P:20337, LOD 205,870 ms (P:20334), acompanhado de GC delta 117 ms (P:20348). Correlação de janela não mede a parcela causal da pausa.

O acumulado GC já começa em **16694 ms / 347 coletas** em P:19035. Termina em **19921 ms / 436 coletas** em M:10250: a parte efetivamente observada adiciona **3227 ms / 89 coletas**. O maior delta entre snapshots é 146 ms (P:19228). O relatório recebido usa corretamente o valor inicial de 16,7 s, mas não o estado final disponível. A menor RAM livre registrada é 716300288 bytes em P:22718; a medição é do sistema inteiro.

Direct buffers JVM: começam em 929 / 100309745 bytes; terminam em 2745 / 107449508 bytes. A maior contagem é 9437 (M:1898), e o maior total de bytes 114022764 (P:20990). Crescimento 929→5827 com ~5 MB adicionais é um recorte inicial, não uma tendência monotônica de toda a sessão. Os dados não identificam o dono das alocações, não medem VRAM e não provam vazamento do Mirai. Há churn que merece investigação caso se consiga atribuir a alocação ao módulo.

## 7. Erros, visuais e transições

Todos os contadores completos `DH_ERROR_REPORTS`, DB_FAILED, REMOTE_FAILED, falhas Java de upload, rejeições de rede, overflow de deferred e categorias `errors_*` são zero; `dropped_total` máximo é zero. O campo `queue` dos contadores chega a 11 e é a fila do logger, não Render_Load.

Não aparecem Camera.update/ClientLevelModule.clientTick em pilhas, FullData ID map fora de sincronia, AIOOBE, OOM, SQLITE_CORRUPT/database locked, GL_INVALID ou novo crash CoreTweaks nos arquivos inspecionados. É ausência observada com cobertura incompleta, não prova universal de correção nem homologação visual.

Existem problemas/avisos externos ou de integração no log normal:

- 305 mensagens de erro de classloading, 309 linhas contendo NullPointerException, predominantemente ligadas a esse carregamento. Contar NPE global como NPE do Mirai seria incorreto.
- Sete falhas de registro de dimensão em `latest.log:17987–17993` e 14 avisos de dimensão do servidor sem correspondência no cliente, entre 19274 e 20213. Não mudar IDs automaticamente; requer dono da dimensão/configuração e compatibilidade com o servidor.
- Dois erros de script MineTweakerRecipeMaker por entrada nula, em 19926 e 20861.
- Seis avisos de ícone não utilizável emitidos por DH-Render Loader, incluindo AE2 OreQuartz/OreQuartzCharged em 20049/20051/20975 e MatterOverdrive BlockInscriber em 21176. São candidatos concretos à investigação de fallback visual; o log não informa a aparência final nem autoriza inventar uma cor/textura.
- Quinze mensagens `GPU culling unavailable (MULTI_DRAW_NOT_INDIRECT)` em caminhos de Angelica, antes do trecho JSONL recuperado. A mensagem não mede o culling do Mirai nem demonstra que sua GPU carece da extensão. Um culling temporal do Mirai precisaria de evidência própria e invalidação correta para não omitir geometria.

O log normal tem **773**, não 138, ocorrências de cada mensagem ArchaicFix ausente / remote channel register. Para o registro remoto, 772 intervalos: 732 de 5 s, 26 de 6 s, nove de 0 s e cinco de 1 ou 3 s. Cobertura 15:57:38→17:01:21. Isso quantifica repetição; não mede carga Netty/CPU nem demonstra por si só polling desnecessário. O emissor e o evento real precisam ser auditados antes de alterar o mod externo.

Há três fallback safe proxy no log normal: #1 armado 15:59:37 e concluído 15:59:45; #2 15:59:58→16:00:04; #3 16:00:47→16:00:48. Esses intervalos de mensagens não são medidas contínuas de bloqueio do Client Thread. Todos antecedem o JSONL de 16:10:38; não há frametime preservado que cubra essas transições. Criação temporária e identidade estável são discutidas pela auditoria separada de handoff. Não se pode transformar os seis segundos entre mensagens em seis segundos comprovados de bootstrap DH bloqueante.

## 8. Julgamento do relatório recebido e próxima evidência

| Alegação/proposta recebida | Resultado da auditoria |
|---|---|
| Só seis janelas sobreviveram (linhas 112/237) | Refutada: 602 completas + uma parcial. As seis primeiras foram calculadas corretamente. |
| GPU bruta ausente (linha 235) | Confirmada nos três segmentos; comandos precedem a cobertura JSONL. Sem prova de falha do profiler. |
| A pipeline drena (linha 43) | Confirmada nos episódios observados, com fila Render_Load até 227 e estado de rede FULL/ready. Não é A/B causal. |
| Governor como P0 causal para os 8,9 ms | Não sustentado pelo recorte: tarefas e uploads zero. Pode ser experimento futuro com justificativa diferente. |
| Deferred0 demonstra sincronização melhor | Não demonstrado: estado anterior NONE/not-ready versus atual FULL/ready. |
| 138 repetições externas (linha 890) | Recorte incompleto: 773 no latest normal. Custo de processamento não medido. |
| Handoff de ~6 s prova stall DH | Não demonstrado por intervalo entre logs, sem frame/stack pareados da transição. |
| Redução automática SSAO/AA/culling | Altera produto visual e requer decisão/teste específico; não decorre dos dados ausentes de GPU. |

Ordem de investigação sustentada por esta rodada:

1. Preservar a captura inteira desde o comando, incluindo GPU por origem, estado e stacks; o logger perdeu cobertura útil no material entregue.
2. Tratar warnings concretos de integração/ícone e medir lifecycle durante handoff sem remover isolamento de cache nem ocultar LODs arbitrariamente.
3. Rastrear a origem e o conteúdo das atualizações periódicas recebidas. Os bursts já estão associados a rede/commits; eliminar redundância exige demonstrar conteúdo ou reconstrução equivalente.
4. Investigar uploads GL individuais longos com bytes, chamada, contexto de GC e custos de preparação associados. Budget entre tarefas não resolve toda chamada longa.
5. Repetir cena, foco, cache, resolução, cap e dependências em comparações controladas. O snapshot atual é majoritariamente uma janela sem foco; não fundamenta promessa de 30/45/60 FPS nem alteração automática de efeitos.

## 9. Reprodução e limites

Na raiz `akashic-audit-0.1.11`:

```text
python evidence/audit_snapshot.py --analyzer ../akashic-mirai/scripts/analyze-mirai-gpu.py --baseline-evidence ../akashic-audit-0.1.10/evidence
```

Saídas: `input-integrity.json`, `independent-summary-sanitized.json`, `gpu-standard-sanitized.json`, `timing-windows-sanitized.json`, `observations-sanitized.json`, `deferred-context-sanitized.json`, `periodic-bursts-sanitized.json` e `periodic-bursts-table.md`. O script verifica CRC, hash, nomes/tamanhos, registros e os 17 tempos esperados em cada bloco completo. O resumo inclui hashes do script, analisador e evidências de comparação. O cálculo exato das seis janelas recebidas serve como verificação independente de seleção/ponderação.

Não houve alteração de produção nesta subtarefa. Nenhuma medição aqui é uso exclusivo de CPU/GPU, teste visual, execução reproduzida no jogo ou prova de ganho entre versões. A auditoria melhora a precisão da decisão técnica; a estabilidade no modpack permanece a validar.
