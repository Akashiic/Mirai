# Auditoria GPU de SSAO, TAA/CAS, fog e far fade — candidata 0.1.10

## Decisão

**Nenhuma variante desta auditoria foi promovida a produção.** O ganho mais claro apareceu no TAA quando a reprojeção estava fora da imagem histórica, mas a alteração de fluxo também mudou arredondamentos em pixels com histórico válido. As variantes que preservaram exatamente os canais UNORM16 não mostraram ganho consistente neste driver. Não há evidência para anunciar que a 0.1.10 eliminou os ~29 ms de GPU ou a queda de FPS reportada no jogo.

As mudanças ficaram em `akashic-audit-0.1.10/evidence`; nenhum shader, parâmetro de qualidade, número de amostras, resolução ou estado de produção foi alterado por esta auditoria. Revisão executada por agente de engenharia do projeto, sem certificação externa.

## Pipeline e custos identificados no código

- `GlDhSSAOShader.java:114` fixa 6 amostras na geração de AO. `ssao/gl/ao.frag` já evita trabalho no céu e além da distância de AO. Reduzir amostras, raio ou distância modificaria o visual.
- `GlDhSSAOApplyShader.java:101` fixa raio 2: o bilateral percorre 5×5 posições, com 25 leituras AO e 25 leituras de profundidade, além da profundidade central. As exponenciais espaciais parecem redundantes no texto GLSL, mas remover essa redundância não trouxe ganho claro no código compilado pelo Intel. O peso de profundidade torna este bilateral não separável; duas passagens separáveis não seriam a mesma operação.
- `antialias/gl/taa.frag` lê cor atual, profundidade, histórico e oito vizinhos. Fora de `0 < prvCoord.xy < 1`, o peso histórico permanece zero; os oito vizinhos não contribuem para cores finitas. O teste de histórico preto é anterior à mistura e foi preservado nas variantes de isolamento.
- `antialias/gl/sharpen.frag` escreve alpha 1. `GlDhTaaSharpenShader.java:112` ainda habilita blend com fatores que substituem o destino para esse alpha. A remoção do blend é uma oportunidade sem redução de qualidade, mas o ensaio não comprovou benefício consistente.
- Em `LodRenderer.java`, SSAO antecede fog e far fade; AA acontece depois da composição de far fade. Portanto, pular AA por profundidade DH igual a 1 removeria AA também do céu/Minecraft já composto. Isso foi rejeitado.
- Far fade usa uma textura intermediária RGBA16 e uma passagem posterior de cópia; o destino DH padrão é RGBA8 (`GlDhMetaRenderer.java:371`). Trocar os buffers ou fundir as passagens sem preservar conversão, arredondamento, ownership e overrides da API não é equivalência demonstrada.
- Fog também escreve um intermediário RGBA16 antes da aplicação por blend. Fusão direta pode remover tráfego, mas muda onde acontece a quantização; não foi implementada sob o requisito atual.

## Ensaios nativos

`ShaderEquivalenceProbe.java`, `ShaderBlendProbe.java` e `ShaderCasMathProbe.java` compilaram os shaders com LWJGL 2.9.4 e JDK 8u362 no driver **Intel UHD Graphics, OpenGL 4.6.0 - Build 27.20.100.9268**. Foi usado Pbuffer invisível de 16×16, renderizando FBO de 1280×720. Não houve abertura de Minecraft, criação de janela visível, alteração do modpack ou de configurações.

Destino de comparação: RGBA16, leitura exata dos quatro canais por `GL_UNSIGNED_SHORT`. Profundidade: DEPTH_COMPONENT32F, NEAREST/CLAMP_TO_EDGE. AO: R16F, LINEAR/REPEAT. Cor atual TAA: RGBA16, LINEAR/CLAMP_TO_EDGE; histórico: RGBA16, LINEAR/REPEAT. Base e máximo nível de mipmap iguais a zero. As texturas intermediárias reais de AO/histórico não especificam wrap e, portanto, mantêm REPEAT. O ensaio CAS reutilizou a textura sintética de cor atual com CLAMP_TO_EDGE; o alvo histórico real do CAS usa REPEAT, logo o ensaio CAS não homologa suas bordas reais. Esse limite reforça a decisão de não promover a variante.

Três cenas determinísticas:

| Cena | Profundidade e cores | Histórico TAA |
|---|---|---|
| 0 | Profundidade plana 0,99; cores/AO pseudoaleatórios finitos | Reprojeção identidade, válida em toda a imagem |
| 1 | Descontinuidade 0,9995/0,85 no meio da tela | Deslocamento X de 0,6 em NDC: aproximadamente 30% fora |
| 2 | Céu nas bordas e quarto direito; profundidade variando 0,95–0,999 | Deslocamento X de 3: toda a reprojeção fora; faixa de histórico preto |

Cada caso compara uma imagem de cada shader e mede 12 pares A/B, alternando a ordem, depois de três aquecimentos por programa. Cada medida delimita oito draws com dois `GL_TIMESTAMP`; o resultado só é lido após disponibilidade do marcador final. Não há `glFinish`, `glFlush` ou espera ativa. `glReadPixels` sincroniza somente a verificação da imagem, fora do intervalo medido. As medidas são intervalos de conclusão GPU divididos por oito, **não GPU busy, tempo de um frame de Minecraft nem promessa de FPS**. Contenção, clocks dinâmicos e o custo reduzido de transições em lotes limitam extrapolações. Os JSON guardam todos os valores, comandos e hashes dos harnesses; os shaders de referência e variantes estão preservados em `shader-variants/`.

## Três propostas concretas e julgamento

1. **TAA: saída antes do histórico/vizinhos quando reprojeção não é válida — adiada.** Mantém a expressão matemática para cores finitas e evita nove leituras inúteis. Em `taa-earlyonly.json`, a cena toda fora passou de 2,329 a 1,694 ms, sem diferença de canais nessa cena; a parcial passou de 2,109 a 1,938 ms. Porém as cenas 0/1 tiveram respectivamente 28.633/14.039 canais alterados, com diferença máxima de 1/65535. Não é evidência de alteração visível por si só, mas também não é prova de preservação temporal: não se testou acumulação por centenas de frames, transparência dinâmica ou o jogo. Retorno depois do cálculo do peso, mistura explícita e inversão da soma não eliminaram consistentemente as diferenças. A alternativa `taa-skipclamp` preservou todos os canais nas três cenas, mas mediu 2,767→2,735 ms mesmo na cena toda fora; a hipótese de predicação/otimização pelo compilador explica o resultado, sem ter sido provada por assembly do driver.
2. **SSAO: reutilizar pesos espaciais/especializar limites do bilateral — rejeitada nesta entrega.** Reuso espacial manteve todos os canais, mas as medianas A/B foram 5,350→5,390; 5,083→5,165; 4,523→4,404 ms. A especialização com fallback para outros raios preserva 25 taps e a fórmula, mas o ganho foi apenas ~1–3%, com 8–37 canais mudando 1/65535. Fusão de exponenciais mudou arredondamento sem ganho. Adiar a leitura AO até confirmar peso exatamente diferente de zero piorou: 3,802→6,726; 3,874→6,874; 3,195→5,137 ms. Não confundir menos leituras descritas em código com execução mais rápida.
3. **CAS: desabilitar blend de substituição — rejeitada como otimização comprovada.** Saída UNORM16 idêntica nas três cenas; medianas 1,769→1,711; 1,744→1,684; 1,347→1,424 ms, com alta dispersão e sinal inconsistente. LOD explícito também foi exato nessas cenas e sem ganho claro. A simplificação algébrica de inversesqrt/recíproco para sqrt não melhorou e mudou até 1/65535. Não compensa alterar produção apenas pela aparência de simplificação.

## Restrições de equivalência e fontes primárias

A especificação GLSL 3.30 não promete invariância entre programas com diferentes operações ou fluxo. Derivadas em fluxo não uniforme têm limitações; o AO usa derivadas para a normal, portanto novos descartes antes delas exigem cuidado. `texelFetch` não substitui automaticamente amostragem filtrada: bordas REPEAT, CLAMP_TO_EDGE e interpolação precisam ser preservadas. [GLSL 3.30, seções 4.6.1 e 8.7–8.8](https://registry.khronos.org/OpenGL/specs/gl/GLSLangSpec.3.30.pdf).

Blits ignoram a maior parte do pipeline de fragmentos, e ler/escrever a mesma imagem pode criar feedback indefinido. Por isso a cópia fullscreen de fade não foi substituída genericamente por blit ou render direto no próprio texture source. Blend e conversão sRGB também precisam ser considerados. [OpenGL 3.3 Core, seções 4.1.7–4.1.8, 4.3.2 e 4.4.3](https://registry.khronos.org/OpenGL/specs/gl/glspec33.core.pdf).

Os marcadores delimitam a conclusão dos comandos na GPU; disponibilidade deve preceder a coleta para evitar bloqueio. [ARB_timer_query](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_timer_query.txt).

O fullscreen atual usa dois triângulos e interpola UV; substituir por triângulo único pode mudar interpolações/derivadas e deve ser comparado, mas não foi usado como explicação dos milissegundos predominantes. Não houve importação de código do Voxy nem transplante de pipeline moderno. Nesta rodada as fontes determinantes foram o código Akashic efetivamente executado e Khronos, não a suposição de que outro renderer seria automaticamente mais eficiente em 1.7.10.

## Continuidade recomendada

Priorizar nesta entrega as correções de ownership/cancelamento, publicação tardia e acesso de câmera com causa reproduzível. Manter SSAO/AA ativos e os mesmos parâmetros. Uma futura promoção da saída antecipada TAA exige teste temporal completo, comparação após CAS/RGBA8 e cenas reais reproduzíveis; o ganho depende da fração de reprojeção inválida e não resolve sozinho o SSAO predominante. Os JSON desta auditoria documentam hipóteses aceitas para investigação e variantes rejeitadas, sem converter experimento sintético em certificação visual ou de FPS.
