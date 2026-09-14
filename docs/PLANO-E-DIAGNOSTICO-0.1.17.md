# Plano e Diagnóstico — 0.1.17-dev

## Evidência de entrada

A homologação 0.1.16 tornou o shutdown do picker observável. Em transições reais, a thread `DH-IO_Thread` apareceu em `TIMED_WAITING` dentro de `LodQuadTree.lambda$tryRefreshRenderingBeaconsAsync$0`, no `Thread.sleep(2_000)` executado **depois** de `refreshRenderingBeacons`.

O source confirmou a sequência antiga: consulta/refresh → sleep de 2 s → conclusão do future. Assim, parte dos 450–1.077 ms observados no `OLD_PICKER_SHUTDOWN` podia ser apenas o restante de um cooldown ocupando IO, e não uma operação de consistência ainda em execução.

## Objetivo

Preservar a mesma cadência lógica de beacon refresh e o mesmo conteúdo publicado, mas mover o cooldown para estado temporal da árvore, liberando o worker imediatamente quando a consulta termina.

## Invariantes

1. O intervalo continua sendo 2 s **após a conclusão** do refresh.
2. Uma consulta ativa não é abortada para reduzir shutdown.
3. Não pode existir mais de uma submissão por árvore.
4. Fechamento do owner impede trabalho/publicação stale.
5. Troca de picker não pode deixar reserva órfã nem permitir que runnable antigo libere tarefa nova.
6. Nenhum ganho pode vir de redução de qualidade gráfica ou frequência lógica.

## Implementação

`BeaconRefreshScheduler` usa `System.nanoTime()` apenas quando necessário, com subtração relativa para tolerar origem arbitrária e wrap signed. Não existe timer de background.

No tick, o scheduler:
- rejeita owner/executor indisponível;
- revoga somente submissão antiga que nunca começou e cujo executor já fechou;
- respeita cooldown sem criar `Submission`;
- reserva e envia uma única task.

No worker, a task valida sua própria identidade antes de executar. Ao terminar, registra o instante final, inicia o cooldown e libera sua própria reserva. Uma task stale não modifica estado de uma task mais nova.

## Evidência de laboratório

- baseline 0.1.16 antes do patch: core/API PASS;
- scheduler: 16/16 PASS;
- integração picker real: 2/2 PASS;
- lifecycle Beacon existente: 4/4 PASS;
- regressões PriorityTaskPicker: 5/5 PASS;
- suíte final: core 326, 0 falhas, 1 skip; API 5/5;
- `BEACON_PICKER_PROBE`: worker disponível em ~6,51 ms e shutdown em ~1,30 ms nesta execução de laboratório;
- source contract: PASS; corpo do refresh equivalente exceto guards de closing, `LodQuadTree.close()` idêntico, sem sleep/timer novo.

O probe não é benchmark de gameplay e não promete os mesmos tempos no modpack. Ele prova que o cooldown já não mantém o worker de IO ocupado.

## Replay de portabilidade

- Java 8u411: core 326 executados, 0 ignorados, 1 assumption, 0 falhas; API 5/5;
- Java 21.0.11: core 326 executados, 0 ignorados, 1 assumption, 0 falhas; API 5/5.

Um primeiro full-run teve falha isolada em `ClientOnlySaveStructureRetentionTest.wrapperMemoizationDoesNotKeepUnloadedWorldAlive`. O teste passou imediatamente quando reproduzido isoladamente; sem alterar esse teste ou seu código de produção, o full-run seguinte passou integralmente. A ocorrência é registrada como flake observado, não escondida nem usada como motivo para relaxar gates.
