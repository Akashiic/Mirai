# 0.1.11-dev

Esta candidata trata um ciclo de espera durante fechamento da árvore de LODs e melhora a atribuição dos custos. Não contém promessa de ganho de FPS contínuo.

- `LodRenderSection`/`LodQuadTree`: enquanto a árvore está ativa, a produção continua limitada pela conclusão do upload, inclusive ao cancelar uma seção durante movimento. Ao fechar a árvore, seu trabalhador pode terminar sem aguardar a fila GL do cliente. Os buffers serializados continuam emprestados até terminar a cadeia real de todos os consumidores e da publicação. O builder retorna na thread de origem. Uma conclusão tardia não publica na árvore fechada.
- Diagnóstico `LOD_RENDER_LIST_WALL`: duração da seleção/construção da lista e culling, inclusive espera e chamadas de API nesse escopo. É parte do passe LOD; não somar ao passe, não interpretar como CPU exclusiva ou culling puro.
- Eventos `world_lifecycle`: pares begin/end com ID e duração wall de fechamento do mundo, setup/shutdown de pools, fechamento de banco, limpeza de texturas, chamada explícita de GC e eventos de load/unload. Permitem investigar transições sem inferir duração a partir de mensagens genéricas do ThePatcher. Fila de diagnóstico continua limitada e assíncrona.
- Coletor local de logs: reúne o log normal e todas as rotações Mirai com caminhos distintos e manifesto dos bytes copiados. Não envia arquivos. A coleta com jogo fechado continua preferível.

Preservados: isolamento de mundos/caches, ordem de cleanup, API, LWJGL2, geometria, shaders, efeitos e configurações gráficas. Permanecem as correções de câmera/publicação/leases da 0.1.10. Não foi introduzida reutilização temporal de visibilidade nem redução automática de SSAO, AA ou distância.

Os testes de lifecycle incluem regressões que falham na 0.1.10 e passam nesta candidata, além dos casos anteriores de cancelamento, consumidores concorrentes, exceções e publicação tardia. O relatório de entrega identifica o JAR final, testes executados e limites de homologação.
