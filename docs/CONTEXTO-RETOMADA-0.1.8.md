# Contexto consolidado e proveniência

Em 8 de setembro de 2026, a memória local consolidada não continha entradas do projeto. A tarefa acessível **“Branch · Backport Distant Horizons”** preservava o histórico da entrega 0.1.7 e o diagnóstico anterior de nuvens, fila gráfica, sincronização e problema C2/Forestry no Oracle Java 8u51. Esse histórico foi confrontado com os anexos; resultados passados não foram apresentados como testes desta rodada.

A tarefa **“Investigar Falha No Servidor”** descrevia um trabalho separado no CubicChunks/Crucible dev.5, incluindo transporte de alturas negativas e integração Bukkit ainda em validação. Isso explica por que há outros componentes experimentais no modpack. Esta rodada não modifica aquela tarefa nem atribui ao Mirai os defeitos que aparecem nas pilhas CubicChunks dev.4.

## Integridade dos anexos

| Item | Resultado |
|---|---|
| Relatório 0.1.7 | SHA-256 coincide: `dd53849826068dff5a5878ede93bfef0a5dcc3c15e3ae096911d8116593ce66b` |
| ZIP de evidências 0.1.7 | Coincide: `4bd3df27bbd9fd0d067e3f96734cd1b19418087e185586d257df4b1d7e2ba504` |
| JAR 0.1.7 encontrado em Downloads | Coincide: `b46cac2c39e63fce350c97687c4ffe36308d380ff84dda4a81db81dfb83e39ac` |
| ZIP de fontes fornecido | Obtido `086c631210bd8d6e0bc3d73a3a8c566fcd465277f228f84ef0890264967c2709`; manifesto externo esperava `426dd06cb3e0fc1922e6f2e08385e8bf3f50e37e3bc97e4b5f4cb11319880888` |
| Manifesto interno dos fontes | Todos os **1.349 arquivos** coincidem com seus hashes; declara candidato `2ae4158f66a3416a165741a8041ed0c4a966973d` |
| Relatório final anterior | Declara commit `d8afab15da319b692d5e753e1c722c5f4042a39f`; evidências registram mudança final em dois arquivos de teste e classes de produção idênticas entre candidatos |

Conclusão: o ZIP recebido é internamente consistente, mas não é o ZIP final identificado pelo manifesto externo. Não foi “corrigido” o hash externo para esconder a divergência. As correções do isolamento do classloader e dependências explícitas de Log4j nos testes foram reaplicadas e verificadas nesta cópia.

O Git desta rodada foi inicializado localmente a partir dos bytes do ZIP, com commit de restauração `195f83f`. Não contém a história original reconstruída artificialmente. As referências upstream e os hashes anteriores permanecem em `MIRAI-UPSTREAM.json`. Os fontes originais extraídos e anexos foram preservados fora da cópia de trabalho.

## Contrato preservado

Minecraft 1.7.10/Forge, API DH 7.1.0, protocolo 15, identidade técnica `distanthorizons`, configuração `DistantHorizons.toml`, backend LWJGL2 e caches existentes. A compilação usa JDK25 e converte o artefato para Java8; isso não altera o Java do launcher. Distância, qualidade, shaderpack, VSync e configurações dos outros mods não foram modificados.

O argumento Forestry já confirmado no histórico não é injetado por este JAR. A homologação do usuário deve conservar suas condições atuais, incluindo argumentos de inicialização, para permitir comparação. Atualizações de Java, CubicChunks ou outros mods devem ser avaliadas como outra variável.
