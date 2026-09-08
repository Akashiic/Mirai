# 0.1.9-dev — observação GPU opcional

Os logs da 0.1.8 mostram aproximadamente 20–24 FPS em intervalos com o passe wall do Mirai perto de 5–6 ms. Esse contraste justifica medir GPU; não permite atribuir a diferença a um componente específico.

- `/miraidiag gpu [10–120 segundos]` ativa pares assíncronos GL_TIMESTAMP. `gpu stop` encerra GPU; `stop` encerra GPU e trace. Desativado por padrão.
- Seis slots e cadência independente para passes main/deferred; resultado de passe antigo só é lido após disponibilidade. Saturação descarta medições, sem espera ativa ou sincronização forçada.
- Etapas: tarefas, opacos, objetos com/sem SSAO, SSAO, transparência, fog, fade distante, AA e composição. PASS cobre a invocação do cliente Mirai, incluindo setup/restauração e possíveis retornos antecipados.
- GPU e wall time de cada captura ficam pareados. O evento inclui sequência, origem temporal, atraso observado da leitura e indicação `lod_stage`; o horário do evento é o da coleta posterior.
- Controle de capacidade, contador de 64 bits, contexto/thread, recurso alheio de query buffer, falha opcional e liberação de queries próprias.
- Snapshot acrescenta foco do jogo, janela ativa e resolução para contextualizar GUI e pacing. Nenhuma captura de tela ou conteúdo de chat é registrada.
- Schema do log atualizado para 2; tempos wall continuam separados de intervalos de conclusão GPU. As correções 0.1.8 permanecem.

Não foi alterado shader, geometria, distância, efeito visual, qualidade ou configuração do jogador. Não há importação de código Voxy nem governor adaptativo nesta versão. Instrumentação não é promessa de aumento de FPS.

Veja [o diagnóstico e o procedimento de coleta](PLANO-E-DIAGNOSTICO-0.1.9.md). O relatório externo de entrega registra testes efetivamente executados e limites de validação em hardware.
