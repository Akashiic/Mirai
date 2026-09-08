# Regressão do conflito fastutil observado na inicialização

O log `latest(20260905-134020).log` registra GTNHLib 0.11.41 adicionando `it.unimi.dsi:fastutil:8.5.18` (linhas 257 e 266). O crash falha na resolução de `ObjectArrayList.combine(ObjectArrayList)` durante a inicialização estática de `ObjectImmutableList`, chamada pelo construtor de `GTNHLib VertexFormat`.

O JAR Mirai 0.1.0-dev distribuído contém 10.772 classes fastutil no namespace público `it.unimi.dsi.fastutil`, incluindo `ObjectArrayList`, porém não contém `ObjectImmutableList`. Carregá-lo antes da versão 8.5.18 mistura classes de versões diferentes. O teste reproduz essa falha com o JAR real da entrega anterior e o binário real do Maven Central, sem classes substitutas. A exceção externa pode ser `BootstrapMethodError`, dependendo da JVM; a causa exigida é o `NoSuchMethodError` do mesmo método informado no crash.

A correção deve reservar a fastutil embutida do Mirai a `DistantHorizons.libraries.unimi.dsi.fastutil`. O teste exige que a fastutil moderna continue funcional nas duas ordens de carregamento e que as coleções privadas do Mirai funcionem independentemente da versão externa.

## Fontes e binários oficiais

- [fastutil 8.5.18 no Maven Central](https://repo.maven.apache.org/maven2/it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar): SHA-256 `9094ae67d01d0ad246f886f11ad557fc2e79c72cbf3feed83e1512a8ae90a74a`.
- [GTNHLib 0.11.41](https://github.com/GTNewHorizons/GTNHLib/releases/tag/0.11.41): o JAR oficial contém `META-INF/gtnhlib_deps.json` requisitando fastutil 8.5.18 e não inclui classes fastutil.
- [Fonte de VertexFormat na versão 0.11.41](https://github.com/GTNewHorizons/GTNHLib/blob/0.11.41/src/main/java/com/gtnewhorizon/gtnhlib/client/renderer/vertex/VertexFormat.java): a construção de `ObjectImmutableList` está no construtor.
- [Angelica 2.2.8](https://github.com/GTNewHorizons/Angelica/releases/tag/2.2.8): o JAR oficial também não inclui classes fastutil.

A origem dos binários oficiais é registrada separadamente da identidade dos arquivos instalados pelo usuário, cujos hashes não foram fornecidos.

## Execução

Requer Python 3.11 ou posterior, JDK 8 e JDK 21. A execução não baixa arquivos nem modifica os JARs. Cada caso usa uma JVM nova com `-Xverify:all` e um `URLClassLoader` sem pai, evitando que o classpath do teste esconda a colisão.

```bash
python3 tests/fastutilcollision/run.py \
  --baseline /caminho/Akashic-Horizons-of-The-Mirai-forge17-0.1.0-dev-1.7.10.jar \
  --candidate /caminho/Akashic-Horizons-of-The-Mirai-forge17-0.1.1-dev-1.7.10.jar \
  --fastutil /caminho/fastutil-8.5.18.jar \
  --jdk8 /caminho/jdk8 \
  --jdk21 /caminho/jdk21 \
  --output /caminho/evidencias
```

Sem `--candidate`, executa apenas os controles e a reprodução anterior. Uma execução completa avalia oito casos em cada uma das duas JVMs:

| Caso | Resultado exigido |
|---|---|
| fastutil moderna isolada | Coleção imutável funciona |
| Mirai anterior antes da moderna | Reproduz exatamente o erro de método ausente |
| Mirai anterior depois da moderna | Coleção imutável funciona |
| Mirai corrigido antes da moderna | Coleções públicas vêm da moderna |
| Mirai corrigido depois da moderna | Coleções públicas vêm da moderna |
| Coleções privadas sem fastutil externa | Listas de objetos e longs funcionam |
| Coleções privadas antes da moderna | Coleções privadas vêm do Mirai |
| Coleções privadas depois da moderna | Coleções privadas vêm do Mirai |

Nos dois casos de coleções privadas com a dependência externa, o mesmo classloader também constrói coleções públicas da fastutil 8.5.18 antes e depois das operações privadas. As origens de ambos os namespaces são verificadas para comprovar a coexistência.

O teste também rejeita qualquer entrada residual em `it/unimi/dsi/fastutil/` no JAR corrigido. O relatório `results.json` registra comandos, JVMs, SHA-256, expectativas e resultados individuais; os logs preservam a origem efetiva das classes e a cadeia de exceções.

A comparação de origem converte a URL `file:` emitida pelo Java para caminho local antes de comparar. Isso preserva a verificação do arquivo exato também no Windows (`file:/C:/...`), em caminhos com espaços e nas formas equivalentes de URL. Uma origem ausente, duplicada, não local ou diferente continua reprovada.

Este teste comprova a regressão de empacotamento e resolução de classes. Ele não inicia Minecraft, Forge, a renderização do Angelica nem um contexto OpenGL e não equivale à homologação do modpack completo.
