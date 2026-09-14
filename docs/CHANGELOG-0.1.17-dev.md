# Akashic Mirai 0.1.17-dev

## Escopo

`0.1.17-dev` parte exatamente do checkpoint `0.1.16-dev` (`716ba89d4eb35ca7c82de6b01d25b0655e13fa41`).

A telemetria da 0.1.16 identificou uma causa concreta de `OLD_PICKER_SHUTDOWN`: o refresh de beacons executava a consulta e depois mantinha um worker de IO ocupado em `Thread.sleep(2_000)` apenas para limitar a frequência da próxima atualização. Em transições reais, o restante desse sono apareceu dentro do shutdown por aproximadamente 586 ms e 1.077 ms.

Esta versão remove somente essa espera ociosa. Ela não reduz distância, qualidade, frequência lógica de refresh, filtros de beacon, segurança do shutdown ou trabalho necessário de consistência.

## Refresh não-bloqueante

- substitui o `sleep(2_000)` no worker por `BeaconRefreshScheduler` por `LodQuadTree`;
- preserva **2 segundos de intervalo após o término real do refresh**;
- não cria thread, timer, `ScheduledExecutorService` ou polling novo;
- mantém no máximo uma submissão pendente por árvore;
- durante o cooldown, ticks retornam sem alocar uma nova tarefa;
- uma submissão rejeitada libera imediatamente a reserva para retry;
- uma falha da tarefa não é engolida e não deixa a reserva presa;
- shutdown do owner impede consulta/publicação stale.

## Troca de picker / shutdown

Se um picker antigo fechar antes de iniciar a tarefa, a próxima tentativa pode revogar somente a submissão que **nunca começou**. Um runnable stale, caso apareça depois, não executa o refresh nem libera a reserva de uma submissão nova.

Uma consulta que já começou continua obedecendo ao contrato anterior: o shutdown espera seu término graciosamente e não a interrompe para ganhar tempo artificialmente.

## Gates adicionados

`BeaconRefreshSchedulerTest`: 16 casos cobrindo fronteira exata dos 2 s, tempo negativo/wrap de `nanoTime`, rejeição, falhas, close, concorrência, repetição de runnable e troca de executor.

`BeaconRefreshPickerIntegrationTest`: 2 integrações com o `PriorityTaskPicker` real. O probe local confirmou worker reutilizável em milissegundos e shutdown sem o cooldown ocioso; o segundo caso garante que trabalho realmente ativo continua sendo aguardado sem interrupt.

## Contrato visual

Nenhum shader, GLState, AngelicaAccessor, Config, SSAO, AA, fog, radius, geometria, scheduler global ou governor é alterado. O corpo de consulta/conversão/comparação dos beacons permanece igual, exceto fences de `closing` antes de trabalho/publicação.
