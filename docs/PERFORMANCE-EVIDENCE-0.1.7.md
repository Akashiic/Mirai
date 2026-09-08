# Evidência que orienta a candidata 0.1.7

Esta nota consolida a análise anterior dos logs fornecidos pelo usuário. A retomada de 8 de setembro recuperou os fontes 0.1.6 e reaplicou as alterações após limpeza automática dos arquivos temporários. Resultados antigos de testes isolados não são apresentados como resultados da nova compilação.

## Observações e decisões

| Observação registrada na análise dos logs | Decisão da candidata |
|---|---|
| 39 de 181 amostras do trace em `GL44.nglBufferStorage`, pelo caminho da fila de render | Acrescentar tempos e bytes de upload. Não trocar backend com base apenas nas amostras. |
| 12 amostras no callback de nuvens, incluindo recoloração e inversão da matriz da câmera | Reutilizar a posição já capturada para o passe e preservar a atualização completa dos grupos. |
| O tempo antigo de render LOD começa depois do processamento da fila gráfica | Medir a passagem completa e a fila separadamente, indicando sobreposição. |
| Até 958 pedidos esperando enquanto o suporte negociado era `NONE` | Adiar apenas posições até existir capacidade, retomando com timestamp corrente. |
| Cancelamentos ao descarregar o mundo também apareciam como falha remota | Normalizar a causa encapsulada e contabilizar cancelamento. |
| A fila de carregamento chegou a esvaziar, mas a cadência de frames permaneceu baixa | Não atribuir toda a perda de FPS aos uploads. |
| 25 amostras no limitador de frames Angelica | Considerar espera deliberada; presença na pilha não demonstra defeito. |

O trace analisado cobria aproximadamente 60 segundos, com 181 amostras de pilha e nenhum descarte registrado. A frequência nominal não foi atingida; as frações de amostras não equivalem a percentuais de CPU ou GPU. Não foi feita medição de VRAM, temperatura ou clocks.

O perfil observado tinha LOD 128, qualidades HIGH/HIGH, resolução BLOCK, geração COMPLETE, SSAO e antialiasing ativos, transparência DOUBLE_PASS, distância vanilla 32 e VSync/limite 60. O hardware registrado era Intel UHD, driver 27.20.100.9268 e Java 8u51. Nenhum desses parâmetros será reduzido automaticamente pela candidata.

`previous-1(1).log` era idêntico a `latest(20260906-135433).log` (SHA-256 `96d846e6a3e500f27637afafb2171590763dd02d3eab4bee4ed47866921138e5`). Não são duas sessões independentes. O trace mais recente estava em `Texto colado(20260906-141601).txt`; a ausência de encerramento naquele trecho não comprova crash.

## O que ainda depende do jogo

FPS, fluidez de voo, aparência das nuvens e do horizonte, trocas de dimensão e compatibilidade com o conjunto real de mods precisam de teste no cliente. Testes de código, ABI e empacotamento podem verificar regressões específicas; não produzem uma certificação universal de drivers ou ausência de vulnerabilidades.
