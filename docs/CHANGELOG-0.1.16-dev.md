# Akashic Mirai 0.1.16-dev

## Escopo

`0.1.16-dev` parte exatamente do checkpoint `0.1.15-dev` (`e89f350cf8dce791d5754664215d34451134776e`).

A rodada 0.1.15 mostrou que `GLState` custa pouco demais para justificar cache agressivo, mas revelou `OLD_PICKER_SHUTDOWN` de ~450–755 ms em várias transições e uma race real de beacon culling quando a câmera deixa de existir durante troca de mundo.

Esta versão não reduz qualidade, não altera shaders e não muda a política de shutdown para “abandonar” trabalho.

## Picker shutdown forensics

- preserva `Executor.shutdown()` gracioso;
- preserva `awaitTermination(5, SECONDS)`;
- preserva o fallback `shutdownNow()` somente após o mesmo timeout antigo;
- registra estado inicial por executor: picker queue, running, reserved, active workers e worker queue;
- registra `terminated_after_ns` e tempo observado em cada `awaitTermination`;
- registra task class/runtime quando uma task ativa termina durante o shutdown;
- captura, somente no início do shutdown, até 16 threads `DH-*` e até 6 frames por thread;
- não adiciona polling ou stack sampling no gameplay normal.
## Beacon lifecycle hardening

O background beacon culling deixa de consultar `getCameraExactPosition()` no caminho pertencente ao `LodQuadTree`.

- resolve `IClientLevelWrapper` no instante do culling via owner supplier;
- usa `getCameraPositionForLodTick(expectedLevel)`, que pode retornar `null` durante transição;
- se owner estiver closing, level wrapper estiver ausente ou camera snapshot for `null`, a rodada é descartada sem limpar o grupo atual;
- a lista filtrada é preparada em scratch privado antes da publicação;
- `cullingThreadRunning` e `updateRenderDataNextFrame` passam a ser `volatile` para visibilidade cross-thread;
- `RejectedExecutionException` reseta `cullingThreadRunning`, evitando estado preso após teardown.

## Gates adicionados

`BeaconRenderLifecycleTest`: 4 casos de ownership/camera transitória.

`PriorityTaskPickerThrottleTest`: novo caso garante que shutdown continua esperando task ativa sem interrompê-la.

## Contrato

Não há alteração deliberada em LOD radius, geometry, SSAO, AA, fog, framebuffer resolution, shader quality, scheduler budget ou governor. A versão serve para localizar com precisão a task/executor responsável pelo stall e para eliminar a race de Beacon já observada em runtime.
