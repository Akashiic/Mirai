# Mirai 0.1.2-dev — integração da cor de neblina com Angelica

## Defeito corrigido

Na versão 0.1.1-dev, o cliente chegava ao menu e estabelecia a conexão com o lobby vanilla. Ao iniciar a renderização do mundo, `AngelicaAccessor.getFogColor()` provocava `NoSuchMethodError`: a relocalização do JOML alterava o tipo de retorno esperado da chamada externa `GLStateManager.getFogColor()` para `DistantHorizons.libraries.joml.Vector3d`. O Angelica retorna `org.joml.Vector3d` e não fornece o método com o descritor privado do Mirai.

O novo log registra a criação do mundo, a inicialização do GLProxy e a compilação dos shaders antes dessa exceção. CoreTweaks tenta manter o processo funcionando após o crash. O log identifica a exceção de renderização; não estabelece um defeito de encaminhamento do proxy.

## Alteração

O adaptador lê RGB do buffer de cor pertencente ao estado atual do Angelica, por `getFogState().getFogColorBuffer()`. O contrato atravessa somente tipos do próprio Angelica e `java.nio.FloatBuffer`, que não são relocalizados pelo Mirai.

As leituras usam índices absolutos, preservam posição e limite do buffer e mantêm a limitação RGB ao intervalo 0–1. O estado é consultado em cada chamada, sem guardar a referência de um mundo ou contexto anterior. Não há nova alocação de buffer, reflexão ou consulta direta adicional ao driver.

O contrato foi conferido nos binários Angelica 2.1.5 de compilação e 2.2.8 correspondente ao log. A versão privada do JOML permanece embutida; o isolamento fastutil da 0.1.1 permanece ativo. Identificador do mod, configurações e formato de cache continuam os mesmos.

## Verificação

A verificação de conteúdo bloqueou a primeira build candidata desta versão: o processamento incremental podia remover os arquivos `.lang` gerados na mesma pasta de saída de `processResources`. A geração agora lê os JSONs originais, escreve em uma pasta própria declarada e fornece essa pasta como entrada do processamento de recursos. A entrega exige preservar os idiomas inclusive após uma nova execução incremental com metadados alterados.

A entrega exige auditoria de descritores externos no JAR final, controle negativo usando 0.1.1 e regressão da leitura de cor. Os resultados efetivamente executados e os limites dos testes acompanham o relatório da entrega. Testes sem contexto gráfico não certificam renderização real ou o modpack completo.

Não é necessário instalar um companion no Paper para esta correção. O servidor vanilla é um destino válido para operação cliente do Mirai; a homologação das transições entre destinos continua separada.
