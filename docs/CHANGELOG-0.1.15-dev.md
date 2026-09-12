# Akashic Mirai 0.1.15-dev

Base imutável: 0.1.14-dev, commit `50dd0425da472835e81dacd82b473264ff06ce9e`.

## Objetivo

Esta candidata é uma release de observabilidade. Ela mede custos que ainda eram agregados/ocultos, sem tentar otimizar estado OpenGL ou alterar a experiência do jogador.

## GLState timing

- `GLState` recebe um escopo fixo por call site: MAIN_PASS, SSAO, TAA, FOG, FAR_FADE, VANILLA_FADE, APPLY, COPY, BLOCK_TEXTURE_ATLAS ou UNSCOPED.
- Save e restore são medidos separadamente.
- A coleta usa buckets de microssegundos e reporta count, mean, max e p95 bucket.
- A coleta detalhada é ativa somente durante `trace`.
- Fora de `trace`, `traceTimingStart()` retorna antes de `System.nanoTime()` quando o trace está desativado.
- Nenhuma nova chamada `glGet*`, bind, framebuffer operation ou query GPU é emitida para medir.
- Os resultados são agregados pelo writer; não existe evento textual por snapshot/frame.

## Thread-pool setup

`setupThreadPools()` agora registra fases raras com `setup_id`, `wall_ns` e completed=true/false:
OLD_PICKER_SHUTDOWN, PICKER_CREATE, NETWORK_COMPRESSION, NETWORK_CLIENT_HANDLER, IO, RENDER_LOADER, LOD_BUILDER, UPDATE_PROPAGATOR, WORLD_GEN, BEACON_CULLING e FULL_DATA_MIGRATION.
## O que deliberadamente NÃO mudou

- nenhum shader;
- nenhum preset ou parâmetro visual;
- nenhuma distância LOD;
- nenhum algoritmo SSAO/TAA/Fog/Fade;
- nenhum cache de `GLState`;
- nenhuma remoção de `glGet*`;
- nenhum scheduler/governor/readiness policy;
- nenhum `System.gc()`;
- nenhuma integração Angelica/Iris.

A sequência de chamadas GL dentro de `GLState` foi auditada contra a 0.1.14 e permanece idêntica em quantidade por símbolo.

## Correção durante desenvolvimento

A primeira tentativa adicionou `timing.gl_state` como metadata persistente. O teste de rotação do logger reprovou porque essa metadata extra podia deslocar metadata posterior em segmentos sintéticos pequenos. A entrada persistente foi removida; o escopo da medição é comunicado apenas no evento `trace start`. O teste original voltou a passar sem aumentar limites.

## Status

Nenhum ganho de FPS é declarado nesta versão. O objetivo é obter evidência suficiente para decidir se uma futura otimização de driver/GLState é necessária ou deve ser abandonada.