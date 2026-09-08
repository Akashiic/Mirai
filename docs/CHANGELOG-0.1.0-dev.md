# Alterações — 0.1.0-dev

Esta é uma linha de desenvolvimento sobre Distant Horizons 3.2.1-b-dev. A lista descreve alterações nos fontes; consulte o relatório de execução para saber quais foram compiladas e exercitadas. Não é uma declaração de estabilidade em Minecraft ou Crucible.

## Identidade e controle da distribuição

- Nome público **Akashic: Horizons of The Mirai**, versão `0.1.0-dev`, créditos do fork e dos autores originais.
- `modid`, namespaces, versão da API, protocolo lógico e nome do arquivo de configuração preservados.
- Atualizador do upstream bloqueado nos pontos públicos de consulta, instalação e fechamento. O fork não deve ser substituído automaticamente por uma build Distant Horizons.
- Identificadores de commit e branch corrigidos no recurso de build; checksum do Gradle wrapper e versão de Forgix fixados.
- Inclusão dos artefatos `forge17`/`forge112` no coletor de CI existente.
- Títulos de interface ajustados e tradução inicial de controles em português brasileiro. A tradução completa não foi refeita.

## Integração e renderização

- Registro de accessors anterior aos consumidores no Forge 1.7.10, idempotência, aliases compartilhados e separação de cliente físico.
- Diagnóstico de capacidade do Hodgepodge sem habilitar configuração experimental de terceiros.
- Consulta RPLE depois da inicialização, evitando captura nula durante a construção antecipada do renderer vanilla.
- Evento de fog encaminhado exatamente uma vez, retorno de cancelamento preservado e intervenção visual mantida no passe de terreno.
- Liberação do buffer UTF-8 do upload de shaders LWJGL2 em `finally`.

## Chunks e concorrência

- Posse por solicitação de geração, registrando apenas referências efetivamente adquiridas.
- Cancelamento anterior à aquisição impede retenção tardia; fechamento duplicado não libera referências alheias.
- Supressão de simulação rastreada separadamente, evitando `allow` sem nosso `prevent` correspondente.
- Falhas de cleanup agregadas sem pular os demais recursos; alteração de marcações de ignore respeita a thread proprietária do mundo.

## Transporte e dados

- Whitelist de tipos por direção antes de construir mensagens/DTOs.
- Frame legado validado, encoder limitado, rejeição de bytes excedentes e separação do callback por lado.
- Fila serverbound limitada e processada no tick; limites por conexão, globais da fila, expiração e orçamento de trabalho.
- Ingress do cliente limitado por mensagens/bytes e vinculado ao mundo/sessão de origem; descarte e encerramento liberam buffers.
- Handshake emitido após a publicação do mundo e preparação do executor.
- Arrays, dados descomprimidos, colunas e mapas validados antes de alocações ou crescimento proporcional ao tamanho declarado.
- Remontagem limitada por transferência/sessão/IDs, prazo absoluto e número de fragmentos; cleanup de buffers em erro, expiração e encerramento.
- Fragmentos de saída de 30 KiB; fila de envio limitada, views com referências próprias, snapshots de saída independentes e remoção dos emissores inativos/fechados da divisão de banda.
- Orçamento finito de envio por tick, aritmética ampliada antes da multiplicação e tratamento de falha de notificação de fechamento.

## Ciclo de vida

- Fechamento explícito de sessão e gerenciamento das tarefas pendentes dos jogadores.
- Saída de jogador libera o estado mesmo quando seu nível já não está disponível.
- Fechamento de mundos tenta os demais níveis após falhas e completa futures excepcionalmente, evitando espera infinita causada por exceção da tarefa.
- Invalidação e encerramento dos recursos compartilhados também precisam ocorrer quando o fechamento do mundo anterior falha; o mundo seguinte não pode herdar esse estado quebrado.

## Padrões de homologação

- Configuração nova: raio LOD 64, até dois workers configurados e razão de atividade 0,5.
- Geração distante desativada por padrão. Dedicado 1.7.10 exige também `-Dakashic.mirai.experimentalServerGeneration=true` para remover a restrição experimental; essa propriedade não ativa sozinha a configuração de geração.
- LWJGL2 mantido; nenhuma dependência obrigatória de novo companion Bukkit criada.
- Modo remoto exige Mirai correspondente nos dois lados. Preservar o envelope legado não homologa interoperabilidade com fragmentos antigos de 1 MiB do DH upstream.

## Limites de aceitação

O conjunto não importa automaticamente as novidades do `main` moderno. Paridade visual completa, todas as combinações de shaders, desempenho no hardware do usuário, compatibilidade com cada mod do pack, recuperação de todos os caches antigos e estabilidade prolongada no Crucible continuam exigindo homologação.

O ZIP do pack foi localizado, mas não materializou nesta sessão. As versões do inventário vieram do log de runtime e não receberam SHA-256 por JAR. O log também contém um crash anterior do Sampler em OpenGL; ele não pode ser apresentado como regressão causada por esta build que ainda está em desenvolvimento.
