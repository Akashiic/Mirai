# Captura administrativa de terreno residente — Akashic 0.1.5-dev

Esta versão inclui uma varredura opcional para reenviar ao processamento normal do DH os chunks que **já estão carregados** em uma dimensão ativa. Ela permite revisar o cache da área residente sem um jogador percorrendo cada chunk. Não lê chunks descarregados dos arquivos de região, não mantém chunks carregados, não cria terreno e não preenche automaticamente toda uma área que só existe no disco.

## Comandos

A raiz `/dh` exige o nível administrativo 4 do Minecraft. Em um híbrido, o encaminhamento/permissões do servidor também precisa respeitar esse contrato.

| Comando | Semântica |
|---|---|
| `/dh prepare loaded <dimensionId> <blockX> <blockZ> <chunkRadius>` | Inicia uma passagem sobre um quadrado centrado no chunk que contém as coordenadas de bloco. Raio inclusivo de 0 a 64 chunks; largura `2 × raio + 1`. A dimensão precisa estar carregada e habilitada para atualizações DH. |
| `/dh prepare status` | Exibe o trabalho atual ou o resultado da última passagem. |
| `/dh prepare stop` | Cancela as próximas submissões. Os dados já entregues ao pipeline normal do DH seguem seu ciclo de vida normal. |

Exemplos de sintaxe: `/dh prepare loaded 0 0 0 8` para o Overworld; `/dh prepare loaded 1 0 0 8` para um The End que use o ID 1. Os exemplos não carregam a dimensão caso esteja inativa.

O resultado informa:

- `scanned`: coordenadas verificadas / total solicitado.
- `submitted`: chamadas realizadas à API normal de atualização do DH. **Não é confirmação de gravação, nem prova de cobertura completa.** Essa API não retorna um recibo de persistência; o pipeline ainda pode descartar dados inalterados ou rejeitados por suas próprias regras.
- `unloaded`: nenhum chunk residente encontrado. Nenhuma tentativa de carregar ou gerar é feita.
- `incomplete`: chunk vazio sentinela, identidade inesperada, população/iluminação incompleta ou array de biomas inválido.
- `alreadyUpdating`: a posição já estava em processamento, ou o gerenciador necessário não estava disponível; nenhuma duplicação é forçada.

Um chunk real, totalmente populado e iluminado, composto de ar é aceito. O vazio legítimo do The End não é tratado como dados ausentes.

## Limites e ciclo de vida

- Uma única passagem ativa por servidor; no máximo 16.641 coordenadas, sem coleção de chunks retidos.
- Uma tarefa pendente no `ServerThreadTaskHandler` existente. A política de saúde desse handler continua valendo.
- No máximo uma submissão a cada cinco ticks de servidor (quatro por segundo a 20 TPS), 32 consultas de coordenadas por tarefa e orçamento de varredura de 2 ms entre operações. Uma captura individual não pode ser interrompida no meio; os 2 ms não são uma garantia de latência máxima.
- Todas as consultas à dimensão, ao mapa residente e à construção do wrapper acontecem na thread proprietária do servidor. A conversão posterior usa o pipeline existente de iluminação/LOD/persistência.
- A captura conserva o adaptador de biomas existente, que usa o mundo para respeitar dimensões e mods como LOTR.
- Não há alteração dos limites de renderização, qualidade visual, número de workers, configuração de geração, simulação, arquivos do mundo ou formato do cache.
- Troca de sessão, substituição do objeto de mundo ou do nível DH, exclusão da dimensão, modo somente leitura, descarregamento e encerramento cancelam a passagem.
- A tarefa adiada usa referência fraca ao trabalho; cancelá-la não mantém o mundo antigo preso na fila durante períodos de baixa saúde do servidor.
- Falha de captura ou rejeição da fila termina a passagem, com status de falha e log. Não existem retries infinitos ou uma nova execução automática.

A passagem é intencionalmente única: chunks que carregarem depois de sua coordenada ter sido examinada dependem dos eventos normais de carregamento/salvamento ou de uma nova execução explícita.

## Preparação de terreno descarregado: pendência concreta

A conversão genérica de chunks existentes **somente no disco** não foi incluída nesta candidata. A inspeção do Forge 1.7.10 build 1614 e das classes locais mostrou:

1. `InternalServerGenerator` usa `ChunkProviderServer.loadChunk` e carrega vizinhos 3 × 3. Esse caminho pode gerar/popular terreno.
2. `AnvilChunkLoader.loadChunk` chama também `loadEntities`, incluindo restauração de ticks; não é uma consulta neutra.
3. A variante `loadChunk__Async` separa blocos de entidades, mas sua leitura usa `RegionFileCache`, que pode criar arquivos ausentes, e a recuperação de coordenadas incorretas pode modificar NBT de uma gravação pendente. Exige um contrato específico de leitura e validação antes de uso.
4. Substituir a consulta de biomas do mundo pelo array vanilla salvo ignoraria os adaptadores deliberadamente preservados para dimensões de mods. Não é uma equivalência de aparência demonstrada.

Uma futura importação precisa definir um snapshot de leitura sem ativação/geração, validar identidade, biomas e luz e demonstrar compatibilidade do provedor. Esta candidata não disfarça esse trabalho como uma simples opção de pré-geração.

## Verificação executada

`python tests/preparation/run_loaded_prepare_probe.py` compila as classes de produção de preparação/comando para Java 8 e executa 21 casos em Java 8 e Java 21 com limites de plataforma controlados. Cobrem ausência de geração, ar real, coordenadas negativas, entradas incompletas, identidade, trabalho duplicado, cancelamento, substituição de mundo/nível/sessão, encerramento, rejeição, exceção, limites e comando alcançável.

As duas classes também foram compiladas contra a API MCP real do Forge 1614 e as classes DH empacotadas anteriormente. Isso verifica ligações de compilação; não constitui execução de Minecraft ou Crucible. O JAR final precisa passar adicionalmente pela build/reobfuscação e inspeção de seus símbolos.

Não foi executado um servidor Crucible real nesta etapa. O teste de aceitação no servidor deve observar a área já carregada, conferir os contadores e confirmar que não aumenta o conjunto de chunks carregados ou gerados por iniciativa deste comando.
