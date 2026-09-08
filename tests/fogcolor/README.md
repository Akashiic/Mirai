# Regressão do acesso à cor de fog — Mirai 0.1.2-dev

O teste executa as instruções de `AngelicaAccessor.getFogColor()` extraídas do
JAR informado. O ASM encaminha diretamente o método para uma classe de fixture
com o mesmo nome e um construtor vazio, sem alterar instruções, descritores ou
constantes do método. A inicialização FML, as interfaces e os demais métodos do
accessor são deliberadamente excluídos dessa fixture.

`FogState`, `FogStateStack`, a interface `IStateStack` do GTNHLib e JOML são
classes reais dos JARs fornecidos. Somente `GLStateManager`
é substituído por um seletor explícito de `FogStateStack`, pois a inicialização real
desse gerenciador exige Minecraft e um contexto OpenGL. O método legado
`getFogColor(): org.joml.Vector3d` dessa fixture mantém o descritor externo real
para permitir que o JAR 0.1.1 reproduza seu `NoSuchMethodError` esperado.
O seletor `getFogState()` preserva o retorno real `FogStateStack`, incluindo seu
descritor binário. Seu construtor real aloca estados Java, sem criar contexto GL.

Esta regressão **não executa Minecraft, mixins, shaders, JNI, GPU nem a
inicialização completa do GLStateManager**. A auditoria separada de ABI em
`tests/angelicaabi` confere os descritores contra o Angelica real. O sucesso dos
dois testes não representa homologação visual ou de servidor.

Cada combinação Angelica/JVM executa um controle negativo do JAR antigo e oito
verificações funcionais do JAR candidato:

1. Estado inicial preto.
2. RGB do buffer e saída opaca, independentemente do canal alpha do buffer.
3. Clamp de valores finitos fora do intervalo 0–1.
4. Clamp de infinitos e zero.
5. Atualização do mesmo estado entre chamadas.
6. Seleção do estado atual a cada chamada, sem capturar o contexto anterior.
7. Leitura absoluta com preservação de posição, limite, marca, conteúdo e estado.
8. Repetição de 128 leituras com preservação dos dados e do estado.

O `FogState` contém intencionalmente um vetor diferente do buffer: o teste
verifica o contrato do caminho de buffer escolhido pelo patch. Isso não afirma
que o Angelica real mantém os dois valores inconsistentes.

Exemplo (caminhos locais fornecidos pelo operador):

```bash
python3 tests/fogcolor/run.py \
  --old-jar /artifacts/Mirai-0.1.1.jar \
  --candidate /artifacts/Mirai-0.1.2.jar \
  --angelica /deps/angelica-2.2.8.jar \
  --angelica /deps/angelica-2.1.5-dev.jar \
  --joml /deps/joml-1.10.2.jar \
  --gtnhlib /deps/gtnhlib-0.11.41.jar \
  --asm /deps/asm-9.9.1.jar \
  --jdk8 /toolchains/jdk8 \
  --jdk21 /toolchains/jdk21 \
  --output /evidence/fogcolor
```

`--candidate` pode ser omitido para executar somente o controle negativo.
Os resultados registram hashes dos JARs, fontes de teste, classe original e
classe extraída, versões dos runtimes, comandos, saídas e contagem dos testes.
Os processos usam `-Xverify:all` e `java.awt.headless=true`; isso não simula GPU.
