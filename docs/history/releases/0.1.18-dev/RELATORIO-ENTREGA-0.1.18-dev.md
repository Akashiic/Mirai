# Relatório de Entrega — Akashic Mirai 0.1.18-dev

## Veredito

**APROVADA PARA TESTE CONTROLADO; STABLE PENDENTE DE RUNTIME.**

Commit final: `a77e157826954d41ed746dc23e31d98a0f5ffede`. JAR SHA-256: `29e549b1ce457f0e907585250d5c9cb75d4a64187d4240750e290887c604064e`.

A candidata parte exatamente da 0.1.17 final (`cffe7fdd3db08db32535c6638b020315ae1d8e43`) e não modifica shaders, qualidade, distância, geometria, SSAO, AA ou fog.

## Mudanças promovidas

- staging de uploads do `LodQuadTree` é reutilizado e sempre limpo em `finally`;
- staging do refresh de beacons é reutilizado sob o mesmo monitor, sem mudar query/sort/comparação/publicação;
- `rendererMode=DISABLED` bloqueia novos builds/uploads de render em admissão, início do worker e antes do uploader GPU, preservando `renderDataDirty` para catch-up;
- `gpu_terrain` correlaciona draw calls/VBOs/vértices/índices apenas com passes já amostrados pelo profiler GPU opt-in.

A motivação do hard opt-out é observável: cinco sessões 0.1.16 do cliente estavam com renderer desabilitado, 1.551 snapshots `VBO Render Count` em `0/0`, mas 18.865 escopos de upload GL.

## Evidência de não-regressão

Build final no alvo explícito `1.7.10.properties` / `forge17`: PASS, 43 tarefas. Core: 329 testes, 0 falhas, 1 assumption skip. API: 5/5.

Replay explícito com `-Xverify:all`: Java 8u362 executou core 329/329 e API 5/5; Java 21.0.11 executou os mesmos totais, sem falhas e com o mesmo único assumption skip.

Identity do JAR: PASS em Java 8 e 21. ABI: PASS com Angelica 2.1.5-dev e 2.2.8 + GTNHLib 0.11.41.

Contrato binário contra 0.1.17: 45/45 shaders byte-idênticos; nenhuma classe removida/adicionada inesperadamente; zero mudanças de classe fora do escopo funcional ou da string de versão inlinada. Class majors: 111 classes v46, 197 v51 e 12.228 v52; nenhum major inesperado e nenhuma entrada ZIP duplicada.

Continuidade: 10.772 classes privadas fastutil e as classes `AngelicaAccessor`/`IAngelicaAccessor` são byte-idênticas à 0.1.17 final.

## Experimentos rejeitados

SSAO RAW foi medido em R16F no driver real com parâmetros de produção. A única variante bit-exata não mostrou ganho consistente; variantes mais agressivas mudaram pixels R16F e foram rejeitadas.

O experimento de manter o IBO global bound entre VBOs preservou pixels, mas apresentou ganho CPU pequeno/ruidoso e GPU neutra. Não entrou em produção; `gpu_terrain` foi promovido para decidir a hipótese com dados reais.

## Limites e pendências

Os JARs históricos 0.1.0/0.1.1 usados como controles negativos nas auditorias antigas não existem mais no filesystem atual nem no Git consolidado, portanto esses controles não foram reexecutados. Essa limitação é explícita em `verify-0.1.18-final.json`; a ABI positiva atual e a continuidade binária desde 0.1.17 permanecem verdes.

O cliente real ainda estava usando 0.1.16 durante a coleta que motivou esta rodada. Nenhum resultado desta entrega é apresentado como ganho de FPS com renderer ativo. O próximo gate é runtime com o JAR 0.1.18: primeiro `rendererMode=DISABLED`, depois `DISABLED → DEFAULT`, e por fim `/miraidiag gpu 60` com renderer ativo.

Até essa homologação, a classificação permanece **stable pending runtime**. Não substituir automaticamente o JAR do modpack nem alterar `DistantHorizons.toml` sem um teste controlado.

## Artefatos

JAR final: `Akashic-Horizons-of-The-Mirai-forge17-0.1.18-dev-1.7.10.jar`.

Source ZIP: `Akashic-Mirai-0.1.18-dev-sources.zip`, SHA-256 `a434b4729166cce5c37c9e24e213ca97bcb4c86eb3fd6dbbdbe5d26a07dad6e5`; comentário ZIP aponta para o commit final e não contém `.git` ou diretórios de build.
