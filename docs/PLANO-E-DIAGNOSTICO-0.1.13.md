# Plano, auditoria e homologação — 0.1.13-dev

## Checkpoint

A referência desta rodada é a 0.1.12-dev em `48b728fe26f4eae6cba39053f349e4a3105a4f7c`, JAR SHA-256 `0052901c435ef52da5ea6b3b4762dd92983a1a9c67a8949e7ce29f46d5a864de`. O checkout 0.1.12 permanece separado e não é modificado.

A 0.1.12 mostrou ganho no perfil LOW e eliminou, na coleta analisada, o padrão de GCs de vários segundos durante gameplay focado. A captura pesada disponível apontou a GPU como principal limitante e SSAO como o maior estágio individual observado. Portanto a 0.1.13 muda o foco de governança CPU/memória para custo do renderer.

## Regra cardinal: Visual Fidelity Invariant

Uma otimização padrão só pode avançar quando busca produzir a mesma imagem da 0.1.12 para o mesmo estado. Não contam como otimização transparente:

- reduzir Radius;
- reduzir resolução do SSAO ou framebuffer;
- reduzir samples ou blur radius;
- desligar ou simplificar SSAO/AA/Fog/Fade;
- reduzir geometria ou transparência;
- alterar configuração persistida do jogador;
- desabilitar shaderpack/Angelica para obter benchmark melhor.

Essas estratégias só poderão existir futuramente como opção explícita e separada.
## Fase 1 aprovada para candidata

O backend efetivo do Minecraft 1.7.10 foi confirmado no código como `OPEN_GL`; os renderers Blaze3D modernos não são alvo desta rodada. A cadeia relevante é `GlDhSSAORenderer -> GlDhSSAOShader -> GlDhSSAOApplyShader`.

A primeira intervenção é deliberadamente limitada ao bilateral blur do SSAO. O kernel espacial usa sigma X e Y idênticos e offsets inteiros limitados; os pesos espaciais repetidos passam a ser reutilizados. O componente de profundidade, taps, sampling, blending e framebuffer permanecem iguais.

A instrumentação subdivide SSAO em:

```text
SSAO
├─ SSAO_RAW   geração do mapa de AO
└─ SSAO_APPLY bilateral blur + aplicação no DH framebuffer
```

Isso permite que a próxima rodada decida com dados se a continuação deve atacar o primeiro shader ou o apply. Não será introduzida uma segunda otimização GPU agressiva antes dessa medição.

## Portões internos

- G0 — checkout exato da 0.1.12 e worktree isolado: aprovado.
- G1 — baseline `:core:test :api:test` em 1.7.10 antes do patch: aprovado.
- G2 — equivalência matemática do blur, 1.536 kernels, igualdade bit a bit no modelo float: aprovado.
- G3 — lifecycle/cadence do GPU query ring com novos subestágios: aprovado após corrigir somente expectativas de contagem dependentes do número de stages.
- G4 — regressão ampla na identidade `0.1.13-dev`: aprovada; replay `-Xverify:all` em Java 8u411 e Java 21.0.11 executou 295 testes core em cada JVM, 0 falhas, mais 5/5 testes API em cada JVM.
- G5 — build Forge 1.7.10 de engenharia: aprovado (`remapJar -> downgradeJar -> shadeDowngradedApi`); class majors apenas 46/51/52; ABI aprovada com Angelica 2.1.5-dev e 2.2.8 + GTNHLib 0.11.41. O rebuild final ainda deve ocorrer após o commit de release.
- G6 — runtime visual/GPU no Intel UHD + Angelica/Iris: obrigatório e pendente.
## Evidência de fidelidade pré-runtime

A comparação estática entre checkpoint e candidata encontrou 45 shaders em ambos os lados; 44 são byte-identical e a única diferença é `ssao/gl/apply.frag`. `ao.frag`, `GlDhSSAOShader`, `GlDhSSAOApplyShader`, `Config.java` e `AngelicaAccessor` permanecem byte-identical.

O `apply.frag` da baseline e o candidato foram analisados com `GLSLLexer/GLSLParser` sombreados dentro do Angelica 2.2.8: ambos retornaram zero erros léxicos/de parser. O shader empacotado no JAR de engenharia é byte-identical ao source auditado. Isso é gate estático; equivalência visual final continua dependente do driver/runtime real.

## Homologação runtime obrigatória

Usar a mesma configuração pesada que expôs o gargalo na 0.1.12. Não diminuir qualidade para o candidato. Fazer capturas sem `trace` simultâneo sempre que o objetivo for medir GPU absoluta.

1. Validar entrada no mundo e compilação GLSL sem erro.
2. Inspecionar SSAO em superfícies planas, bordas, cavernas, horizonte e transições de profundidade.
3. Comparar 0.1.12 e 0.1.13 na mesma câmera/configuração; qualquer diferença visual inesperada reprova a fase.
4. Executar `gpu 60` ou `gpu 120` isoladamente e observar `SSAO`, `SSAO_RAW` e `SSAO_APPLY`.
5. Comparar GPU Main Pass, SSAO total, RAW, APPLY, FPS e frametime; não inferir ganho a partir de uma única cena curta.
6. Ligar o mesmo shaderpack Angelica/Iris usado na rodada anterior e repetir inspeção de depth/fog/SSAO.
7. Confirmar ausência de novas falhas GL, query failures, lifecycle errors e corrupção de state.

## Critério de aprovação

A candidata só conta como avanço se preservar visual e compatibilidade e, ao mesmo tempo, reduzir custo mensurável ou fornecer diagnóstico necessário para uma cirurgia posterior. Se o driver já otimizar o cálculo antigo e não houver ganho, a transformação pode ser mantida apenas se custo/regressão forem neutros; caso contrário deve ser revertida. Nenhum resultado de laboratório substitui o A/B no cliente real.