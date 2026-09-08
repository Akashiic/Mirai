# Akashic: Horizons of The Mirai

Fork de estabilização do Distant Horizons para **Minecraft 1.7.10**, cliente **LWJGL2** e homologação dedicada em **Crucible / Java 21**.

A versão **0.1.7-dev** é destinada a testes. Compilação, testes isolados e revisão de código não substituem execução real do modpack. O projeto preserva a identidade técnica `distanthorizons`, a API e os créditos do upstream; não deve ser instalado junto com outro Distant Horizons.

- [Arquitetura, fluxogramas, escopo e critérios de estabilidade](docs/ARQUITETURA-MIRAI.md)
- [Instalação, comandos e coleta de diagnóstico](docs/DIAGNOSTICO-MIRAI.md)
- [Plano de homologação da base 0.1.5](docs/HOMOLOGACAO-0.1.5-dev.md)
- [Alterações desta versão](docs/CHANGELOG-0.1.7-dev.md)
- [Evidências que orientam as alterações de desempenho](docs/PERFORMANCE-EVIDENCE-0.1.7.md)
- [Histórico de revisão da 0.1.2](docs/PARECER-0.1.2-dev.md)
- [Compilação e rastreabilidade](docs/COMPILACAO.md)
- [Inventário extraído do log do usuário](docs/inventory-log-20260905.json)
- [Referências upstream fixadas](MIRAI-UPSTREAM.json)

O relatório da entrega e seus hashes acompanham o JAR e o pacote de fontes. Esse relatório identifica a build efetivamente executada e os limites da aprovação.

## Construir

O daemon Gradle utiliza JDK 25; o alvo distribuído é Java 8, após remapeamento e transformação JVMDowngrader. Use o wrapper e selecione explicitamente o alvo:

```bash
./gradlew -PmcVer=1.7.10 build
```

Distribua somente o resultado de `:forge17:shadeDowngradedApi`, em `forge17/build/libs/Akashic-Horizons-of-The-Mirai-forge17-0.1.7-dev-1.7.10.jar`. Arquivos intermediários não substituem esse artefato.

## Licenças e origem

O fork preserva os avisos do Distant Horizons e a licença LGPL v3. Consulte [LICENSE.LESSER.txt](LICENSE.LESSER.txt), [LICENSE.txt](LICENSE.txt), os avisos de terceiros e o [README original](README-UPSTREAM.md). A nova identidade não implica afiliação oficial aos mantenedores do Distant Horizons ou do Crucible.
