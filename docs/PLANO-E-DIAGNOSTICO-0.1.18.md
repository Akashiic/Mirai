# Plano e diagnóstico — Akashic Mirai 0.1.18-dev

## Objetivo da homologação

A 0.1.18 separa dois problemas que não devem ser confundidos: trabalho de render inútil quando o renderer está desligado e custo GPU quando o renderer está realmente desenhando LODs.

O cliente atual usado como evidência estava em `0.1.16-dev` (`716ba89d4eb35ca7c82de6b01d25b0655e13fa41`) com `rendererMode="DISABLED"`. Nessas sessões, `VBO Render Count` permaneceu `0/0`; portanto esses logs não podem certificar FPS do renderer ativo.

## Gate A — renderer desabilitado

1. Fechar o cliente e instalar somente o JAR 0.1.18 candidato.
2. Preservar mundo, cache e `DistantHorizons.toml`; não alterar `rendererMode=DISABLED` para este gate.
3. Confirmar `/miraidiag status` e jogar no mesmo cenário que produziu as sessões 0.1.16.
4. Usar `/miraidiag trace 60` durante deslocamento/carregamento normal.
5. Preservar `logs/akashic-mirai` e `logs/latest.log` ao sair normalmente.

Critério: depois de drenar qualquer trabalho já em voo do startup/toggle, não deve surgir uma nova sequência sustentada de uploads de terrain enquanto o renderer permanecer `DISABLED`. Erros, livelock de dirty/retry ou crescimento de filas reprovam a candidata.

Não é requisito que worldgen, rede, SQLite ou cache parem: o patch bloqueia trabalho de render/geometry/GPU, não a manutenção de dados do DH.

## Gate B — reativação/catch-up

1. Ainda no mesmo mundo, alterar o renderer para `DEFAULT` pela interface normal do DH.
2. Observar a transição sem limpar cache e sem reiniciar.
3. Marcar o início com `/miraidiag mark renderer_enable`.
4. Verificar que LODs voltam a aparecer progressivamente, sem buracos permanentes, crash, corrupção de VBO ou necessidade de relog.
5. Alterar novamente para `DISABLED`, aguardar, e repetir uma segunda reativação.

O comportamento aceito é catch-up progressivo: se uma seção possuía buffer anterior, ele pode permanecer disponível até o refresh. O patch não promete reativação instantânea de áreas novas que ficaram desligadas por longo período.

## Gate C — GPU com renderer ativo

Com `rendererMode=DEFAULT`, fechar o chat e usar `/miraidiag gpu 60` numa cena reproduzível. Fazer uma captura parado e outra em deslocamento moderado, sem mudar qualidade durante cada janela.

Cada amostra válida deve agora combinar os tempos `OPAQUE`, `TRANSPARENT`, `SSAO_RAW`, `SSAO_APPLY`, `FOG`, `FAR_FADE`, `AA`, `COMPOSITE` disponíveis com eventos `gpu_terrain` da mesma captura. Os campos `draws`, `vbo_slots`, `vertices`, `indices` e `max_vbos_per_container` permitem distinguir custo de fragment shader/bandwidth de custo de submissão/draw.

Somente se containers reais mostrarem multiplicidade relevante de VBOs será reaberto o experimento de IBO/batching. Um microbenchmark isolado não autoriza essa promoção.

## Critérios de reprovação imediata

- qualquer mudança visual reproduzível atribuível à candidata;
- LODs que não retornam após `DISABLED → DEFAULT`;
- upload/build contínuo de terrain com renderer desabilitado após o trabalho em voo drenar;
- exceções de lifecycle, publicação após close, VBO id `0`, crash/driver reset ou crescimento não limitado de fila/memória;
- regressão dos testes JVM ou divergência entre `gradle.properties`, `ModInfo.VERSION`, nome do JAR e `build_info.json`.

## Limites das alegações

Os probes SSAO/IBO são ferramentas de laboratório e não são certificados de FPS. Os testes JVM não abrem Minecraft nem provam compatibilidade visual. A promoção final exige logs de uma sessão real 0.1.18 e, para qualquer alegação de ganho GPU, captura com o renderer ativo.
