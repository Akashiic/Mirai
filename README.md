# Akashic: Horizons of The Mirai

Fork de estabilização do Distant Horizons para **Minecraft 1.7.10**, cliente **LWJGL2** e homologação dedicada em **Crucible / Java 21**.

A versão **0.1.17-dev** corrige o cooldown ocioso de refresh de beacons revelado pela forense da 0.1.16: a consulta continua no executor de IO e preserva o mesmo intervalo de 2 s após sua conclusão, mas o worker não permanece mais em `Thread.sleep(2_000)`. Trabalho realmente ativo continua sendo aguardado graciosamente; troca de picker e fechamento recebem fences para impedir reserva órfã ou publicação stale. Shaders, Config, AngelicaAccessor, GLState, scheduler/governor e qualidade visual permanecem fora do escopo.

- [Arquitetura, fluxogramas, escopo e critérios de estabilidade](docs/ARQUITETURA-MIRAI.md)
- [Instalação, comandos e coleta de diagnóstico](docs/DIAGNOSTICO-MIRAI.md)
- [Plano de homologação da base 0.1.5](docs/HOMOLOGACAO-0.1.5-dev.md)
- [Alterações desta versão](docs/CHANGELOG-0.1.17-dev.md)
- [Plano e diagnóstico da 0.1.17](docs/PLANO-E-DIAGNOSTICO-0.1.17.md)
- [Auditoria independente da rodada 0.1.10](docs/ANALISE-LOGS-0.1.10.md)
- [Auditoria independente da rodada 0.1.9](docs/ANALISE-LOGS-0.1.9.md)
- [Análise da rodada anterior](docs/ANALISE-LOGS-0.1.8.md)
- [Comparação técnica Voxy e Distant Horizons](docs/VOXY-DH-0.1.8.md)
- [Compatibilidade com o modpack](docs/COMPATIBILIDADE-0.1.8.md)
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

Distribua somente o resultado de `:forge17:shadeDowngradedApi`, em `forge17/build/libs/Akashic-Horizons-of-The-Mirai-forge17-0.1.16-dev-1.7.10.jar`. Arquivos intermediários não substituem esse artefato.

## Licenças e origem

O fork preserva os avisos do Distant Horizons e a licença LGPL v3. Consulte [LICENSE.LESSER.txt](LICENSE.LESSER.txt), [LICENSE.txt](LICENSE.txt), os avisos de terceiros e o [README original](README-UPSTREAM.md). A nova identidade não implica afiliação oficial aos mantenedores do Distant Horizons ou do Crucible.
