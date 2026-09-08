# Compilação e rastreabilidade

## Fontes

A base principal é `b4abe2f26bd1da4ce86ba5323711b2b50de84930`, com core `791070ae3647c1791b057f6269431434369da128`. O arquivo `MIRAI-UPSTREAM.json` registra URLs e referências. O relatório de entrega informa os commits locais resultantes dos patches. O pacote de fontes contém o core modificado dentro de `coreSubProjects`; não o substitua pelo submódulo original ao reconstruir o pacote.

O ZIP de fontes não contém o jogo, caches Gradle, toolchains ou JARs privados do modpack. Dependências de compilação são obtidas pelos repositórios configurados no build. Os checksums efetivamente resolvidos acompanham a evidência da entrega; dependências transitivas podem selecionar versão diferente da declaração direta.

## Pipeline observado

| Etapa | Ferramenta ou alvo |
|---|---|
| Daemon | JDK 25 conforme `gradle/gradle-daemon-jvm.properties` |
| Wrapper | Gradle 9.4.1 com SHA-256 fixado |
| Core e API | Compilação com alvo Java 8 |
| Código comum e loader | Compilação intermediária com `--release 17` |
| Pacote Forge 1.7.10 | `shadowJar → remapJar → downgradeJar → shadeDowngradedApi` |
| Distribuição | Formato de classe Java 8, major 52 ou inferior |

O daemon, o compilador intermediário e o Java usado para executar Minecraft são requisitos distintos. Uma classe major 52 não prova que suas dependências ou APIs sejam compatíveis em runtime.

## Comandos

Instale JDK 25 para executar o Gradle e deixe JDK 8 disponível à descoberta de toolchains. O JDK 21 serve aos testes separados e ao alvo do servidor. A seleção explícita de toolchains pode usar a propriedade Gradle `org.gradle.java.installations.paths` com caminhos locais válidos.

```bash
./gradlew -PmcVer=1.7.10 --max-workers=4 --no-parallel build
python3 scripts/verify-mirai-jar.py forge17/build/libs/Akashic-Horizons-of-The-Mirai-forge17-0.1.7-dev-1.7.10.jar
```

O verificador requer Python 3.11 ou posterior. Para checkout Git, `--expected-commit` compara o commit embutido ao commit esperado. Ao reconstruir a partir do ZIP sem Git, informe `-PinfoGitCommit=<commit-do-relatório>` e `-PinfoGitBranch=akashic/mirai-0.1.7-dev` para preservar a proveniência declarada.

Não é necessário desativar TLS nem modificar bibliotecas do jogo. No ambiente desta sessão, houve configuração do proxy fornecido ao processo de build e download de toolchains verificado por SHA-256. Esse ajuste de ambiente não é uma recomendação de configuração para o launcher do usuário.

## Regressões e limites

A tarefa geral executa os testes JUnit do core e da API. As tarefas de testes dos loaders são desativadas no upstream; isso deve ser considerado na leitura do resultado. Os harnesses em `tests/` e `testScripts/` têm instruções próprias, fronteiras simuladas explícitas e execuções separadas em Java 8/21 quando indicadas.

A primeira resolução da base intacta foi interrompida depois de remapear Minecraft/Forge e aplicar os access transformers, antes de compilar o mod. Não existe nesta sessão uma build-base integral nem comparação de desempenho antes/depois. O build integral comprovado é o dos fontes modificados.

O pacote preserva também as classes opcionais do backend LWJGL3 que o upstream já incluía. O classpath da transformação é completado para que essas classes sejam analisadas corretamente, sem acrescentar bibliotecas LWJGL3 nem alterar a escolha LWJGL2 nesta versão.

O objetivo é rastreabilidade até fontes e dependências. A equivalência byte a byte entre reconstruções em outra máquina ainda não foi certificada.

## Recuperação e hotfix 0.1.1-dev

Os fontes da entrega anterior foram restaurados do ZIP publicado e cada arquivo foi conferido pelo SHA-256 do manifesto. O histórico Git local foi reinicializado; seus commits de restauração não são apresentados como os commits originais. `MIRAI-UPSTREAM.json` preserva as referências e hashes anteriores. A nova build deve registrar o commit local do hotfix e distribuir os fontes correspondentes.

## Retomada 0.1.7-dev

Em 8 de setembro de 2026, os 1344 arquivos da 0.1.6 foram restaurados do pacote publicado e verificados contra o manifesto. O trabalho temporário da tentativa anterior de 0.1.7 havia sido removido pela manutenção do ambiente; as correções foram reaplicadas e precisam dos resultados desta nova execução. O novo histórico é uma cópia local consolidada, incluindo o core modificado. Não execute `git submodule update` sobre o core distribuído.

O relatório final informa separadamente a compilação integral, os testes executados pelo Gradle, a execução dos mesmos testes em Java 8/21 e os ensaios do JAR empacotado. Os testes headless não iniciam Minecraft, Crucible ou OpenGL. A versão de Java usada para compilar não substitui a versão que reproduziu o problema nativo no cliente do usuário.

## Portões adicionais da 0.1.2-dev

Depois do empacotamento final, execute a auditoria de ABI contra os JARs reais das dependências. A compilação por si só não detecta descritores externos alterados durante o Shadow:

```bash
python3 tests/angelicaabi/audit.py forge17/build/libs/Akashic-Horizons-of-The-Mirai-forge17-0.1.7-dev-1.7.10.jar \
  --dependency /caminho/angelica-2.2.8.jar \
  --dependency /caminho/gtnhlib-0.11.41.jar \
  --negative-control /caminho/Akashic-Horizons-of-The-Mirai-forge17-0.1.1-dev-1.7.10.jar \
  --report /caminho/abi-final.json
```

Repita a auditoria com Angelica 2.1.5 de compilação. Execute também a regressão de cor em `tests/fogcolor/` e a regressão de isolamento em `tests/fastutilcollision/`, seguindo seus READMEs. Elas são portões explícitos de distribuição: não são executadas automaticamente pela tarefa Gradle upstream e não iniciam o jogo. Inclua seus comandos, resultados e hashes no pacote de evidências.

## Identidade 0.1.5

`:api:verifyReleaseVersion` compara a constante pública `ModInfo.VERSION` com `mod_version` antes da compilação. O verificador de JAR aceita `--expected-version`, `--expected-commit` e `--expected-branch`, e lê o atributo `ConstantValue` da classe empacotada.

`tests/release/run_identity.py` executa as classes reais de identidade do JAR final com Java 8/21 e Log4j API do ambiente legado. Os controles negativos da 0.1.4 comprovam que o teste detecta a versão interna antiga e as atribuições invertidas. A alteração preserva a constante pública e não acrescenta uma dependência de runtime.
