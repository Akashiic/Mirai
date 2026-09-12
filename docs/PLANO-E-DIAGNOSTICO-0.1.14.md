# Plano, auditoria e homologação — 0.1.14-dev

## Checkpoint

Base imutável: 0.1.13-dev, commit `985d0f8cb0196e579ae6f71143b2ac7973198ad1`, JAR SHA-256 `b105c663e3009e90413370fd81428bcea3c4342cb70a1825515fe60b95cfa409`.

A 0.1.13 continua sendo o experimento isolado da primeira otimização SSAO e deve ser testada separadamente. A 0.1.14 não substitui esse A/B científico; ela inicia uma rodada independente de redução de allocation churn.

## Player Experience Invariant

Uma otimização transparente não pode piorar:

- fidelidade visual;
- velocidade de readiness dos LODs;
- pop-in ou atualização distante;
- frametime e 1% lows;
- input responsiveness;
- world load/unload e troca de dimensão;
- compatibilidade Angelica/Iris/shaderpacks;
- memória sustentada em sessões longas.

Ganhar FPS fazendo menos trabalho visível é reprovação, não otimização.

## Escopo aprovado

Somente temporários render-thread-owned cujo conteúdo é copiado imediatamente por `glUniform*` ou usado durante a própria chamada GL podem ser reutilizados.

A intervenção inclui:

- matrices scratch de SSAO/TAA/Fog/Terrain;
- scratch vectors e transforms do Generic renderer;
- scratch de model offset usado por buffer LOD;
- arrays pequenos de clear color em Fog/MetaRenderer.

Não entram nesta rodada:

- cache/pooling de `GLState`;
- remoção de consultas `glGet*`;
- redução de qualidade ou resolução;
- cache de uniforms constantes;
- alteração de thread pools/governor;
- remoção do `System.gc()` de unload.

A auditoria confirmou que `SharedApi.setDhWorld(null)` ocorre também em disconnect normal. Portanto o GC não pode ser tratado como “shutdown final” sem um sinal adicional comprovável.

## Gates internos

- G0 — worktree isolado a partir do commit exato da 0.1.13: aprovado.
- G1 — baseline `:core:test :api:test` em 1.7.10 antes do patch: aprovado.
- G2 — prova de cópia imediata de matrix/vector uniforms pelo `GlShaderProgram`: aprovado por inspeção de source.
- G3 — equivalência bit a bit de scratch matrices: aprovado em 3 × 2.048 iterações.
- G4 — compilação `common`, LWJGL2 e Forge17 após o patch: aprovado.
- G5 — regressão ampla core/API na identidade final 0.1.14: aprovado.
- G6 — shaders, `Config`, `GLState`, `AngelicaAccessor` e `SharedApi` byte/logicamente inalterados: aprovado antes do version bump; repetir no final.
- G7 — replay Java 8/21, build Forge17, bytecode Java 8, release identity e ABI Angelica 2.1.5/2.2.8: aprovados na candidata; o pacote de release pós-amend repete identity/ABI e registra hashes finais.
- G8 — runtime real comparativo: obrigatório e pendente.

## Homologação runtime

A ordem recomendada é testar primeiro a 0.1.13 isoladamente e registrar seu comportamento. Depois repetir a mesma cena/configuração com a 0.1.14.

Para a 0.1.14 observar prioritariamente:

1. FPS médio e distribuição de frametime;
2. 1% lows e microstutter;
3. heap/GC e allocation pressure indireta;
4. tempo de readiness dos LODs enquanto se desloca;
5. ausência de pop-in adicional;
6. world load/unload e troca de dimensão;
7. Angelica + o mesmo shaderpack já homologado;
8. ausência de novos erros GL/lifecycle.

## Critério de aprovação

A 0.1.14 só avança se for neutra ou melhor em experiência. Ganho médio com 1% lows, readiness, memória ou compatibilidade piores reprova a candidata. Nenhum ganho de FPS é declarado antes dos logs do cliente real.