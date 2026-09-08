# Parecer independente — Akashic: Horizons of The Mirai 0.1.1-dev

Data: 5 de setembro de 2026. Revisão estática independente da implementação e da compilação. Escopo: conflito de classes fastutil no início do cliente e correção de empacotamento correspondente.

## Decisão

**A relocalização condicional do fastutil embutido está aprovada pela inspeção estática. A aprovação do binário depende dos resultados dos testes sobre o JAR final.** Este parecer não certifica a inicialização de Minecraft, Angelica, OpenGL ou Crucible no ambiente do usuário.

## Evidência do problema

O crash `crash-2026-09-05_10.39.53-client.txt` informa `NoSuchMethodError` para `ObjectArrayList.combine(ObjectArrayList)`, durante a inicialização de `ObjectImmutableList`. O caminho passa por `GTNHLib VertexFormat`, `DefaultVertexFormat`, `Angelica SelectionBoxRenderer` e inicialização do estado gráfico. O erro é de ligação entre classes Java; sua presença nessa pilha não demonstra defeito do driver gráfico.

O arquivo `latest(20260905-134020).log`, linhas 257 e 266, registra fastutil **8.5.18**, solicitado por **GTNHLib 0.11.41**. A execução registrada utiliza Minecraft **1.7.10**, **Java 1.8.0_51** e Windows **8.1 amd64**. O Java 21 do servidor não explica este erro do cliente.

O JAR Mirai 0.1.0-dev recuperado tem **44.461.481 bytes** e SHA-256:

`43f6f070afa680be477f3b3fba224c00d4b88e3e62d0ecf4c8ed34dde82167ed`

Esse artefato contém **10.783 entradas** sob `it/unimi/dsi/fastutil/`, das quais **10.772 são classes**. Inclui `ObjectArrayList`, mas não `ObjectImmutableList`. Os fontes fixam a dependência embutida em **8.2.1**. A composição do JAR, a dependência registrada no log e o método ausente sustentam a conclusão de conflito entre classes de versões diferentes no mesmo namespace.

A equipe de regressão reproduziu o mesmo `NoSuchMethodError` com os binários reais: Mirai antigo antes de fastutil 8.5.18 no classloader falha em Java 8 e 21; a ordem inversa e fastutil moderno sozinho inicializam normalmente. A matriz-base contém seis casos com o resultado esperado e está registrada em `crash-evidence/fastutil/baseline-matrix/results.json`. O auditor examinou esse registro; sua execução foi feita pela equipe de regressão. O SHA-256 do fastutil 8.5.18 utilizado é `9094ae67d01d0ad246f886f11ad557fc2e79c72cbf3feed83e1512a8ae90a74a`. São testes reais de ligação entre classes, sem iniciar Minecraft ou criar contexto OpenGL.

## Correção inspecionada

Em `buildSrc/src/main/groovy/dh-loader.gradle`, a configuração de `shadowJar` acrescenta:

```groovy
if (project.hasProperty("embed_fastutil") && embed_fastutil == "true")
    relocate "it.unimi.dsi.fastutil", "${librariesLocation}.unimi.dsi.fastutil"
```

`librariesLocation` já corresponde a `DistantHorizons.libraries`. O resultado pretendido é uma cópia privada em `DistantHorizons.libraries.unimi.dsi.fastutil`, incluindo suas referências no código do Mirai. A condição coincide com a condição de inclusão da dependência no mesmo arquivo. Nesta árvore, apenas `versionProperties/1.7.10.properties` declara `embed_fastutil=true`.

A abordagem mantém a versão interna já utilizada pelo projeto e deixa o namespace público disponível à dependência dos demais mods. Alterar a ordem dos arquivos ou atualizar outra biblioteca global não estabelece esse isolamento. A [documentação oficial do Shadow](https://gradleup.com/shadow/configuration/relocation/) confirma que a relocalização reescreve classes e suas referências; a inspeção do artefato é necessária para confirmar que todas as etapas posteriores preservaram esse resultado.

## Fronteiras verificadas

Foi inspecionado o constant pool das classes do JAR anterior, com comparação dos descritores de métodos e campos:

| Verificação | Resultado da inspeção |
|---|---|
| Referências fastutil na API pública `com/seibel/distanthorizons/api/` | Nenhuma |
| Referências fastutil nos fontes do módulo público `api` | Nenhuma |
| Invocações ou campos de proprietário externo ao DH/fastutil com descritores fastutil | Nenhum |
| Proprietários internos com descritores fastutil | 33; precisam ser transformados conjuntamente |
| Referências diretas fastutil nas classes do loader `forge17` | Nenhuma |
| Uso de `ObjectInputStream`, `ObjectOutputStream`, `BinIO` ou reflexão pelo nome fastutil nos fontes de produção examinados | Nenhum encontrado |

Os usos no código comum compilado concentram-se em coleções internas de wrappers, offsets de formato de vértice e gerenciamento de objetos de sincronização LWJGL2. Não foi identificado contrato de Forge, Minecraft ou integração opcional que exija conservar o tipo fastutil público nesse caminho.

Há métodos e campos Java públicos nas estruturas **internas** do core e dos wrappers que expõem tipos fastutil, por exemplo `IWrapperFactory` e `BlockStateWrapper`. Seus descritores mudarão com a relocalização. Um addon que depende diretamente dessas estruturas pode precisar de adaptação; a ausência de referências na API oficial não certifica addons que acessam detalhes internos.

As outras bibliotecas Java embutidas examinadas já utilizam namespaces privados, incluindo JOML, NightConfig, XZ, LZ4, SLF4J, SQLite, Zstd e os auxiliares do JVMDowngrader. Não foi identificado segundo conflito equivalente relacionado ao crash. Isso não constitui auditoria universal de todas as dependências ou de todos os binários nativos.

## Critérios para o JAR final

1. Nenhuma entrada global `it/unimi/dsi/fastutil/`, inclusive em caminhos de classes com múltiplas versões.
2. Classes privadas fastutil presentes, com identidade interna coerente com o caminho no arquivo.
3. Nenhuma referência residual ao namespace original nos constant pools, descritores, assinaturas genéricas ou strings com nomes de classes. A verificação deve respeitar os limites do nome, sem rejeitar uma mera substring contida em um prefixo privado.
4. Nenhuma exposição nova de fastutil na API pública oficial.
5. Reprodução do conflito usando o JAR 0.1.0-dev e fastutil 8.5.18.
6. Inicialização de `ObjectImmutableList` da versão moderna e das coleções privadas do Mirai no mesmo classloader, com as duas ordens de classpath, em Java 8 e Java 21.
7. Metadados, reobfuscação e bytecode do artefato final verificados pelo processo de compilação.

O auditor preparou um leitor independente de constant pool, `evidence-fastutil/audit_jar.py`, para os critérios estáticos. Os resultados do JAR final e dos testes de execução serão registrados separadamente, com o hash do artefato efetivamente testado. **Este documento não antecipa esses resultados.**

## Limites da aprovação

Mesmo com esses critérios satisfeitos, a aprovação refere-se à correção demonstrada do conflito fastutil. Renderização real, mundo integrado, conexão com o servidor Crucible e o restante do modpack permanecem sujeitos à homologação. Não há fundamento neste crash para exigir troca de Java, retirada de Angelica/GTNHLib ou migração de LWJGL2 como parte da correção.
