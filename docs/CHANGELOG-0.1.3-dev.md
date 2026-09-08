# Mirai 0.1.3-dev — estabilização após auditoria

Alvo: Minecraft 1.7.10, Forge 1614, backend LWJGL2. A identidade interna continua `distanthorizons` para preservar contratos de integração. A marca pública é **Akashic: Horizons of The Mirai**.

| Auditoria 0.1.2 | Implementação nesta atualização |
|---|---|
| INT-01 | Interações remotas rejeitadas no handler servidor; identidade do mundo conferida ao executar; filas de geração separadas por sessão e limitadas. |
| INT-02 / B-01 | Seleção de mixins concentrada na enum do UniMixins. Exclusões Angelica efetivas na seleção; `MixinMinecraft` registrado explicitamente. |
| S-01 | Cache temporário por instância de mundo quando não há identidade estável de backend. Namespace persistente com chave do servidor; fallback de endpoint usa hash. Índices fracos liberam mundos e wrappers descarregados sem apagar arquivos ainda em uso. |
| S-02 | Callbacks LevelInit vinculados à sessão, geração e mundo; reset/unload invalidam trabalho antigo. Fechamento do mundo impede criação/publicação tardia de níveis. |
| S-03 | Publicação JDBC coordenada com fechamento. Conexões/Statements parcialmente adquiridos são fechados; falhas de fechamento são agregadas após tentar os demais recursos. |
| S-04 | Migrador V1 com parada cooperativa e ownership do provider. Apaga fonte V1 somente após gravação V2 confirmada, inclusive para merge sem alterações. |
| R-01 | Estado gráfico capturado antes do passe e restaurado em saída normal ou excepcional; uploads de atlas também têm restauração em `finally`. |
| R-02 | Seis overloads LWJGL2 liberam buffers nativos temporários em falhas Java. |
| R-03 / R-05 | Inicialização de shaders/programas publica estado pronto após sucesso e libera aquisições parciais. |
| R-04 | Streams de shader fechados e leitura UTF-8 explícita. |
| NET-01 | Quotas, recursos em fila e buffers de upload têm finalização idempotente em falha, descarte e cancelamento. |
| NET-02 | Oferta servidor de LOD restrita ao wrapper canônico da dimensão atual do jogador, com revalidação ao longo do pedido. |
| G-01 | Removido loop de até 100 chamadas de unload numa única tarefa; descarregamento devolvido aos ticks normais do provider. |
| G-02 | Falha de ticket impede geração sem retenção; capacidade Forge respeitada sem reflexão; tickets e timers têm fechamento cooperativo. |

Melhorias adicionais: RPLE comum separado da lightmap cliente; transparência obrigatória somente durante shader ativo; restauração de attachments distingue mipmap/face; componentes de caminho temporário compactados para Windows legado. As correções anteriores de ABI/fog, bibliotecas privadas, limites de rede e ownership Hodgepodge permanecem.

O conjunto é destinado à homologação. Testes com fronteiras simuladas e inspeção binária não substituem execução no Windows, Angelica real, proxy e Crucible. O relatório da entrega registra os resultados efetivamente executados e o SHA-256 do artefato.
