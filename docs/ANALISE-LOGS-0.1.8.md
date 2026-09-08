# Auditoria de logs — Akashic of The Mirai 0.1.7

Data da análise: 08/09/2026. Os anexos foram tratados como evidências; não como instruções de execução. Os números abaixo vêm dos arquivos originais indicados pelo usuário. Não foram alteradas configurações do jogador, arquivos do modpack ou mundos.

## Conclusão técnica

Os logs são úteis e suficientes para confirmar travamentos, localizar trabalho redundante no caminho de transformação de LODs e separar falhas próprias de falhas de outros componentes. Eles **não** demonstram que toda a queda de FPS vem do Mirai nem permitem declarar ganho de FPS após uma mudança sem novo teste no jogo.

Há uma reprodução determinística de um aviso do Mirai: colunas não solicitadas em uma leitura parcial de DTO recebem a sentinela `EMPTY_DATA_POINT = 0`, mas o transformador tentava resolver essa sentinela como o bloco de ID 0. Isso gera exceções repetidas quando o mapa está vazio, ou consultas de cor desnecessárias quando existe um ID 0 real. A correção implementada pula somente o datapoint completamente zero, preservando datapoints reais que utilizam ID 0. A análise não classifica os dois avisos como prova de corrupção do banco nem de perda de blocos reais.

As duas falhas que encerraram a renderização normal no teste 0.1.7 têm origem em caminhos do CubicChunks dev.4: uma interação de iluminação com CoreTweaks e um cast no diagnóstico F3. CoreTweaks recuperou o cliente, de modo que `session_end=normal_shutdown` nos diagnósticos Mirai não significa ausência de crashes.

## Identidade e comparabilidade das execuções

| Arquivo | Versão explicitamente registrada | Intervalo UTC | Duração de log | União dos intervalos com mundo carregado |
|---|---|---|---:|---:|
| `previous-2.log` | 0.1.6-dev, linha 261 | 01:46:02–02:31:45 | 45min43s | 2.513,424 s |
| `previous-1.log` | 0.1.6-dev, linha 241 | 03:02:20–03:23:38 | 21min18s | 482,219 s |
| `latest.log` | 0.1.7-dev, linha 574 | 04:11:22–04:19:18 | 7min55s | 126,818 s |

São três sessões distintas; os arquivos anteriores não são novas rodadas da versão 0.1.7. A sessão mais recente só registra duas permanências no mundo: 90,482 s, entre linhas 1278 e 1726; e 36,336 s, entre linhas 1864 e 2050. As anteriores contêm trocas rápidas de dimensão, com eventos de load do novo mundo antes de unload do antigo; por isso o script calcula intervalos por `native_token` e toma a união, sem contar sobreposição duas vezes.

O ambiente também mudou: os logs 0.1.6 identificam CubicChunks `dev.3`; `log.txt` e o inventário 0.1.7 identificam `dev.4`. Na 0.1.7 o protocolo Mirai aparece como `support=NONE`, sem envio/recebimento de pacotes. As 0.1.6 tiveram requisições e cancelamentos efetivos. Não são comparações A/B controladas de desempenho.

O `log.txt` está no horário local UTC−3: por exemplo, o crash 01:17:45 corresponde a 04:17:45 UTC. Foi usado esse deslocamento para contextualizar a telemetria.

## Ambiente efetivamente observado

- Oracle Java `1.8.0_51`, 64 bits; 8 processadores lógicos; heap máximo 5.368.709.120 bytes (5 GiB). A JVM reporta Windows 8.1; isso é o valor observado, não uma verificação independente do sistema instalado.
- Intel UHD Graphics; OpenGL `4.6.0 - Build 27.20.100.9268`; LWJGL 2.9.1. `arb_timer_query=true` indica capacidade, não medição feita. Ver `latest.log:1217–1222`.
- Angelica 2.2.8 (`latest.log:620`); Hodgepodge 2.7.189 (`619`); CoreTweaks 0.3.4.7-GTNH (`683`); EntityCulling com versão não expandida `@VER@` (`700`). Não se deve inventar uma versão para este último.
- ParallelMipMap-1.0.jar confirmado em `log.txt:1298` como arquivo injetado no classpath, sem registro de mod. A ausência em `mod.*` não prova que estava ausente.
- Distância vanilla 4, limite 60 FPS, VSync ligado; raio LOD 128; 4 workers/ratio 1.0; transparência COMPLETE, fade DOUBLE_PASS, SSAO e AA ligados; nenhum shaderpack ativo (`latest.log:800–819`, `1223–1229`). Isso é contexto; nenhuma dessas escolhas visuais foi alterada para produzir um resultado artificial.

## O que os tempos permitem afirmar

Todas as durações Mirai são tempo decorrido de parede, incluindo esperas. `CLIENT_RENDER_PASS_WALL` contém trabalho também medido em fila, tarefas, upload e render LOD; **não se somam essas métricas**. `GL_*_UPLOAD_BYTES` é entrada tentada e não VRAM alocada. O p95 é somente limite de um bucket por janela; não um percentil exato por frame. Cada métrica é drenada separadamente, e uma operação longa aparece na janela em que termina. Médias agregadas usam `sum(count × mean_us)/sum(count)` e têm a pequena imprecisão de truncamento original para microssegundos.

| Métrica 0.1.7 | Amostras | Média ponderada de todas as amostras | Maior amostra | Evidência do máximo |
|---|---:|---:|---:|---|
| Intervalo entre inícios de render event | 1.956 | 94,995 ms | 15.960,538 ms | `latest.log:1745` |
| Span START→END do render event | 1.956 | 26,652 ms | 13.801,385 ms | `latest.log:1358` |
| Passagem completa DH cliente | 908 | 15,597 ms | 3.865,678 ms | `latest.log:1391` |
| Render LOD | 903 | 9,107 ms | 1.588,548 ms | `latest.log:1388` |
| Drenagem de tarefas de render | 966 | 1,708 ms | 303,048 ms | `latest.log:1673` |
| Tarefa de render individual | 5.230 | 0,303 ms | 301,253 ms | `latest.log:1674` |
| Upload GL storage | 340 | 1,389 ms | 44,447 ms | `latest.log:1703` |

Essas médias incluem entrada, menus, recuperação de crash e encerramento; não equivalem a FPS médio de uma rota jogada. Em particular, o máximo de intervalo de 15,96 s aparece **depois** do primeiro unload/crash, e não corresponde a um draw call DH de 15,96 s.

Entre 04:17:04 e 04:17:44, com mundo carregado e a última observação de GUI fechada, os intervalos médios por janela ficam entre 84 e 140 ms, enquanto o span de render event fica entre 25 e 45 ms e a passagem DH entre 4,96 e 12,82 ms. Há custo substancial fora do intervalo instrumentado do render event. Os anexos não discriminam espera de apresentação/VSync, tick, outros mods, GC e driver nesse restante. A diferença de médias não é uma medição de tempo GPU.

A janela 04:17:39 é um alvo concreto: máximo da passagem DH 305,763 ms (`1671`), da fila 303,048 ms (`1673`), da tarefa 301,253 ms (`1674`), do upload storage apenas 10,013 ms (`1675`). Isso é consistente com uma tarefa cara na fila, mas, sem trace/nome amostrado, não identifica qual tarefa foi responsável nem exclui uma pausa da JVM durante sua execução. A janela teve 219 ms adicionais de GC (`1678`).

O Render_Load está saturado nas amostras `latest.log:1376`, `1404`, `1432`: `queued_observed=>=256`. Esse valor é um limite inferior deliberado do diagnóstico, não uma fila exatamente de 256 entradas. A resolução redundante das sentinelas ocorre justamente neste caminho; eliminá-la reduz trabalho demonstrável sem reduzir distância ou aparência.

## Memória, GC e diagnósticos

O total acumulado de GC sobe de 2.987 ms (`latest.log:26`) para 35.832 ms (`2130`), diferença de **32.845 ms** em 468,153 s entre essas observações. Esse número abrange a JVM inteira, não alocação exclusiva do Mirai. Sem log de GC detalhado não se pode atribuir cada pausa ou inferir automaticamente saturação de heap.

Na recuperação do primeiro crash aparecem diferenças sucessivas de 5.222 ms (`1723`), 3.024 ms (`1741`) e 5.107 ms (`1758`) de GC; no segundo, 4.517 ms (`2047`), 2.202 ms (`2065`) e 3.457 ms (`2089`). A correlação temporal é forte, mas não demonstra que o Mirai solicitou esses GCs.

A RAM livre do sistema cai para aproximadamente 673 MiB na amostra `1964`, durante a segunda permanência. O heap observado máximo não atinge o máximo configurado, e não foi encontrada `OutOfMemoryError`. Buffer pools Java excluem outras alocações nativas e memória GPU; não há prova suficiente para declarar vazamento de VRAM.

O snapshot de diagnóstico custa até 15,439 ms (`1383`) e 12,845 ms (`1467`), em chamadas periódicas de aproximadamente 5 s. Há custo observável do próprio observador, mas os anexos não mostram trace intensivo ativo: não aparecem `trace`/pilhas de amostragem de performance. A política limita a fila e nenhum segmento reporta eventos descartados.

## Erros: origem e ação correta

### Sentinela de LOD interpretada como bloco — correção implementada

`log.txt:19215` e `20118` registram o mesmo formato de aviso, em posições diferentes: ID 0 com mapa de tamanho 0. O emissor original é `FullDataToRenderDataTransformer.java:381`, após tentar obter bioma/estado nas linhas 370–372. O catch ignora o datapoint e silencia avisos adicionais para aquela posição, mas a tentativa e a criação de exceções continuam para outras colunas e novas transformações.

Caminho demonstrado no código:

1. `FullDataSourceV2DTO.populateDataSource:246–250` preenche colunas com `EMPTY_DATA_POINT` antes de decodificar a região solicitada.
2. Uma leitura adjacente decodifica somente uma linha/coluna de 64 posições; as outras 4.032 continuam com a sentinela.
3. `populateDataSource:305` marca a fonte como não vazia, mesmo quando a região solicitada e seu mapa são vazios.
4. O transformador trata comprimento de coluna 1 como dado e resolve `getId(0) == 0` sem verificar a sentinela.

Pular apenas `fullData == EMPTY_DATA_POINT` evita até 4.032 consultas/exceções desnecessárias por fonte parcial, conforme o conteúdo do mapa. Se as quatro bordas forem lidas, isso pode eliminar 16.128 tentativas por construção central, sem afirmar que todas as construções do log seguiram esse pior caso. Um ID 0 com altura/luz/dados reais permanece válido. Um datapoint não zero com ID ausente continua sendo reportado.

Os três testes `FullDataEmptySentinelTest` executam serialização/decodificação real do DTO e transformação completa: borda vazia sem mapa; borda com ID 0 real e preservação exata de geometria/cor/luz/material; e ID inválido real como controle. O XML da suíte Gradle de 08/09 às 04:56:45 UTC registra 3 testes, 0 falhas/erros. Evidências complementares de JVMs e prova negativa ficam em `akashic-mirai/evidence/sentinel`.

Validação adicional concluída em Temurin 8u362 e Temurin 21.0.11: os mesmos três testes passam com a correção em ambas as JVMs. Compilando somente o transformador original 0.1.7 e colocando-o primeiro no classloader isolado, os dois testes de sentinela falham e o controle de ID realmente inválido passa, nas duas JVMs. No cenário válido a versão original consulta a cor 4.033 vezes, contra 1 esperada/aprovada na versão corrigida. Essa prova negativa demonstra que os testes capturam o defeito original, sem alterar o código de produção usado pelo build. Não representa medição de FPS, GPU ou validação no Oracle 8u51 original.

`FullDataPointUtil.remap` e a compactação de mapa também não distinguem a sentinela em todos os pontos, mas as leituras parciais demonstradas acima são consumidas pelo renderer. Não foi provado um trajeto de atualização afetado nos anexos. Esse outro caminho foi registrado para revisão, sem ampliar a alteração de produção por suposição.

### Dois crashes externos a corrigir no componente responsável

Primeiro crash, 01:17:45 local: `log.txt:19224–19251`. Raiz `ArrayIndexOutOfBoundsException: 16` em `Chunk.getEBS_CubicChunks`, chamado por redirecionamentos CubicChunks de iluminação; a cadeia inclui `CoreTweaks LightFixStare.postPlayerCheckLight`. A fronteira vertical de seção é um alvo de reprodução no CubicChunks. Não há chamada Mirai na pilha causal mostrada. Isso documenta uma interação concreta com CoreTweaks, não uma falha GL.

Segundo crash, 01:18:54 local: `log.txt:20131–20144`. Raiz `ClassCastException` de `ChunkProviderClient` para `CubeProviderClient`, passando por `WorldClient.getCubeCache` e `CubicChunks WorldDiagnostics.onRenderGameOverlayTextEvent:26`. Angelica HUDCaching aparece como chamador do evento; Mirai apenas consta na lista de listeners (`20168`), o que não atribui a ele a exceção. Reproduzir F3 em mundo vanilla com CubicChunks instalado é o alvo concreto.

Os fontes anexados são do Mirai e não contêm a implementação CubicChunks dev.4 necessária para corrigir e testar esses dois defeitos com responsabilidade. Não se recomenda mascarar tais exceções em render hooks Mirai.

### Rede, inicialização e ruído de outros componentes

- Quatro impressões de `Timeout waiting for client thread to catch up` em `log.txt:19880`, `19914`, `19948`, `19982`, todas às 01:18:14, propagam a mesma condição pela pipeline FML/Netty. Não são quatro falhas independentes do protocolo Mirai. Acontecem entre as duas entradas após recuperação de crash.
- `log.txt` possui 299 registros “Exception encountered attempting classloading”, muitos NPEs durante inicialização/descoberta de classes. O primeiro exemplo (`76–100`) atravessa LaunchClassLoader e detecção de lado do Mixin. Não há evidência para atribuir todos ao Mirai; o cliente prossegue. Devem ser tratados separadamente por stack raiz, e não somados como 299 crashes.
- `GPU culling unavailable (MULTI_DRAW_NOT_INDIRECT)` aparece duas vezes (`18764`, `20043`). Indica fallback do renderer que emitiu a mensagem; não prova incompatibilidade com todo OpenGL moderno nem erro fatal.
- Os contadores Mirai `errors_*`, `DB_FAILED`, `GL_UPLOAD_JAVA_FAILURES` são zero. Isso **não captura** os WARN de ID map nem os crashes externos acima. As contagens representam somente os pontos instrumentados.
- Nas sessões 0.1.6, `REMOTE_FAILED` acompanha quase integralmente `NETWORK_CANCELLED`: previous-1, 3.153/3.153; previous-2, 1.880/1.877 e três `NETWORK_QUEUE_CLOSED`. Grandes blocos estão próximos a troca/unload de mundo. Não se deve rotular cancelamento de lifecycle como corrupção ou perda de rede.
- Na 0.1.7 todas as operações de rede Mirai são zero; `support=NONE` e filas diferidas crescem até 910 (`2030`). Essa fila por si só não é prova de vazamento, pois é uma conexão sem suporte e a política tem limite. Não se deve comparar throughput remoto entre essas sessões.

## Próximo teste necessário

Usar o mesmo modpack, configuração visual, mundo/rota e runtime para comparar o artefato original e corrigido, primeiro em uma área já carregada e depois percorrendo terreno. Repetir com marcações de início/fim, incluir trace curto do Mirai durante a queda e log completo do jogo. Separar rodadas de F3/troca de dimensão e crashes das rodadas de frametime. Registrar foco/minimização, VSync/cap, resolução, shaderpack e versão exata de CubicChunks/EntityCulling. Se persistir o intervalo fora do render event, recolher perfil de CPU/GC/presentação adequado em uma rodada separada.

Não é necessário degradar SSAO, transparência, distância, neblina, mipmaps ou qualidade de malha para validar as correções de redundância. Sem essa rodada no jogo, o resultado responsável é “defeitos reproduzidos corrigidos e testes de regressão aprovados”, não “todo problema de FPS resolvido”.

## Reprodução da análise

Executar `py -3 analyze_logs.py` e `py -3 inspect_windows.py` nesta pasta. Os scripts usam apenas biblioteca padrão Python e os três logs Mirai originais. Saídas: `telemetry-summary.json`, `timing-windows.csv`, `windows-context.json`. Os registros preservam linha/UTC para auditoria. Somas de contadores de eventos são deltas por janela; `queue` é tamanho observado e `dropped_total` é acumulado, cujos somatórios não devem ser interpretados como eventos. A classificação mundo/GUI em `windows-context.json` é uma observação contextual por emissão, não rótulo exato de cada frame.

Os relatórios não reproduzem endereços de servidor, contas, chat, credenciais ou argumentos sensíveis do jogo. `log.txt` original deve continuar sendo tratado como arquivo privado.
