# Akashic Mirai 0.1.18-dev

## Escopo

`0.1.18-dev` parte exatamente do checkpoint final `0.1.17-dev` (`cffe7fdd3db08db32535c6638b020315ae1d8e43`). O objetivo desta rodada é reduzir trabalho e churn comprovadamente desnecessários e melhorar a observabilidade GPU sem reduzir qualidade visual.

A candidata não altera shaders, SSAO, anti-aliasing, fog, resolução, distância de LOD, geometria emitida, cadência do scheduler de beacons ou governor global.

## Menos churn no quadtree e beacons

- `LodQuadTree.loadQueuedSections` reutiliza uma lista de staging pertencente à árvore em vez de alocar uma nova `ArrayList` a cada tick;
- a lista mantém exatamente o mesmo comparator e a mesma ordem de upload;
- o staging é limpo em `finally`, inclusive após exceção, para não reter `LodRenderSection` após o tick/teardown;
- o refresh de beacons reutiliza seu staging de conversão sob o mesmo monitor de `beaconList`;
- sort, comparação, merge, consulta ao repositório e publicação de beacons permanecem semanticamente iguais.

Uma proposta de reutilizar também o `HashSet` de requeue foi rejeitada: a história de capacidade do `HashSet` poderia alterar a ordem de iteração e, por consequência, a readiness do tick seguinte.

## Renderer DISABLED como opt-out real

As cinco sessões Mirai 0.1.16 presentes no cliente mostraram `rendererMode="DISABLED"`, 1.551 snapshots de `VBO Render Count` todos em `0/0`, mas 18.865 escopos `GL_STORAGE_UPLOAD_WALL` concluídos. Isso demonstrou trabalho de upload sem qualquer VBO elegível para desenho.

A 0.1.18 bloqueia novos render builds/uploads quando `rendererMode=DISABLED` em três fronteiras: admissão, início do worker e antes da submissão ao uploader GPU. Se o toggle ocorrer durante uma tarefa já enfileirada, o trabalho é abortado no próximo fence possível.

A seção é marcada `renderDataDirty` quando o trabalho é adiado. Um buffer já publicado continua disponível (`canRender=true`), mas deixa de contar como upload completo; ao retornar para `DEFAULT`, o quadtree agenda o refresh normal. Trabalho GL que já tenha sido submetido antes do toggle pode terminar; não há cancelamento destrutivo de uma operação nativa em voo.

A documentação pública/upstream descreve `DISABLED` como “Disable rendering”; o projeto upstream também já registrou que manter render data apenas para alternar rapidamente modos de debug era desnecessário e aceitou maior tempo de loading na troca em favor de menor custo/memória.

## Telemetria `gpu_terrain`

Durante uma passagem já selecionada pelo profiler GPU opt-in, o terrain renderer registra:

- containers candidatos e efetivamente processados;
- slots de VBO e draw calls emitidos;
- vértices e índices submetidos;
- maior quantidade de VBOs em um único container;
- distinção entre opaque/transparent e main/deferred pass.

Fora de uma passagem GPU amostrada não são construídas strings/eventos nem mantidos contadores por draw. A telemetria serve para decidir batching/IBO a partir do jogo real, não para inferir FPS a partir de microbenchmark.

## Experimentos não promovidos

O GPU Lab ganhou um probe nativo para o SSAO RAW real (R16F, parâmetros de produção, LWJGL 2.9.4/JDK 8). `raw-step6` preservou todos os bits R16F, mas não trouxe ganho consistente. `raw-loop6`/`raw-specialized6` alteraram 113 e 50 pixels R16F nas cenas complexas; `raw-recurrence` alterou milhares. Nenhuma dessas variantes entra na 0.1.18.

O probe de IBO compartilhado manteve saída RGBA8 byte-idêntica e mostrou GPU neutra. A redução de custo CPU de submissão apareceu apenas pequena/ruidosa e depende de haver vários VBOs por container. A alteração de produção foi revertida; somente o laboratório conserva a evidência. `gpu_terrain` decidirá se essa hipótese merece nova rodada.

Uma otimização zero-allocation do comparator de `RenderBufferHandler` também foi removida antes da promoção: auditoria de `SortedArraySet` confirmou que o comparator não participa do caminho atual de `add()`, logo o patch seria semanticamente correto porém sem benefício real.

## Gates

- baseline 0.1.18 intocado testado no alvo explícito `1.7.10.properties` / `forge17` antes do primeiro patch;
- regressões `core` e `api` repetidas após os checkpoints de staging;
- `LodRenderDisabledModeTest` cobre short-circuit sem dependências construídas, preservação de dirty e buffer antigo renderizável durante catch-up;
- `MiraiGpuProfilerTest` cobre publicação de `gpu_terrain` somente durante a passagem realmente amostrada;
- busca estrutural confirma que `LodRenderSection` é o único chamador de `LodBufferContainer.tryMakeAndUploadBuffersAsync` para terrain;
- `git diff --check` deve permanecer limpo antes do build final.

## Estado de aprovação

Código/JVM gates aprovados não equivalem a homologação runtime. A 0.1.18 continua `development; runtime certification pending` até repetir o cenário real no Forbidden, primeiro com renderer desabilitado e depois com renderer ativo para captura GPU/visual.
