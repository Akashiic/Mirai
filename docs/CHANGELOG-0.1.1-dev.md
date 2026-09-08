# Akashic: Horizons of The Mirai — 0.1.1-dev

Correção de empacotamento para o crash de inicialização reportado em 5 de setembro de 2026. A base é a entrega 0.1.0-dev, preservando seus patches, configurações e limites de homologação.

## Causa

O JAR 0.1.0-dev embute fastutil 8.2.1 no namespace global `it.unimi.dsi.fastutil`. No log atual, GTNHLib 0.11.41 solicita e carrega fastutil 8.5.18. A JVM pode obter `ObjectArrayList` do Mirai e `ObjectImmutableList` da biblioteca moderna. Esta segunda classe exige `ObjectArrayList.combine`, ausente na primeira, e a inicialização termina com `NoSuchMethodError` durante a preparação do Angelica.

A regressão usa o JAR realmente entregue e fastutil 8.5.18 real para reproduzir esse cruzamento. A chamada de GTNHLib/Angelica no stacktrace é o local onde o defeito se manifesta; a correção atua sobre o empacotamento do Mirai.

## Alterações

- O Shadow relocaliza fastutil embutida para `DistantHorizons.libraries.unimi.dsi.fastutil`, incluindo as referências do core e do loader.
- A regra é condicionada a `embed_fastutil=true`; nesta árvore, somente o alvo 1.7.10 usa essa opção.
- A biblioteca global continua pertencendo ao ambiente que a fornece. O hotfix não troca as versões de Angelica, GTNHLib, Hodgepodge ou UniMixins.
- O verificador da distribuição exige a classe fastutil privada e rejeita classes fastutil expostas no namespace global.
- Uma regressão de classloading compara o JAR anterior e o corrigido, com biblioteca externa real, ordem do classpath invertida e execução em Java 8/21.
- A versão passa a 0.1.1-dev para distinguir os artefatos. Em instalação nos dois lados, usar a mesma versão no cliente e no servidor.

## Alcance

Este hotfix não muda o protocolo lógico, o formato de cache nem o código de geração/renderização. A API pública oficial examinada não expõe fastutil; addons que acessam tipos internos do core diretamente podem depender de descritores alterados pela relocalização e exigem validação específica.

A auditoria anterior de ABI examinou Guava, Log4j e Netty. Ela não cobriu esta colisão de fastutil, e seus resultados não devem ser generalizados como validação de todas as bibliotecas. O novo teste inclui explicitamente a coexistência de versões incompatíveis no mesmo classloader.

O relatório da entrega registra os resultados efetivamente executados, o SHA-256 e o commit. O teste de classloading não equivale a iniciar o modpack completo ou homologar o Crucible.
