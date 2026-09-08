# Parecer de pré-build — Mirai 0.1.2-dev

Escopo: falha de ligação em `AngelicaAccessor.getFogColor()` registrada no cliente durante a primeira renderização do lobby vanilla.

A inspeção identifica uma chamada cujo tipo de retorno foi alterado pelo Shadow, embora o método pertença ao Angelica externo. A correção substitui a travessia de JOML por um buffer Java NIO. Não remove o isolamento de bibliotecas nem atualiza o modpack.

O revisor independente confirmou, nos binários Angelica 2.1.5 e 2.2.8, a existência de `GLStateManager.getFogState()`, a herança de `FogStateStack` e `FogState.getFogColorBuffer()`. Também examinou a atualização conjunta do vetor RGB e do buffer pelas operações do Angelica. O código corrigido lê somente índices absolutos 0, 1 e 2 e não conserva referências entre chamadas.

## Portões para distribuição

- Os descritores de métodos e campos externos usados pelo JAR final devem resolver nas dependências auditadas.
- O controle negativo 0.1.1 deve ser rejeitado pelo novo verificador pelo método exato do crash.
- A regressão da leitura deve cobrir valores, clamp, preservação do buffer, atualização e troca do estado.
- O isolamento fastutil deve continuar intacto no JAR final.
- Metadados, bytecode, commits e hashes devem identificar os mesmos fontes e artefato.

Este documento é anterior à compilação. **O parecer final do auditor e os resultados efetivamente executados acompanham o pacote de evidências separado**, associados ao SHA-256 do JAR final. A aprovação deve permanecer limitada à correção demonstrada; Minecraft, Angelica/OpenGL e transições reais não são presumidos homologados.
