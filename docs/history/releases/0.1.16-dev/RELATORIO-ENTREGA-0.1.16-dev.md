# Relatório de Entrega — Akashic Mirai 0.1.16-dev

Status: **APROVADA PARA TESTE RUNTIME CONTROLADO; STABLE PENDENTE**.

Commit final: `716ba89d4eb35ca7c82de6b01d25b0655e13fa41`  
Branch: `akashic/mirai-0.1.16-dev`  
JAR SHA-256: `bb0748c9da9ce145a7f9e7bc066202856852875af8be9784650f6887e397442c`  
Source ZIP SHA-256: `e6916cb3ad0a8c627a902f434bdc0cf9d1058ae272421c806e2cbe92b8eb6930`

## Escopo efetivamente entregue

A 0.1.16 preserva a política graciosa do `PriorityTaskPicker`: `shutdown()` em todos os executors, `awaitTermination(5s)` e `shutdownNow()` apenas no mesmo timeout antigo. Ela adiciona forense de shutdown por executor/task/thread; **não afirma ainda ter reduzido os ~450–755 ms observados na 0.1.15**.

O Beacon recebeu lifecycle hardening real: o caminho pertencente ao `LodQuadTree` usa o nível proprietário e `getCameraPositionForLodTick(expectedLevel)`, descarta camera/level transitórios inválidos sem limpar o grupo atual, respeita `closing`, restaura interrupt durante o sleep e reseta o flag após rejeição do executor.

## Gates

- core: 308 testes, 0 falhas, 1 assumption skip;
- API: 5/5;
- replay Java 8u411 e Java 21.0.11: PASS com `-Xverify:all`;
- Forge 1.7.10 build completo: PASS (`remapJar -> downgradeJar -> shadeDowngradedApi`);
- class majors finais: somente 46/51/52;
- identity do JAR em Java 8 e 21: PASS;
- ABI Angelica 2.1.5-dev e 2.2.8 com GTNHLib 0.11.41: PASS;
- 45/45 shaders byte-identical à 0.1.15;
- `Config`, `SharedApi`, `AngelicaAccessor` e `GLState` byte-identical no JAR;
- 4/4 testes novos de lifecycle do Beacon;
- shutdown gracioso protegido por teste que prova que task ativa não é interrompida.

## Teste runtime recomendado

Use as mesmas configurações da 0.1.15 e faça várias trocas de dimensão/world/backend. Não é necessário `trace` para o picker: com diagnósticos normais, procure eventos `picker_shutdown`, `picker_shutdown_thread`, `task_finished`, `terminated_after_ns`, `wait_call_ns` e `forced`. Verifique também que a exceção `No camera entity or model-view matrix available` do Beacon não reaparece e que beacons/LODs não apresentam regressão após transições.

Se `forced=true`, tarefa órfã, write perdido, stale LOD, regressão visual ou nova instabilidade aparecer, a versão deve ser reprovada. Se um executor/task dominar consistentemente o stall sem necessidade de consistência, só então projetaremos cancelamento cooperativo localizado numa versão posterior.
