# 0.1.14-dev

Esta candidata parte exatamente da 0.1.13-dev (`985d0f8cb0196e579ae6f71143b2ac7973198ad1`) e busca reduzir allocation churn no render thread do backend OpenGL/LWJGL2 sem alterar o trabalho visual entregue ao jogador.

## Contrato de experiência

A 0.1.14 não altera shaders, presets, distância LOD, resolução, SSAO, TAA, fog, fade, transparência, geometria, scheduler, governor ou readiness dos LODs.

Também permanecem fora do patch:

- `GLState` e seu mecanismo de captura/restauração;
- `AngelicaAccessor` e integração de shaderpack;
- `SharedApi` e o `System.gc()` de unload;
- cache de uniforms ou mudanças no lifecycle dos programas GL.

Uma otimização é reprovada se melhorar FPS às custas de pop-in, atraso de LOD, 1% lows, input latency, memória sustentada ou fidelidade visual.

## Render scratch reuse

`GlShaderProgram.setUniform(...)` copia matrizes/vetores imediatamente para chamadas `glUniform*`; referências aos objetos de origem não são retidas. A candidata aproveita essa propriedade apenas em objetos privados da instância de renderer, confinados ao render thread.

Os hot paths convertidos para scratch state são:

- SSAO: projection + inverse projection;
- TAA: inverse projection + inverse model-view;
- Fog: inverse MVP + array de clear color legado;
- Terrain: combined projection/model-view + model offset por buffer;
- MetaRenderer: arrays de clear color;
- Generic renderer: projection/model-view, posições chunk/subchunk e transform translate/scale de boxes.

O caminho direto de boxes conserva a ordem matemática `Projection × ModelView × (Translate × Scale)`.

## Gates algébricos

`RenderScratchReuseEquivalenceTest` executa três grupos de 2.048 iterações determinísticas. Scratch matrices são preenchidas com valores aleatórios antes do reuso para detectar qualquer campo stale.

Os testes exigem igualdade de `Float.floatToIntBits` para os 16 elementos em:

- copy + inverse contra nova matriz;
- `Projection × ModelView` contra nova matriz;
- transform direto de boxes contra a antiga sequência de factory allocations.

## Limites

A remoção de expressões `new` prova menor pressão de alocação no source, não um ganho específico de FPS. O impacto real depende da frequência de cada caminho no modpack e deve ser medido em runtime.