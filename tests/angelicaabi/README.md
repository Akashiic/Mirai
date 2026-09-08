# ABI da integração Angelica

O crash da 0.1.1-dev apareceu ao começar a renderizar o mundo, depois do menu.
Shadow transformou o retorno da chamada externa `GLStateManager.getFogColor()`
de `org.joml.Vector3d` para o JOML privado do Mirai. O método com esse descritor
não existe no JAR do Angelica. Os dois tipos não são intercambiáveis na JVM.

`audit.py` lê os class files do JAR final, inclusive entradas multi-release,
sem carregar Minecraft ou criar contexto OpenGL. Verifica:

- Todas as referências de campo/método cujo proprietário está fora do JAR,
  procurando tipos privados introduzidos nas assinaturas externas.
- As classes e os descritores exatos de campos/métodos externos da integração
  Angelica, GTNHLib, Iris e Sodium, contra os JARs fornecidos.
- Herança para resolver `FogStateStack.getFogColorBuffer()`, declarado em `FogState`.
- Integridade ZIP e identidade interna das classes.
- Controle negativo com a 0.1.1-dev: precisa identificar o símbolo exato da falha.

Exemplo, a partir da raiz dos fontes:

```bash
python3 tests/angelicaabi/audit.py /caminho/Mirai-candidato.jar \
  --dependency /caminho/angelica-2.2.8.jar \
  --dependency /caminho/gtnhlib-0.11.41.jar \
  --negative-control /caminho/Mirai-0.1.1-dev.jar \
  --report /caminho/auditoria-abi.json
```

Repita com `Angelica-2.1.5-dev.jar` de compilação para conferir a borda mínima
declarada. Um retorno 0 com controle negativo significa candidato aprovado
neste escopo e versão antiga corretamente rejeitada. Os hashes de todos os
binários examinados ficam no JSON. Nenhum JAR de terceiros está incluído aqui.

O scanner enumera separadamente referências a dependências privadas opcionais
ausentes. No SQLite empacotado, SLF4J é consultado com `Class.forName`; se ausente,
o logger segue pelo JDK. A exceção identifica os nove símbolos usados por esse
único caller e o SHA-256 da classe que contém o guard. Qualquer outro símbolo
privado órfão, mesmo com retorno primitivo, falha. Essa observação não é uma
segunda falha reproduzida.

Este gate não certifica todos os métodos de todas as dependências, os opcodes
de invocação, todas as regras de acesso da JVM, execução de mixins, estado real
da GPU ou conexão Proxy → Paper → Crucible. A aprovação funcional exige execução
no modpack. O teste não substitui o gate de isolamento fastutil nem o de bytecode.
