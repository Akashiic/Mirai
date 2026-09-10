# Plano, auditoria e homologação — 0.1.12-dev

## Checkpoint e regra de regressão

A fonte de referência desta rodada é a 0.1.11-dev limpa em `1484fc07025140f5e5a109c26eb4ed58ebc062de`. O desenvolvimento ocorre no worktree isolado `akashic-mirai-gpt-0.1.12`; o checkout original não é modificado. A 0.1.12 só pode avançar se mantiver os contratos visuais, de ownership e de compatibilidade já aprovados na 0.1.11.

Não pertencem ao governor: distância LOD, SSAO, AA, fog, geometria, shaders ou qualidade. Nenhuma dessas opções é reescrita automaticamente.

## Issues confirmados na sessão longa

1. **Starvation reproduzível:** com `numberOfThreads=1` e `threadRunTimeRatio=0.5`, múltiplos pools acumularam backlog e ficaram sem progresso durante um intervalo prolongado. O código mostrou que o picker podia reservar o único slot global para uma tarefa colocada atrás do throttle `afterExecute()` do executor.
2. **Sync terminal:** `hasSyncRequestCapacity()` corretamente recusava uma sessão fechada, mas a camada de deferred ainda aceitava intents porque testava somente o fechamento da fila. O resultado observado foi `ready=false`, `closed=true`, `sync_deferred=4096` e overflow continuado.
3. **FullData no unload:** parte dos 53 eventos era cancelamento esperado de shutdown embrulhado em `CompletionException`; outra parte vinha de trabalho de chunk já enfileirado chegando a um client level após sua desassociação.
4. **Pressão de memória/GC:** a sessão registrou heap alto e pausas GC de segundos. Essa evidência habilita governança conservadora de concorrência, não redução automática de qualidade.
5. **Ponto cego:** `Update Propagator` participava do `PriorityTaskPicker`, mas não aparecia no snapshot Mirai da 0.1.11.

## Arquitetura da correção

```text
render owner -> MiraiRuntimeGovernor -> effective thread ceiling
                                     -> PriorityTaskPicker
queued category -> reserve global slot -> reserve immediately available worker
                                      -> run command -> release global slot
                                      -> preserve worker reservation through throttle
                                      -> throttle ends -> release worker -> wake picker
```

O governor mede somente sinais disponíveis dentro do processo: razão aproximada de heap usado, delta de tempo acumulado dos coletores e frametimes do render owner durante gameplay focado. O limite efetivo nunca ultrapassa `numberOfThreads`; reduções não cancelam tarefas já em execução e a recuperação é gradual.

## Portões de aprovação

- **G0 — baseline:** `:core:test :api:test` deve passar antes do primeiro patch. Resultado: aprovado.
- **G1 — reprodução:** teste dedicado deve falhar com a lógica 0.1.11 no cenário de starvation e passar com a correção. Resultado: aprovado.
- **G2 — concorrência/FIFO:** 2.000 tarefas instantâneas devem drenar e `occupiedThreadsRef` deve retornar exatamente a zero. Um segundo caso força backpressure durante throttle e exige ordem `ABC`; a versão intermediária `ACB` foi rejeitada. Resultado final: aprovado.
- **G3 — sync/lifecycle:** testes de cache remoto, deferred e delayed batch devem continuar verdes, incluindo sessão terminal sem intent retido. Resultado: aprovado.
- **G4 — regressão ampla:** `:core:test :api:test`. Resultado: aprovado na candidata de código.
- **G5 — pacote Forge 1.7.10:** build completo e `verify-mirai-jar.py`. O build de engenharia pré-bump foi aprovado; o build final deve ser repetido sobre o commit de release.
- **G6 — ABI/isolamento/identidade:** executar os harnesses Angelica, fogcolor, fastutil e release identity com os binários exatos disponíveis. Ausência de controle histórico deve ser registrada, não simulada.
- **G7 — runtime:** iniciar o modpack real com o JAR final e reproduzir navegação, voo/carga, `1 thread / 0.5`, sessão longa, unload/reconnect e coleta Mirai. Só este gate pode promover a candidata para estável.

## Critérios para a próxima coleta

Durante o teste runtime, observar `runtime_governor`, `scheduler_state` e todos os eventos `threads`. Em `1 thread / 0.5`, backlog pode crescer temporariamente, mas `completed` precisa continuar avançando entre categorias e `occupied` não pode ficar preso sem um `reserved` correspondente. Em sessão fechada, `sync_deferred` deve convergir para zero e `SYNC_DEFERRED_OVERFLOW` não deve continuar crescendo.

No unload/reconnect, cancelamentos normais não devem aparecer como `Unable to persist full-data batch`; tarefas stale de client level não devem publicar após `wrapper.getDhLevel()` deixar de apontar para o level antigo. Qualquer crash, corrupção visual, buraco persistente ou regressão de handoff reprova a candidata, independentemente de melhora de FPS.

## Rollback

A 0.1.11 permanece o checkpoint funcional. Para isolar apenas o governor durante A/B, iniciar o cliente com `-Dakashic.mirai.runtimeGovernor=false`; isso não desfaz os fixes de starvation, sync ou lifecycle. Se houver regressão estrutural, retornar o JAR inteiro à 0.1.11 e preservar os logs da sessão reprovada.
