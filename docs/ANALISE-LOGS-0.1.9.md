# Auditoria independente da coleta 0.1.9

8 de setembro de 2026. Escopo: snapshots congelados de `previous-1.log`, `latest.log` e a entrada única `latest.log` de `latest.zip`, copiada como `input/minecraft-latest.log`. Nenhum arquivo de produção foi alterado por esta auditoria. Identificadores de sessão, objetos, jogadores e servidores foram excluídos dos resultados distribuíveis.

**Conclusão:** a 0.1.9 produziu medições GPU úteis e associáveis à origem, mas ainda há baixa frequência de quadros, uma falha real no tick de câmera e uma transição de mundo com intervalo de 6,098 s sem explicação completa. Os dados não certificam estabilidade nem autorizam reduzir efeitos visuais. SSAO e AA merecem investigação de custo preservando imagem; seus tempos não devem ser confundidos com ocupação exclusiva da GPU.

## Integridade, sessão e reprodução

| Entrada | Bytes | SHA-256 |
|---|---:|---|
| `previous-1.log` | 4.194.116 | `b264009139bb10ae88e5e60c21357b4bf7ccfcdc652e8a08ecec8e9aef86bf15` |
| `latest.log` | 943.768 | `c59c445f31de5c957535824f6f37f79710b5b1d29ca5bb9af6ba485170c4691e` |
| `latest.zip` | 121.942 | `9626e05bfa0d43762264e5369b45616d273b3282a5a762b35562be85a4a6e1c1` |
| `minecraft-latest.log` | 2.513.392 | `e6f320c9e7f6096845cf1dc6415701d031dc72bbb4d92cb310f82157b8cec50f` |

O ZIP foi lido pela entrada exata `latest.log`, após verificar inventário de uma entrada e tamanho esperado, sem extração genérica. Os dois JSONL são segmentos da **mesma sessão**, de `16:06:39.243Z` a `17:01:27.564Z` (54 min 48,321 s), somando 20.932 linhas. O segundo cabeçalho é consequência da rotação, não uma segunda execução. Há 651 janelas de contadores e 559 eventos GPU. O analisador padrão aceitou todos: zero JSON inválido, zero duplicatas, zero conflitos e zero amostras rejeitadas.

Reprodução, a partir da pasta desta auditoria, ajustando somente o caminho da cópia do projeto:

```powershell
python ../akashic-mirai/scripts/analyze-mirai-gpu.py input/previous-1.log input/latest.log --out evidence/gpu-standard-analysis-sanitized.json
python evidence/audit_rotated_gpu_session.py --analyzer ../akashic-mirai/scripts/analyze-mirai-gpu.py
```

O segundo script verifica o ZIP, reproduz os pareamentos e gera os resumos sanitizados, `gpu-paired-samples-sanitized.csv` e `gpu-pairsets-sanitized.json`. O CSV contém a linha de cada amostra, captura, sequência, origem, publicação, contexto usado e todos os tempos. Cada conjunto no JSON enumera seus membros exatos. `context-findings-sanitized.json` reproduz filas, deferred/rede e periodicidade externa. Arquivos brutos permanecem somente em `input`.

## Ambiente e limites de comparação

`previous-1.log:456–458` declara `0.1.9-dev`, commit `fa91834906f7d643b283e42ce2c5113e1dbdc2d2`. `latest.log:15–17` repete a identidade. Isso identifica o build declarado pelo runtime, não o SHA binário do JAR instalado.

Java continua Oracle `1.8.0_51`. GPU é Intel UHD, OpenGL `4.6.0 - Build 27.20.100.9268`, LWJGL 2.9.1 (`previous-1:1002–1007`). O caminho de consultas foi identificado como OpenGL33, 64 bits, suportado (`11963–11965` e `latest:1092–1094`). Angelica 2.2.8, Hodgepodge 2.7.189 e CoreTweaks 0.3.4.7-GTNH permanecem declarados; **CubicChunks mudou para dev.6** (`previous-1:499`), contra dev.5 na coleta 0.1.8.

Raio LOD 128, qualidades HIGH, resolução BLOCK, transparência COMPLETE, SSAO, AA, fog e fade permanecem habilitados (`previous-1:681–701`). Nas 559 amostras GPU, o último cap registrado à origem era 60, resolução 1920×1001 e shaderpack inativo. Entretanto, os snapshots associados a 244 amostras indicam display inativo com GUI aberta; outras 27 têm GUI aberta com display ativo. Configuração de cap e VSync não garante foco ou pacing efetivo. A sessão inteira alternou cap 10/60, além de mensagens de pacing 30/60/10 no log normal.

Não é comparação A/B com 0.1.8: mudaram cenas, duração, atividades, foco, CubicChunks e a instrumentação. A resolução de cada frame da coleta antiga não estava disponível para comparação equivalente.

## Pareamento GPU: o número 29,5 ms não é o grupo focado

Há duas capturas solicitadas por 60 s: captura 1 em `previous-1:11962–12652`, captura 2 em `latest:1091–1740`. Todas as amostras são do passe `main`; não houve `deferred`. O nome de estágio FOG ou AA descreve o intervalo de conclusão entre seus timestamps, não utilização exclusiva ou trabalho isolado do hardware.

| População exata | Amostras | PASS médio | CPU wall pareado médio |
|---|---:|---:|---:|
| Todas as invocações | 559 | 29,486 ms | 7,548 ms |
| Invocações com estágio LOD | 551 | 29,914 ms | 7,656 ms |
| LOD com último snapshot focado à origem | 280 | 28,044 ms | 6,938 ms |
| Captura 1, LOD focado à origem | 212 | 27,337 ms | 6,194 ms |
| Captura 2, LOD focado à origem | 68 | 30,249 ms | 9,259 ms |

O grupo focado exige `in_world=true`, `gui_open=false`, `game_focus=true`, `display_active=true` no último snapshot **anterior a capture_start_elapsed_ms**. A idade máxima de contexto entre todas as amostras é 5.008 ms. Exigir adicionalmente idade ≤10 s e nenhuma troca de mundo desde esse snapshot preserva os mesmos 280 membros. Isso melhora a classificação, mas continua sendo contexto periódico: não prova câmera imóvel, foco invariável entre snapshots ou cena constante.

| Estágio | Todas as 551 LOD: média | 280 LOD focadas: média | 280 focadas: p95 |
|---|---:|---:|---:|
| PASS | 29,914 ms | 28,044 ms | 39,726 ms |
| OPAQUE | 3,485 ms | 3,302 ms | 6,921 ms |
| SSAO | 8,952 ms | 7,834 ms | 13,582 ms |
| AA | 7,318 ms | 7,121 ms | 9,394 ms |
| FAR_FADE | 4,396 ms | 4,220 ms | 5,925 ms |
| FOG | 3,235 ms | 3,200 ms | 4,246 ms |
| COMPOSITE | 1,299 ms | 1,139 ms | 1,482 ms |
| GENERIC | 1,134 ms | 1,162 ms | 3,093 ms |
| TRANSPARENT | 0,069 ms | 0,042 ms | 0,180 ms |

TASKS e GENERIC_SSAO têm médias menores que 0,002 ms nos grupos LOD. As seis médias alegadas aproximadamente como SSAO 10,7 + AA 8 + opaque 4,7 + fade 4,5 + fog 3,5 + composite 1,3 somam 32,7 ms; não reproduzem a população focada definida acima. Sem os membros usados para cada valor, não se deve combiná-las com PASS 29,5 ms.

Há um exemplo verificável de mistura de populações: a média PASS 29,486 ms usa 559 amostras; as médias dos estágios LOD usam 551. Oito invocações após troca de mundo (`latest:1269–1276`) contêm somente PASS/TASKS. Comparar a soma das médias dos estágios presentes com a média de todas as invocações faz os estágios parecerem ultrapassar PASS. Usando **as mesmas 551 linhas**, a soma dos dez subestágios observados é 29,891821 ms, contra PASS 29,913561 ms; o restante médio é 0,021740 ms. Nenhuma das 559 amostras tem soma observada maior que seu PASS. Essa verificação aplica-se aos hooks sequenciais desta coleta; não torna quaisquer timers sobrepostos universalmente aditivos.

O máximo PASS observado é 79,056587 ms; o máximo no grupo focado é 46,715554 ms. P95 usa nearest rank das amostras individuais, não buckets de resumo nem médias de percentis. Ausência de um estágio não foi convertida em zero.

## Origem, atraso de leitura e frequência de quadros

O atraso de readback tem média 181,561 ms e máximo 733,641 ms. Usar o contexto da publicação muda a classificação de três amostras (`previous-1:12003,12471; latest:1333`) e resulta em 279 LOD focadas, contra 280 à origem. A nova origem e os IDs de captura/sequência corrigem uma limitação da primeira proposta de instrumentação.

Há amostragem a cada quatro invocações por tipo de passe. Isso não autoriza multiplicar amostras por quatro e anunciar FPS: podem existir capturas encerradas com resultados pendentes, transições e invocações sem desenho. Os contadores GPU_QUERY_FAILURES e GPU_SAMPLES_DROPPED ficaram em zero; o encerramento informa que resultados pendentes não lidos são descartados, então esses zeros não significam cobertura de todos os frames.

Para uma medida independente de frequência, foram selecionadas somente janelas de resumo inteiramente dentro do intervalo entre primeira e última origem de cada captura: 11 janelas em cada. Na primeira, 1.136 intervalos têm média 48,900 ms, inverso aproximado **20,45 quadros/s**. Na segunda, 958 intervalos têm média 58,516 ms, inverso **17,09 quadros/s**. Esses conjuntos incluem contextos de foco diferentes e a segunda transição descrita abaixo. A janela `previous-1:12604–12622`, por exemplo, tem média 68,908 ms, aproximadamente 14,51 quadros/s, com GUI/display inativo no contexto correspondente. Não representa desempenho de gameplay focado.

FRAME_INTERVAL, FRAME_EVENT_SPAN, CLIENT_RENDER_PASS_WALL e GPU PASS não são parcelas independentes. CPU wall inclui esperas e pausas; timestamps GPU podem incluir starvation do fluxo de comandos. Nem CPU+GPU nem FRAME_INTERVAL−CLIENT_RENDER demonstram tempo exclusivo de GPU. Esta coleta localiza intervalos caros, mas não mede GPU busy, o frame GPU inteiro ou o responsável por todo o tempo restante.

## O intervalo de 6,098 s e a troca de mundo

`latest.log:1286` registra FRAME_INTERVAL máximo **6.098,305 ms**, média 128,430 ms em 65 amostras. O resumo está em `1285`, elapsed 2.935.746 ms. Na mesma janela: máximo CLIENT_RENDER 74,738 ms (`1292`), fila 0,789 ms (`1294`), tarefa 0,090 ms (`1295`), upload DATA 0,036 ms (`1297`), delta de GC zero (`1303`). A janela anterior registra 86 ms adicionais de GC (`1255`). Assim, nem os tempos Mirai mostrados nem o GC reportado explicam seis segundos; também não existe medição GPU contínua desse vazio.

Os eventos colocam a anomalia numa transição: load dimensão −1 em `1235`, elapsed 2.927.477; load dimensão 0 em `1258`, elapsed 2.933.414. São 5,937 s entre esses eventos. As origens GPU vizinhas são 2.927.298 (`1234`) e 2.933.518 (`1269`): intervalo sem amostras de 6,220 s. A primeira invocação posterior é uma das oito sem estágio LOD.

A captura 2, portanto, não é uma cena única: 43 amostras LOD antes da transição têm PASS médio 37,006 ms; 207 depois têm 27,917 ms; outras oito não têm LOD. A troca também reinicia os contadores dos pools observados. O log normal registra `ClosedChannelException` do JourneyMap em sua thread de IO às 13:55:26 (`minecraft-latest:22793–22797`), no entorno da transição. É outra falha real, mas não prova a causa do bloqueio do cliente. Faltam pilhas contemporâneas do cliente e instrumentação do percurso de troca de mundo para atribuir os seis segundos.

## Falha Mirai de câmera, capturada e não fatal

`previous-1.log:5807` e `minecraft-latest.log:20132–20141` registram NullPointerException em `Camera.update:32`, chamado por `MinecraftRenderWrapper.getCameraExactPosition:272` → `ClientLevelModule.clientTick:117`, em `DH-ClientTickTimer`. Os contadores da janela `previous-1:5808` confirmam **DH_ERROR_REPORTS=1 e errors_DH_LOG=1**. Portanto uma conclusão de “todos os erros Mirai zerados” seria incorreta.

A captura da exceção em DhClientLevel impede que essa ocorrência mate o Timer; o restante daquele tick pode ser interrompido. A correção deve preservar a matemática da câmera e usar estado válido/coerente no lifecycle, sem inventar origem de mundo quando entidade/câmera estiver ausente. A análise de logs não certifica o conserto: isso depende de revisão e testes específicos da próxima implementação.

## Filas, sincronização adiada e handoff

**Render_Load versus LOD_Builder:** Render_Load chega a 168 itens em `latest:1220`, com três tarefas em execução; o Builder do mesmo snapshot (`1219`) está em zero. O único Builder com fila 1 é `1169`, quando Render_Load está em 141 (`1170`). Portanto “168 contra 1” não é um par do mesmo instante. Globalmente, Builder tem fila zero em 456/457 snapshots; Render_Load, em 409/457. Render_Load alcança **≥256 em 27 snapshots**, inclusive `latest:1325,1383`, após o reload. `>=256` é limite inferior do observador, não tamanho exato.

Isso sustenta pressão no carregamento de render em trechos específicos, mas não demonstra redundância, starvation, execução duplicada ou que aumentar threads resolveria FPS. As tarefas têm funções e durações distintas. `average_task_wall_ms` é média móvel das últimas 200 tarefas concluídas, e não custo instantâneo da janela; NaN significa ausência de amostras. Código de referência: `PriorityTaskPicker` e observador da versão 0.1.9.

**sync_deferred:** máximo global é 1.111 (`previous-1:15276`), não 1.091. O patamar 1.091 vai de `latest:2058` a `3690`, 256,733 s entre snapshots extremos. Todos os **434 snapshots com deferred positivo** coincidem com `support=NONE`, `ready=false`, sessão aberta e `sync_waiting=0/sync_in_progress=0`. Nenhum backlog positivo foi observado com ready=true. A versão 0.1.9 exige readiness, configuração, capacidade e identidade do level para admissão. Esses dados são compatíveis com intenções aguardando servidor apto; não provam deadlock ou falha do dreno. SYNC_DEFERRED_OVERFLOW ficou em zero.

**Handoff:** há 434 snapshots configurado/recebido/efetivo=4/4/2; outros 22 são 4/−1/0, mais um estado inicial 0/−1/0. O 4→2 corresponde à sobreposição conservadora de dois chunks já prevista por `VanillaChunkCoverage`; recebido−1 é cobertura desconhecida. Cobertura recebida não prova malha vanilla concluída. Os 452 snapshots de level registram render_state_active=true e ignored_dimension=false, mas isso não certifica ausência de corrida de identidade entre snapshots.

**Patcher externo:** o log normal contém 468 mensagens `remote channel register`, entre linhas 18790 e 23040, em 2.333 s. A mediana entre mensagens é 5 s: 343 intervalos de 5 s e 101 de 6 s. Há cinco `join game/backend switch` explícitos. O padrão periódico justifica investigação do emissor externo caso seja necessário; quantidade de mensagens não mede seu CPU nem permite culpar esse polling pelo GPU PASS ou eliminar notificações de lifecycle.

## Outros sinais e prioridades sustentadas

- Quatro avisos LOD de ícone inutilizável para OreQuartz/OreQuartzCharged (`minecraft-latest:21433–21434,22752–22753`) persistem. São alvos restritos de compatibilidade/material; sem imagens não está demonstrado o efeito visual exato.
- Há erros de integração NEI/ImmersiveEngineering (`18831`) e NEI/BigReactors (`18904`), além do script MineTweaker em três pontos. Não são automaticamente falhas Mirai.
- Não foram encontradas novas mensagens FullData ID-map, crashes CoreTweaks/WorldDiagnostics, ArrayIndexOutOfBoundsException, OutOfMemoryError, SQLITE_CORRUPT, database locked ou GL_INVALID. Ausência nesta execução não certifica correção, sobretudo com CubicChunks alterado.
- O GC acumulado aumenta 13.769 ms entre os primeiros/últimos snapshots JVM; o maior delta é 2.674 ms próximo do encerramento (`latest:3712`). Não é ocupação exclusiva de pausas do cliente. RAM física livre mínima observada é 586.342.400 bytes (`previous-1:13952`), sem prova de que Mirai causou a pressão.

A ordem de trabalho sustentada é: corrigir e testar o lifecycle de câmera sem mudar sua matemática; investigar a transição de mundo separadamente do custo GPU; medir e otimizar os passes caros somente com equivalência visual; aprofundar Render_Load com evidência por tarefa antes de mudar o scheduler. A telemetria nova é um avanço verificável. Estabilidade, ausência de efeitos colaterais e FPS resolvido continuam pendentes de validação da próxima versão no ambiente real.
