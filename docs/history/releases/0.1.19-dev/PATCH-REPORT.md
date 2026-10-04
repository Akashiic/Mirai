# Akashic Mirai 0.1.19-dev — Disabled Renderer Quiescence

## Objetivo

Quando `rendererMode = "DISABLED"`, manter o Akashic Mirai instalado sem continuar pagando o custo normal de um renderer que não produzirá pixels.

Este patch não limita FPS, não reduz qualidade e não altera shaders. Ele fecha fontes de trabalho novo do Mirai enquanto o renderer está desligado.

Commit aprovado: `afef9d7e4f085c0d1dccba66d763db22878714ca`
Branch: `akashic/mirai-0.1.19-disabled-quiescence`
Minecraft: `1.7.10` / Forge17 / LWJGL2

## Evidência que motivou o patch

No teste real com renderer DISABLED, `LOD_RENDER_WALL` e uploads GL ficaram em zero, mas ainda havia `CHUNK_SUBMITTED`, `DB_COMMITTED`, sync/cache remoto e `LOD_UPDATE_WALL`.

O log vanilla também registrou `Clientside chunk ticking` acima de 100 ms, chegando a 467 ms. As maiores pausas de frame observadas eram ainda maiores que o trabalho medido do Mirai; portanto o patch reduz a contribuição do Mirai, mas não afirma corrigir todo stutter do modpack.

## O que muda em DISABLED

- `ClientRenderState`/quadtree não são criados no startup se o renderer já estiver desligado.
- O quadtree deixa de receber ticks enquanto DISABLED.
- Novos eventos chunk→LOD do Forge 1.7.10 são suprimidos antes de `ChunkWrapper`/pipeline DH.
- Novos requests `SyncOnLoad` e sincronização remota diferida deixam de ser admitidos.
- Partial LOD pushes podem ser descartados antes do decode quando SyncOnLoad garante recuperação posterior.
- Atualizações que já estavam em voo terminam normalmente; commits/ownership não são cancelados no meio.
- A 0.1.18 já bloqueava novos geometry builds/uploads GPU; esta camada acrescenta quiescência do pipeline anterior ao GPU.

## Reativação

Coordenadas de chunks suprimidos são guardadas em backlog primitivo limitado a 4096 entradas. Não são retidos objetos `Chunk` e o mundo é mantido apenas por `WeakReference`.

Ao voltar para DEFAULT, somente chunks ainda residentes são reenviados, no máximo 1 a cada 5 ticks. `chunkExists(x,z)` é verificado antes do lookup: o catch-up não força load, ticket ou worldgen.

O render state antigo é reconstruído uma única vez na reativação; não há shutdown pesado no instante em que o jogador escolhe DISABLED.

## Rollback imediato

JVM opt-out: `-Dakashic.mirai.disabledRendererQuiescence=false`

Com esse opt-out o comportamento de quiescência fica desativado sem remover o JAR.

## Gates offline

- Core: 335 testes, 0 falhas, 0 erros, 1 skip.
- API: 5 testes, 0 falhas/erros.
- `common:compileJava` e `forge17:compileJava`: PASS no target 1.7.10.
- Build final: PASS incluindo `downgradeJar` e `shadeDowngradedApi`.
- JAR ZIP integrity: PASS.
- Class majors: somente 46, 51 e 52.
- Identidade empacotada: PASS em Java 8u362 e Java 21.0.11.
- ABI Angelica 2.1.5-dev: PASS.
- ABI Angelica 2.2.8 + GTNHLib 0.11.41: PASS.
- 45/45 recursos de shader byte-idênticos à 0.1.19 diagnóstica.

## Limite da aprovação

**Aprovado para teste runtime controlado.** Ainda não é correto afirmar que todo stutter do modpack foi eliminado: o aviso de chunk ticking pertence ao ciclo de chunks do cliente e outros mods também participam desse ciclo.
