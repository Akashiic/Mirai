# Plano e Diagnóstico — 0.1.16-dev

## Evidência de entrada

Na homologação 0.1.15, `OLD_PICKER_SHUTDOWN` dominou recriações de pools: ~451 ms, ~744 ms, ~594 ms e ~755 ms, respondendo por ~99,9% do `THREAD_POOLS_SETUP` nesses casos.

A leitura do source mostrou que `PriorityTaskPicker.shutdownNow()` é, apesar do nome, majoritariamente gracioso: sinaliza `shutdown()` em todos os executors e espera até 5 s por terminação; só força `shutdownNow()` se o timeout expirar.

Logo, cortar a espera sem descobrir a task ativa violaria o contrato de consistência do projeto.

A mesma rodada registrou uma exceção do `DH-Beacon Culling Thread` ao consultar câmera durante troca de world/backend. O projeto já dispõe de `getCameraPositionForLodTick(expectedLevel)`, que valida ownership de nível/câmera e retorna `null` para transições transitórias.

## Objetivos

1. Tornar o shutdown do picker explicável por executor/task/thread.
2. Preservar integralmente a política de shutdown gracioso existente.
3. Impedir beacon culling stale durante transições sem esconder erros reais durante gameplay válido.
4. Não alterar qualidade visual, LOD readiness ou políticas de scheduler.

## Não objetivos

- não reduzir timeout de 5 s;
- não interromper task ativa antecipadamente;
- não abandonar writes/IO/worldgen em voo;
- não reduzir trabalho gráfico;
- não cachear GLState;
- não otimizar SSAO nesta versão.
## Telemetria esperada

Cada `picker_shutdown` possui um `id` único. No início são registrados executors/occupied/runtime ratio e um snapshot limitado das threads `DH-*`.

Para cada executor, o log registra estado antes do sinal e depois da terminação. `terminated_after_ns` mede quando o underlying executor atingiu `terminated()` em relação ao início global; `wait_call_ns` mede quanto a iteração atual ficou bloqueada em `awaitTermination`.

Tasks que terminarem após o início do shutdown registram executor, classe do `Runnable`, runtime total, elapsed desde o início da parada e flag de interrupção.

Esses valores não devem ser somados indiscriminadamente: executors são sinalizados antes das esperas e podem terminar em paralelo.

## Critério para uma otimização posterior

Somente após runtime real:

- se um executor/task dominar os ~450–755 ms e houver checkpoint de cancelamento seguro, projetar correção localizada;
- se o tempo representar operação obrigatória de consistência, preservar a espera;
- se aparecer `forced=true`, investigar o timeout antes de qualquer redução;
- nenhuma melhora é aprovada se introduzir orphan tasks, writes perdidos, stale LODs, memory leak ou piora de transição.

## Gates de laboratório atuais

- baseline 0.1.15 antes do patch: PASS;
- Beacon lifecycle: 4/4 PASS;
- picker regressions: 5/5 PASS, incluindo shutdown gracioso sem interrupção;
- core completo: 308 testes, 0 falhas, 1 assumption skip;
- API: 5/5 PASS;
- replay explícito Java 8u411: core 308/0 falhas (1 assumption), API 5/5 PASS;
- replay explícito Java 21.0.11: core 308/0 falhas (1 assumption), API 5/5 PASS;
- common + LWJGL2 + Forge17 compile: PASS;
- runtime real da 0.1.16: pendente.
