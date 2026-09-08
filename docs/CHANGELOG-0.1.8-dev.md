# 0.1.8-dev — correções de dados vazios e trabalho gráfico redundante

## Dados LOD

O transformador reconhece `EMPTY_DATA_POINT` antes de consultar o mapa de blocos. O decoder de uma leitura parcial preenche posições não lidas com essa sentinela; consultá-la como ID zero gerava o aviso de mapa fora de sincronia. Blocos reais cujo ID é zero continuam processados, e IDs inválidos em dados não vazios continuam denunciados. O patch não apaga cache nem transforma corrupção real em dado válido.

## Buffers gráficos

O primeiro upload `BUFFER_STORAGE` reutiliza o objeto GL recém-criado. Os uploads seguintes continuam recriando armazenamento imutável; falhas parciais são tratadas conservadoramente. Ao criar um novo ID, seu tamanho é zerado, evitando reutilizar a capacidade do objeto anterior em uma troca para `SUB_DATA`. Restauração de bindings, método de upload escolhido e bytes de geometria permanecem no caminho existente.

## Nuvens

O culling reutiliza três vetores temporários do próprio handler, eliminando a criação de 12 vetores por chamada não central. A ordem dos cálculos, normalização, distâncias e decisão de visibilidade é preservada. O vetor fornecido pelo wrapper é copiado antes de normalizar. Não há redução de camadas, distância ou mudança da posição/cor das nuvens.

## Fila de renderização

A manutenção mantém no máximo um callback pendente e verifica novamente se a renderização normal já retomou. A execução usa o orçamento normal, em vez de uma autorização fixa de 500 ms. Uma rejeição transitória de agendamento não encerra o timer. A política normal continua metade do frame configurado, calculada diretamente em nanossegundos; uma tarefa nativa indivisível ainda pode exceder esse tempo.

A média de diagnóstico de cada tarefa passa a usar a duração da própria tarefa, em vez do tempo acumulado da fila. A captura da pilha de criação deixa de ocorrer automaticamente só porque a versão contém `dev`. Para uma investigação que precise dessas pilhas, existe `-Dakashic.mirai.renderTaskStacks=true`; o diagnóstico automático e `/miraidiag trace` continuam disponíveis.

## Limites

Esta candidata não é um backport de Voxy nem uma troca do renderer. As técnicas comparadas e a compatibilidade dos mods estão documentadas na auditoria. O caminho atual do Voxy exige outra plataforma e tem licença restritiva; nenhum código Voxy foi incorporado.

O log geral também mostra falhas nas integrações CoreTweaks/CubicChunks e no overlay CubicChunks. Esta build não altera esses projetos. Uma correção local do Mirai não comprova que toda a queda de FPS do modpack foi resolvida. A homologação no jogo requer o mesmo percurso, configurações, versões dos demais mods e condições equivalentes de cache.
