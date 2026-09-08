# Diagnóstico local do Akashic Mirai — 0.1.9-dev

O registro básico começa automaticamente em `logs/akashic-mirai/latest.log`, dentro da instalação do Minecraft ou da pasta do servidor. Cada processo tem sua própria sessão. Não é necessário habilitar DEBUG global ou instalar outro mod.

## Instalar e registrar um teste

1. Feche o cliente/servidor e substitua o JAR Akashic anterior na pasta `mods`. Use o mesmo JAR no cliente e no Crucible. Guarde a versão anterior fora de `mods`; não instale duas versões de Distant Horizons juntas.
2. Mantenha configurações, mundos e cache. Entre no mesmo cenário onde viu o problema.
3. No cliente, use `/miraidiag status`. O estado esperado após a inicialização é `recording`.
4. Quando precisar investigar uma falha, use `/miraidiag trace 60`. A coleta detalhada dura 60 segundos e encerra automaticamente; o registro básico continua.
5. Perto do sintoma, use `/miraidiag mark sky_white` (ou `fps_drop`, `lod_gap`, `dimension_change`). O retorno contém um número de marcador para relacionar com o vídeo. Use letras, números, `_` ou `-`, até 32 caracteres.
6. Saia normalmente e envie a pasta `logs/akashic-mirai` do teste, o `logs/latest.log` normal da mesma sessão e o vídeo/screenshot relevante. Se reiniciar, preserve também os arquivos `previous-*.log`, pois o teste pode estar neles.

No console do Crucible, use `dh diagnostics status`, `dh diagnostics trace 60` e `dh diagnostics mark fps_drop`. Dentro do jogo, o equivalente `/dh diagnostics ...` mantém a permissão administrativa do comando DH. Esse comando registra o servidor; não ativa a amostragem do cliente. `/miraidiag` é um comando exclusivamente local do cliente, inclusive ao entrar por proxy em um lobby sem DH.

`stop` encerra trace e GPU antecipadamente. `trace` aceita de 10 a 120 segundos; sem duração, usa 60. `mark` sem rótulo usa `manual`. A confirmação de um marcador informa sua entrada na fila, não garante persistência após encerramento abrupto ou falha de disco.

Na 0.1.9, `/miraidiag gpu 60` solicita captura GPU temporária no cliente. É opcional, desativada por padrão e limitada a 10–120 segundos. `/miraidiag gpu stop` encerra somente GPU. Feche o chat depois do comando para observar o jogo em foco. A resposta inicial confirma o pedido; `gpu_capture` no log informa ativação efetiva ou indisponibilidade. Veja [escopos, limitações, etapas e teste comparativo](PLANO-E-DIAGNOSTICO-0.1.9.md). A captura não mede o frame inteiro nem muda qualidade gráfica.

## O que o arquivo informa

| Área | Observações incluídas |
|---|---|
| Identidade e ambiente | Versão/commit/branch, Java, SO, arquitetura, processadores disponíveis, até 450 IDs/versões de mods e contagem de omissões |
| Gráficos | Fabricante e renderizador OpenGL, versão/driver reportada, LWJGL, capacidades selecionadas, shaderpack ativo e configurações efetivas incluídas na lista permitida |
| Cliente | Intervalos entre frames, duração do evento de renderização, tempo decorrido nas chamadas de render do LOD, estado de renderização, cobertura já calculada e seleção de buffers do último passe |
| Servidor | Duração entre eventos de tick, até oito mundos carregados, contagens residentes e ciclo de vida das dimensões |
| Trabalho e memória | Filas de executores observadas até 256 entradas (`>=256` quando atinge o limite), tarefas em execução/concluídas, tempo médio de tarefa, heap, GC, pools de buffers e métricas opcionais da JVM/SO |
| Rede e cache | Suporte negociado, limites de sincronização, cache inicial versus atualização, solicitações e desfechos, bytes de fragmentos recebidos, recusas/limites/cancelamentos, gravações e preservação de dados locais |
| Erros | Contagem de relatos e amostras limitadas de tipos/causas/pilhas, sem copiar mensagens arbitrárias das exceções |
| Trace temporário | Pilhas das threads principais observadas, estado da thread, CPU cumulativa quando já disponível, marcadores e decisões individuais sobre seções |

Os resumos têm intervalo alvo de cinco segundos. Os tempos de parede incluem esperas: tempo de render LOD não equivale a tempo da GPU, e tempo de uma solicitação não equivale à latência pura da rede. O `p95_bucket_ms` é o limite do intervalo do histograma, não um percentil exato. As contagens de buffers não comprovam pixels corretos na tela; chamadas de render podem terminar sem desenhar.

### Medidas adicionais da 0.1.7

| Métrica | Fronteira observada |
|---|---|
| `CLIENT_RENDER_PASS_WALL` / `CLIENT_DEFERRED_PASS_WALL` | Passagem completa do cliente, incluindo preparação e processamento de tarefas que ocorram dentro dela, até em retorno antecipado. |
| `RENDER_TASK_QUEUE_WALL` / `RENDER_TASK_WALL` | Tempo de execução da fila e de uma tarefa individual, incluindo esperas nas chamadas feitas por elas. |
| `GL_STORAGE_UPLOAD_WALL` / `GL_DATA_UPLOAD_WALL` / `GL_SUB_DATA_UPLOAD_WALL` | Tentativas não vazias de upload pelo método resolvido. O campo `count` conta chamadas, não buffers residentes. |
| `GL_STORAGE_UPLOAD_BYTES` / `GL_DATA_UPLOAD_BYTES` / `GL_SUB_DATA_UPLOAD_BYTES` | Bytes tentados por método; não são VRAM alocada nem bytes comprovadamente transferidos pela GPU. |
| `GL_UPLOAD_JAVA_FAILURES` | Tentativas que saíram excepcionalmente pelo caminho Java. Não consulta o estado de erro do OpenGL. |
| `sync_deferred` / `SYNC_DEFERRED_OVERFLOW` | Posições aguardando admissão e descartes do conjunto limitado de intenções, respectivamente. |

Os tempos são inclusivos: uma chamada de upload pode estar dentro de uma tarefa, da fila e da passagem cliente. **Não some esses tempos.** Uma espera dentro do driver aparece como duração da chamada Java, sem identificar sua causa nem medir execução da GPU. A instrumentação não acrescenta `glFinish`, consultas GL ou gravação de arquivo ao caminho de upload.

Contadores são agregados por processo. Em servidor integrado, cliente e servidor compartilham esse agregado. Tokens de mundo relacionam eventos dentro da sessão; não são identificadores permanentes. Snapshots de filas são observações concorrentes, não uma fotografia transacional. Ausência de contagem não prova ausência de problemas fora dos pontos instrumentados.

Amostragem de pilhas é uma pista: encontrar uma classe do Starlight, Angelica ou outro mod em uma pilha não prova que ele consumiu determinada porcentagem da CPU nem causou a queda de FPS. Não há perfil completo por mod/worker, medição de tempo da GPU, VRAM, clocks, temperatura ou identificação garantida do modelo de CPU. Valores não disponíveis permanecem identificados como indisponíveis.

## Limites de custo e retenção

- Escrita e rotação ocorrem em uma thread dedicada. A fila aceita no máximo 512 registros, com texto limitado; descartes por saturação são contabilizados. Não há escrita de disco nos hooks de frame/chunk.
- Cada segmento tem até 4 MiB, com `latest.log` e quatro arquivos anteriores (aproximadamente 20 MiB). A rotação também ocorre ao iniciar uma sessão. Cabeçalhos e metadados limitados são repetidos para facilitar a leitura de segmentos isolados.
- Uma trava impede dois processos de disputar esses arquivos. Falha de permissão, diretório ou disco desliga o registro e informa o motivo no log normal, sem fechar o jogo por essa falha.
- Coleta básica não captura pilhas periodicamente. Trace consulta até 24 frames, no máximo quatro vezes por segundo por thread principal observada; não habilita o monitoramento de CPU da JVM.
- A coleta tem custo. O próprio snapshot cliente registra sua duração. Trace pode perturbar latências; não compare uma rodada com trace a outra sem trace como se fossem o mesmo ensaio de FPS.
- Encerramento normal tenta finalizar em até um segundo; interrupção abrupta ou disco travado pode perder o final. O arquivo normal do Minecraft continua necessário, inclusive para mensagens completas de exceções.

Os arquivos são locais, em JSON por linha, e não são enviados automaticamente. Não há captura de chat, credenciais, argumentos completos da JVM, endereço do servidor, nome do jogador ou nome do mundo. Trace pode conter posições codificadas das seções e nomes de classes; o inventário revela mods instalados. Revise arquivos antes de compartilhá-los publicamente.

Para um ensaio comparativo explícito, `-Dakashic.mirai.diagnostics=false` na JVM desativa esse registro naquela execução. Não é necessário usar essa opção para jogar ou enviar um diagnóstico.
