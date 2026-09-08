# 0.1.10-dev — câmera e ciclo de vida dos buffers

## Problemas corrigidos

- O tick de LOD podia acessar uma entidade de câmera ausente durante handoff, produzindo a NPE vista na coleta 0.1.9. A consulta para o tick agora recusa câmera/mundo indisponível ou identidade alterada. O chamador interrompe aquele tick antes de consultar posição para a sincronização. Renderer ausente continua permitindo o comportamento normal de rede.
- O render e o tick usavam um vetor mutável compartilhado de `Camera.INSTANCE`. A consulta interna passou a devolver coordenadas próprias por chamada, preservando a interpolação e a inversa da matriz que posicionam a câmera, inclusive em terceira pessoa. A API void anterior da câmera e do módulo de tick permanece disponível.
- Falhas ao converter geometria ou enviar buffers podiam deixar checkouts de memória sem devolução. Os recursos passam a ter liberação garantida no escopo proprietário; buffers de índices permanecem reservados até todos os consumidores de upload terminarem, inclusive se o resultado público for cancelado.
- Geometria incompleta podia permanecer no builder local da thread após erro, afetando a seção seguinte. O caminho de falha agora fecha o builder sem transferir seu conteúdo.
- Seções encerradas ou substituídas recusam trabalhos pendentes e resultados tardios. A publicação valida a seção, o pedido e o level; um upload antigo não substitui geometria válida. Fechamento do container é idempotente.

Os vazamentos e resultados tardios são problemas reproduzidos em testes com falhas/cancelamentos controlados. Os logs desta rodada não demonstram que esses caminhos explicavam o FPS estável ou que sua correção elimina a fila Render_Load observada.

## Pesquisa de GPU

SSAO e AA permanecem alvos de custo relevantes. Foram ensaiadas variantes locais em OpenGL isolado na Intel UHD. As candidatas que preservaram os pixels não apresentaram benefício consistente; outras mudaram arredondamento ou exigiriam validação temporal adicional. Nenhuma alteração de shader foi promovida nesta build. Os shaders, efeitos, filtros, resolução, raio e qualidade gráfica continuam preservados.

O [relatório dos experimentos GPU](PESQUISA-GPU-0.1.10.md) registra cenas, formatos, resultados, fontes e motivos de rejeição.

## Validação e limitações

O teste de câmera compara 12 mil casos com a implementação exata da 0.1.9 e exercita 80 mil consultas concorrentes, além de estados ausentes e alterações de identidade injetadas. Usa JOML 1.10.2 e as matrizes reais do projeto; os colaboradores Minecraft são fixtures. O teste do chamador verifica que o guard impede o restante do tick, com controle negativo que reproduz a falha sem esse guard. Os testes de buffers usam classes de produção completas e filas/recursos controlados, incluindo falhas e cancelamentos.

O relatório que acompanha a entrega registra as execuções Java 8/21, a compilação e os testes do JAR identificado por hash. Esses testes não constituem homologação do modpack, do servidor ou da imagem em jogo. Ainda precisam ser medidos FPS após a atualização, o stall de handoff e o comportamento visual nas cenas reais.

Veja a [auditoria da 0.1.9](ANALISE-LOGS-0.1.9.md) e o [plano de validação](PLANO-E-DIAGNOSTICO-0.1.10.md). Não há mudança de protocolo/identidade persistente de cache nem atualização de mods externos nesta entrega.
