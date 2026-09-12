# Plano e Diagnóstico — Akashic Mirai 0.1.15-dev

## Checkpoint

Base: 0.1.14-dev, commit `50dd0425da472835e81dacd82b473264ff06ce9e`, JAR SHA-256 `bb1da37e04fc0d16a3fd4b0f09d78beed0bb707e4556fae95f2b6ce27b4bf569`.

A 0.1.15 não substitui o teste A/B 0.1.13→0.1.14. Ela adiciona observabilidade para decidir o próximo alvo sem alterar o frame visual.

## Contrato de experiência

A instrumentação não pode:

- alterar pixels, shaders ou qualidade;
- reduzir trabalho visual ou readiness dos LODs;
- mudar ordem de chamadas OpenGL;
- cachear/omitir estados que protegem Angelica e outros mods;
- mudar scheduler/governor ou prioridades;
- introduzir eventos/logs por frame;
- adicionar consultas ao driver só para medir.

## GLState

Cada snapshot mantém exatamente o save/restore original. Durante `trace`, dois cronômetros CPU-wall envolvem save e restore; fora de `trace`, a checagem retorna sem `System.nanoTime()` quando não há trace ativo.

A saída `gl_state` é agregada em janelas de 5 s e usa buckets de microssegundos. Ela mede elapsed CPU wall incluindo waits no driver; não é GPU time e não deve ser somada cegamente aos passes GPU.
## Thread pools

`setupThreadPools()` é decomposto sem reordenação funcional em:

1. OLD_PICKER_SHUTDOWN
2. PICKER_CREATE
3. NETWORK_COMPRESSION
4. NETWORK_CLIENT_HANDLER
5. IO
6. RENDER_LOADER
7. LOD_BUILDER
8. UPDATE_PROPAGATOR
9. WORLD_GEN
10. BEACON_CULLING
11. FULL_DATA_MIGRATION

Cada fase é rara e registra `setup_id`, `wall_ns` e `completed`. A ação original continua lançando a mesma exceção quando falha.

## Gates internos

- G0 — worktree isolado a partir do commit exato da 0.1.14: aprovado.
- G1 — baseline core/API em 1.7.10 antes do patch: aprovado.
- G2 — regressão de metadata/rotação detectada e corrigida sem relaxar limites: aprovado.
- G3 — testes dirigidos de histogramas, no-op e exception semantics: aprovado.
- G4 — sequência de chamadas GL em `GLState` idêntica por símbolo à 0.1.14: aprovado.
- G5 — shaders, Config e AngelicaAccessor inalterados: aprovado nesta etapa.
- G6 — regressão ampla core/API + compile common/LWJGL2/Forge17 na identidade final 0.1.15: aprovado.
- G7 — replay explícito Java 8/21: aprovado; core 303/0 falhas e API 5/5 em ambos os runtimes.
- G8 — build Forge17, bytecode Java 8, release identity Java 8/21 e ABI Angelica 2.1.5/2.2.8: aprovados na candidata pós-commit; o artefato final pós-amend repete esses gates.
- G9 — runtime real com `trace 60`/`trace 120`: obrigatório e pendente.

## Homologação runtime

Rodar primeiro um trecho normal sem trace para confirmar que a observabilidade não altera comportamento. Depois usar `trace 60` ou `trace 120` numa cena reproduzível.

Analisar `gl_state` por escopo:

- count por janela;
- mean_us;
- max_us;
- p95_us de save e restore;
- correlação com frametime, GUI/focus e shaderpack ativo.

Também procurar eventos `thread_pool_setup` em connect/disconnect/troca de mundo e identificar a subfase responsável por setups longos.

## Critério para uma futura otimização

Se GLState for barato, não otimizar. Se for material, qualquer 0.1.16 deve manter restore correctness, Angelica/shader compatibility e Visual/Player Experience Invariants. Nenhuma redução de `glGet*` ou cache entra apenas por intuição.