# 0.1.13-dev

Esta candidata inicia a fase de otimização GPU/renderer do cliente Forge 1.7.10 a partir do checkpoint funcional `0.1.12-dev` (`48b728fe26f4eae6cba39053f349e4a3105a4f7c`). A sessão runtime anterior mostrou que GC/background ficaram muito mais controlados, enquanto o perfil gráfico pesado permaneceu GPU-bound. A captura disponível apontou SSAO como o maior estágio individual observado (~24 ms), seguida por AA, Far Fade, Opaque e Fog; o valor absoluto da captura tinha sobreposição parcial com trace e por isso não é tratado como benchmark isolado.

## Contrato de fidelidade visual

A 0.1.13 não reduz automaticamente distância LOD, resolução, qualidade horizontal/vertical, transparência, SSAO, AA, fog, fade, geometria ou shaderpack. O patch desta candidata preserva explicitamente:

- SSAO `sampleCount=6`;
- SSAO `radius=4.0`;
- `strength=0.2`, `minLight=0.25`, `bias=0.02`;
- fade SSAO em 1600 blocos;
- bilateral blur em resolução integral;
- `gBlurRadius=2`, isto é, kernel efetivo 5×5;
- as mesmas 25 amostras de SSAO e 25 amostras de depth por pixel no blur;
- a mesma função de peso depth-aware e a mesma ordem de acumulação.

Qualquer futura alternativa que reduza qualidade deve ser opt-in explícito e não faz parte deste patch padrão.
## SSAO phase 1

O `apply.frag` da 0.1.12 recalculava pesos Gaussianos espaciais que dependem somente do offset inteiro e de sigma fixo `1.6`. Com blur radius 2, o código-fonte executava 5 avaliações espaciais no eixo Y e 25 no eixo X, além das 25 avaliações depth-aware.

A 0.1.13 calcula uma única vez por fragmento os quatro pesos espaciais possíveis (distâncias 0–3) e reutiliza exatamente esses valores nos dois eixos, já que `g_sigmaX == g_sigmaY == 1.6`. A avaliação depth-aware por tap permanece inalterada. No kernel efetivo 5×5, isso reduz as avaliações espaciais expressas no shader de 30 para 4 por pixel sem remover taps, alterar pesos ou mudar resolução.

Um teste determinístico compara a formulação antiga contra a reutilização para radius 1, 2 e 3, 512 kernels por radius, exigindo igualdade exata de bits `float` no modelo Java. O teste é um gate algébrico; o compilador GLSL/driver Intel real ainda precisa ser homologado visualmente.

## Diagnóstico GPU

O estágio `SSAO` continua medindo o passe completo. Foram adicionados subestágios aninhados `SSAO_RAW` e `SSAO_APPLY`, permitindo separar a geração de AO do bilateral blur/aplicação na próxima captura. As queries só existem durante captura GPU explicitamente solicitada; com o observer desligado, `beginStage()` retorna antes de criar trabalho GL.

Os testes do query ring foram convertidos para derivar a quantidade de queries de `Stage.values().length`, e um novo caso verifica que `SSAO_RAW` e `SSAO_APPLY` permanecem intervalos independentes dentro do estágio SSAO externo.

## Evidência de engenharia

- 1.536 kernels determinísticos de blur (radius 1–3) produziram igualdade bit a bit no modelo `float`;
- suíte core/API completa verde na identidade 0.1.13;
- replay com `-Xverify:all`: core 295 testes em Java 8u411 e Java 21.0.11, 0 falhas; API 5/5 em ambas;
- 45 shaders comparados contra 0.1.12: somente `ssao/gl/apply.frag` mudou;
- parser GLSL do Angelica 2.2.8: 0 erros na baseline e 0 no candidato;
- build Forge 1.7.10 de engenharia aprovado; ABI aprovada com Angelica 2.1.5-dev e 2.2.8 + GTNHLib 0.11.41.

## Limites

Nenhum ganho de FPS/GPU é declarado por testes headless. A aprovação final depende de A/B no mesmo mundo/câmera/configuração, inspeção visual e nova captura GPU. Se houver mudança visual perceptível, erro de shader, regressão Angelica/Iris ou efeito colateral de GL state/lifecycle, a candidata é reprovada independentemente de performance.