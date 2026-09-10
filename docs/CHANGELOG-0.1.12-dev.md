# 0.1.12-dev

Esta candidata parte do checkpoint limpo `0.1.11-dev` (`1484fc07025140f5e5a109c26eb4ed58ebc062de`) e trata defeitos confirmados pelos logs longos da 0.1.11. O escopo permanece conservador: não reduz distância, SSAO, AA, fog, geometria ou qualidade visual automaticamente.

- **Scheduler / starvation:** o `PriorityTaskPicker` não reserva mais o único slot global para uma tarefa que ficaria aguardando o `afterExecute()` throttled de outro executor. A reserva de worker agora cobre execução + intervalo de `threadRunTimeRatio`; ao fim do throttle o picker é acordado. Se um worker não estiver imediatamente disponível, a tarefa volta ao início de uma `ConcurrentLinkedDeque`, preservando FIFO dentro do executor. O ratio configurado não é removido nem reescrito.
- **Race de ocupação global:** o slot global passa a ser reservado antes do dispatch e é devolvido em toda falha de admissão. Um teste de 2.000 tarefas instantâneas exige drenagem completa e ocupação final zero.
- **Lost wakeup do picker:** uma submissão que perde o lock não é mais esquecida; ela deixa um pedido de retry durável para o passe atualmente dono do lock. Um teste concorrente força exatamente essa janela e exige que o trabalho recém-enfileirado execute sem depender de uma futura submissão externa.
- **Governor de runtime:** um guardrail client-side reduz somente a concorrência efetiva de background diante de pressão de heap, tempo de GC ou frametimes severos. Ele não persiste configuração e nunca aumenta acima do número de threads escolhido pelo usuário.
- **Recuperação do governor:** a concorrência retorna gradualmente, um slot por vez, após amostras saudáveis consecutivas; menu/alt-tab não é contado como frametime severo de gameplay.
- **Rollback A/B:** `-Dakashic.mirai.runtimeGovernor=false` desativa somente o governor e faz o limite efetivo voltar ao número de threads configurado.
- **Sync-on-load:** uma sessão de rede terminalmente fechada não conserva novos intents deferred nem deixa a fila crescer até `4096`. Handshake aberto porém ainda não pronto continua usando defer normalmente.
- **FullData lifecycle:** cancelamentos/rejeições de shutdown embrulhados em futures deixam de ser classificados como erros de persistência. O comportamento intencional de cancelar lotes ainda não iniciados durante close é preservado.
- **Chunk lifecycle:** trabalho de chunk pertencente a um `IDhClientLevel` já desassociado do wrapper é descartado antes do processamento caro e novamente antes da publicação, reduzindo publicação tardia após unload.
- **Telemetria:** `runtime_governor`, `scheduler_state`, `reserved=` por pool e o pool `Update_Propagator` eliminam o ponto cego observado na investigação de starvation.

## Evidência de engenharia

A baseline 0.1.11 passou `:core:test :api:test` antes de qualquer alteração. O teste dedicado de starvation falhou contra a lógica da baseline e passou após o patch. Na revisão pré-commit, um novo teste FIFO reprovou uma versão intermediária com ordem `ACB`; após o requeue no head, o mesmo teste exige e obtém `ABC`. O stress de 2.000 tarefas, a suíte dirigida e a regressão ampla também passam. Um build Forge 1.7.10 de engenharia validou o pipeline antes do bump; o pacote final deve ser reconstruído a partir do commit desta release.

O build e os testes headless não certificam FPS, VRAM, comportamento visual, troca real Proxy/Crucible ou sessão longa no modpack. A homologação runtime da 0.1.12 continua necessária antes de classificá-la como estável.
