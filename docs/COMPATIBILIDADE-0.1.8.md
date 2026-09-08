# Auditoria de compatibilidade — Akashic of The Mirai 0.1.7

Data: 8 de setembro de 2026. Escopo: leitura do pacote 0.1.7, logs fornecidos, tags oficiais dos mods e inspeção estática do JAR local ParallelMipMap. Não houve execução do jogo, mudança de configuração, atualização ou remoção de mods nesta auditoria. Documentos anexados foram tratados como evidências históricas, não como novas instruções.

## Resultado executivo

Não há prova nos registros examinados de que Angelica, ParallelMipMap ou EntityCulling sejam a causa das quedas de FPS do Akashic. Há um crash concreto na interação de atualização de luz CoreTweaks/CubicChunks, e há sobreposições arquiteturais que merecem validação. Não confundir presença simultânea de mods, aviso genérico de Forge, ou relato upstream com causalidade demonstrada.

O caminho seguro para performance é diminuir trabalho redundante e alocações do Akashic, preservar contratos de estado OpenGL e reduzir rajadas de trabalho com medições. Desligar neblina, SSAO, transparência, culling do usuário ou reduzir distância não satisfaz o pedido de preservar o ambiente visual.

## Ambiente comprovado e qualidade da evidência

`latest.log:574` identifica **0.1.7-dev**. `previous-1.log:241` e `previous-2.log:261` identificam **0.1.6-dev**: são referência anterior, não comprovação automática de regressão atual. No latest: Angelica 2.2.8 (620), CoreTweaks 0.3.4.7-GTNH (683), EntityCulling com versão de metadado não preenchida `@VER@` (700); o log geral resolve o nome do JAR como `entityculling-1.6.4-mc1.7.10.jar` (29). CubicChunks é um fork `v0.1.21-akashic-crucible-0.1.0-dev.4` (586/617), portanto uma correção genérica do CubicChunks atual não deve ser transplantada sem suas fontes.

GPU Intel UHD, OpenGL 4.6, driver 27.20.100.9268 e LWJGL **2.9.1** aparecem em `latest.log:1217–1222`; Java 1.8.0_51 em 2. Configuração registra raio LOD 128, qualidade HIGH/HIGH, resolução BLOCK, transparência COMPLETE, DOUBLE_PASS, SSAO/AA ativos, quatro workers e ratio 1.0 (799–820). A existência de OpenGL 4.6 não prova rendimento suficiente de técnicas modernas nesse hardware nem remove os contratos de LWJGL2/Java8.

Os logs permitem localizar exceções e períodos caros. Não registram imagens, frame GPU completo, perfil de CPU por thread, configurações efetivas detalhadas dos outros mods ou teste A/B controlado. Ausência de contador de erro não prova correção visual. `GL_*_UPLOAD_WALL` mede tempo de parede com preparação e restauração, não tempo exclusivo de GPU. `GL_*_UPLOAD_BYTES` é payload tentado, não VRAM alocada (latest:11).

## Angelica 2.2.8: cache GL e renderização

Fonte oficial congelada no commit **6d8cbf9c2f46247a87716c10ccbceb5d99385ae8**, tag [2.2.8](https://github.com/GTNewHorizons/Angelica/releases/tag/2.2.8), clone `upstream/angelica-2.2.8`.

Angelica transforma chamadas `org.lwjgl` para GLSM. `GLSMRedirector` declara exclusões que não incluem `com.seibel.distanthorizons`; mapas incluem enable/disable, texture, buffer, framebuffer, consultas e blend. `AngelicaRedirectorTransformer` aplica o redirector no carregamento. O `LWJGL2Service` do Akashic chama essas APIs, portanto **a inferência sustentada pelo código é que as chamadas normais são transformadas**; comprovar todas as classes carregadas exigiria dump de bytecode transformado. [Redirector, exclusões e mapeamentos](https://github.com/GTNewHorizons/Angelica/blob/6d8cbf9c2f46247a87716c10ccbceb5d99385ae8/glsm/src/main/java/com/gtnewhorizons/angelica/glsm/redirect/GLSMRedirector.java#L177).

`GLStateManager.glGetInteger` retorna cache para programa, VAO, VBO/EBO, FBO, blend, textura ativa, profundidade e outros estados quando caching está habilitado; consulta backend para estados não cobertos ou caching desabilitado. Logo contar `glGetInteger` no fonte como número de stalls de driver superestima o problema. Também não é correto adicionar um cache global do Akashic sem protocolo de invalidação: os outros renderizadores compartilham esse estado. [Implementação de consultas](https://github.com/GTNewHorizons/Angelica/blob/6d8cbf9c2f46247a87716c10ccbceb5d99385ae8/glsm/src/main/java/com/gtnewhorizons/angelica/glsm/GLStateManager.java#L986).

Akashic já possui integração: mínimo Angelica 2.1.5, leitura de profundidade via `IRenderTargetExt`, fog RGB via `FloatBuffer` para evitar namespaces JOML incompatíveis (`forge17/.../AngelicaAccessor.java:25–90`). `GLState` restaura bindings, texturas, framebuffer, blend, depth, stencil, viewport/cull; `LegacyRenderState` cobre estado alterado fora do escopo principal. Esses contratos e `finally`/cleanup precisam permanecer na otimização. O método `restoreDepthAndBlendFunctions` chama blend simples e depois separado; é uma redundância candidata muito pequena, não evidência de gargalo e não justifica simplificar globalmente guards.

Angelica possui workers que usam `RenderThreadContext.workerSlice()` e `WorldSlice` para redirecionar acesso a blocos/luz; a guarda de mutações só atua quando existe esse contexto. Ela **não torna automaticamente seguros quaisquer workers externos** do Akashic ou EntityCulling. Preserve snapshots do mundo no thread correto e processamento assíncrono sobre dados próprios. [Acesso de worker](https://github.com/GTNewHorizons/Angelica/blob/6d8cbf9c2f46247a87716c10ccbceb5d99385ae8/src/mixin/java/com/gtnewhorizons/angelica/mixins/early/celeritas/terrain/MixinWorldClient_WorkerAccess.java#L31).

Existe relato oficial aberto de flicker em Angelica 2.2.8 com Intel iGPU, Indirect e CPU Only Culling. Ele usa Java25/lwjgl3ify e não demonstra que o usuário sofreu o mesmo defeito com Java8/LWJGL2. Registrar como hipótese para reprodução, sem trocar opções automaticamente. [Issue 2038](https://github.com/GTNewHorizons/Angelica/issues/2038).

## CoreTweaks: crash concreto e limites

Tag 0.3.4.7-GTNH, commit **71f2329a6fed5eea29fce4241b82e1751e6b30e4**, clone `upstream/coretweaks-0.3.4.7`.

`log.txt:19261` registra `ArrayIndexOutOfBoundsException: 16` em `Chunk.getEBS_CubicChunks`, via leitura de luz de CubicChunks. A pilha passa por `LightFixStare.postPlayerCheckLight` (19273), `World.setActivePlayerChunksAndCheckLight` e tick do cliente. Não contém frame Akashic. É prova de uma falha do ambiente; não prova que CoreTweaks seja o único responsável, pois o acesso que lança está no fork CubicChunks.

CoreTweaks escolhe posição observada por raycast e testa luz numa vizinhança ±5 sem limitar Y no próprio método. Índice 16 é coerente com acesso além das 16 seções de um chunk vanilla, mas a coordenada exata não consta nessa pilha; a origem precisa ser reproduzida com o fork instalado. Corrigir a fronteira do armazenamento CubicChunks ou integrar corretamente a API de altura é preferível a capturar toda `ArrayIndexOutOfBoundsException` no render Akashic. Não desativar `LightFixStare` silenciosamente nem apagar o mod. [Código do gatilho](https://github.com/GTNewHorizons/CoreTweaks/blob/71f2329a6fed5eea29fce4241b82e1751e6b30e4/src/main/java/makamys/coretweaks/tweak/LightFixStare.java#L11).

O ajuste far-plane faz `Math.max(config.minFarPlaneDistance, original)`, injetado no argumento de `gluPerspective`. Akashic deve continuar usando a matriz efetiva produzida pelo jogo. Não reconstruir uma projeção presumindo valores vanilla. [Implementação](https://github.com/GTNewHorizons/CoreTweaks/blob/71f2329a6fed5eea29fce4241b82e1751e6b30e4/src/main/java/makamys/coretweaks/tweak/FarPlaneDistanceTweaker.java#L7).

O `threadedTextureLoader` no código dessa tag é explicitamente inicializado como FALSE; não atribuir contenção a esse recurso só pela presença do CoreTweaks. A otimização de upload de FastCraft é direcionada a texture stitching. [Configuração da tag](https://github.com/GTNewHorizons/CoreTweaks/blob/71f2329a6fed5eea29fce4241b82e1751e6b30e4/src/main/java/makamys/coretweaks/Config.java#L199).

## ParallelMipMap: identificado pelo próprio artefato

O aviso Forge em `log.txt:1298` chama o JAR de non-mod. Inspeção ZIP mostra `FMLCorePlugin: com.gamerforea.parallelmipmap.CoreMod` e `FMLCorePluginContainsFMLMod: false`: trata-se de um coremod, sem container FML convencional. **Esse aviso isolado não autoriza concluir que deve ser removido.**

JAR local: `C:/Users/User/AppData/Roaming/.technic/modpacks/forbidden-series/mods/ParallelMipMap-1.0.jar`; SHA256 **BE7B3B007B79DF37944DB21C6AF83A553510A94B5FB8626AA14F955F27D58288**. Fonte pública primária não localizada; não atribuo um repositório por suposição. Bytecode lido sem executar o mod com `javap -c -p`, salvo em `upstream/parallelmipmap-1.0-javap.txt`.

`ASMTransformer.patchTextureMap` substitui o método completo `loadTextureAtlas` por um corpo fornecido no JAR. `ParallelUtils.generateMipMaps_MultiThread` inicia `availableProcessors()` threads daemon `MipMap worker #...`, distribui sprites com iterador sincronizado e faz join em todas. O trabalho identificado é geração de mipmaps durante carga/reload do atlas, não um render loop permanente. Nenhuma relação causal com o custo contínuo de LOD foi encontrada.

Angelica injeta em `loadTextureAtlas` (HEAD, loadSprite e RETURN) e redireciona `generateMipmapData` em cada sprite. Há **superfície de colisão comprovada**, mas não efeito quebrado comprovado: a ordem dos transformers e resultado final determinam se os hooks sobrevivem. Evitar novos overwrites de TextureMap no Akashic; invalidar apenas caches próprios no reload. Testar atlas e recursos depois de F3+T em ambiente descartável. [Hooks do atlas](https://github.com/GTNewHorizons/Angelica/blob/6d8cbf9c2f46247a87716c10ccbceb5d99385ae8/src/mixin/java/com/gtnewhorizons/angelica/mixins/early/celeritas/features/textures/MixinTextureMap.java#L58), [mipmaps por sprite](https://github.com/GTNewHorizons/Angelica/blob/6d8cbf9c2f46247a87716c10ccbceb5d99385ae8/src/mixin/java/com/gtnewhorizons/angelica/mixins/early/celeritas/features/mipmaps/MixinTextureAtlasSprite.java#L139).

## EntityCulling: trabalho diferente dos LODs

Tag exata **1.6.4-1.7.10**, commit **d226a35031d5d906ea6793f9e9073878f2099cc8**, clone `upstream/entityculling-1.6.4-1.7.10`. Não usar a documentação da versão moderna como garantia de comportamento deste backport.

`CullTask` executa em `CullThread`, lê entidades/tile entities e testa visibilidade de AABBs com CPU e cache de oclusão. `Provider` usa `world.getBlock(...).isOpaqueCube()`. Mixins cancelam render de entidades e tile entities; não encontram meshes LOD do Akashic. `CeleritasWorldRenderer.isEntityVisible` usa visibilidade por seções e preserva entidades em shadow pass; não é duplicação exata da oclusão por linha de visão. Não desligar um recurso alegando redundância sem teste. [CullTask da tag](https://github.com/tr7zw/EntityCulling/blob/d226a35031d5d906ea6793f9e9073878f2099cc8/src/main/java/dev/tr7zw/entityculling/CullTask.java#L39), [Provider](https://github.com/tr7zw/EntityCulling/blob/d226a35031d5d906ea6793f9e9073878f2099cc8/src/main/java/dev/tr7zw/entityculling/Provider.java#L14), [culling Angelica](https://github.com/GTNewHorizons/Angelica/blob/6d8cbf9c2f46247a87716c10ccbceb5d99385ae8/src/main/java/com/gtnewhorizons/angelica/rendering/celeritas/CeleritasWorldRenderer.java#L593).

Há leituras concorrentes toleradas pelo backport e tratamento de NPE/CME ao iterar listas. Isso não é evidência de falha no log, nem autorização para compartilhar suas estruturas nos workers do Akashic. Otimizar culling de nuvens/LODs mantendo decisão idêntica é independente desse mod e evita aumentar a contenção.

## Ordem recomendada

1. Separar resultados 0.1.6/0.1.7 e registrar crash CoreTweaks/CubicChunks como item próprio com coordenadas/reprodução pendentes.
2. Implementar no Akashic somente reduções mensuráveis de trabalho/alocação e testes diferenciais que mantenham decisões de culling, estado GL e dados produzidos.
3. Medir distribuição de frame, fila/render/upload e trabalho de snapshot; manter métricas separadas para tempo CPU de parede e GPU. Não aumentar workers automaticamente ao detectar outros mods.
4. Validar jogo com o mesmo pacote, resolução, distância, posição, recursos e opções: giro/caminhada/parado, transição opaco/translúcido, água, céu/nuvens, inventário, reload, troca de dimensão/desconexão, altura próxima de 256 e shaders se utilizados.
5. Só depois estudar experimentos isolados de upload/culling mais profundos. Números sem teste A/B não sustentam promessa de FPS final.

Nenhum patch de compatibilidade externa é necessário apenas para satisfazer a arquitetura examinada. Os riscos identificados devem guiar testes e investigações dirigidas, preservando configurações e mods do jogador.
