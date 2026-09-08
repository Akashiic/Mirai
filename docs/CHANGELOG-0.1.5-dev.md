# Akashic: Horizons of The Mirai — 0.1.5-dev

Base da atualização: 0.1.4-dev, raiz `dde9e879f3f914ae907e232ad4d756a44f442e94`, core `954b57e6491ee77f9e3e45a602d31467f0033205`.

## Alterações

- **Rede:** consulta de LODs já armazenados no servidor quando o cache do cliente está vazio, através da sincronização existente. A autorização de gerar terreno novo permanece separada. Ausência de dados é uma resposta sem atualização; não cria dados de ar nem grava uma seção fictícia.
- **Ciclo de vida de rede:** limites de fila e revisão da liberação de solicitações e respostas durante fechamento, cancelamento e falha de agendamento. Uma resposta concluída depois da saída não deve publicar dados no mundo seguinte.
- **Desempenho:** `DhFrustumBounds` reutiliza sua matriz privada e usa o teste escalar equivalente do JOML. Remove matrizes, vetores e uma inversão sem consumidor; preserva os planos e as decisões de visibilidade nos testes comparativos.
- **Dimensões:** chunks pendentes exigem identidade do mundo Minecraft, preservando a troca de wrapper para o mesmo mundo. Nomes/identificadores iguais entre mundos diferentes não autorizam reaproveitamento. A consulta de nível também valida a identidade do mundo de origem.
- **Preparação administrativa:** `/dh prepare loaded` encaminha chunks já residentes e válidos pela captura existente do DH. Área e trabalho limitados; status e cancelamento; pausa entre submissões e cancelamento por troca/fechamento de mundo. O comando não importa regiões descarregadas do disco.
- **Identificação:** versão interna corrigida para 0.1.5-dev e atribuições de branch/commit corrigidas. Gradle verifica a constante pública antes de compilar; a validação do JAR confere seu valor e executa o parser empacotado em Java 8/21.

## Contratos preservados

LWJGL2, API existente, identidade técnica `distanthorizons`, formato de persistência, créditos e licenças upstream. A atualização não reduz distância, qualidade, transparência, nuvens, neblina ou antisserrilhado como estratégia de desempenho. Não migra nem apaga caches e não habilita geração distante experimental automaticamente.

## Limites

O ensaio de culling mede alocações e equivalência do resultado; não mede FPS no Minecraft. Grandes cortes, transições expostas, horizonte branco e ordenação de transparência continuam exigindo investigação visual. Não há promessa de correção de todos esses sintomas nesta candidata.

A preparação completa a partir de chunks descarregados foi adiada: o carregador legado tem efeitos de entidade/ticks e uma captura destacada pode perder regras de biomas de mods. O comando `loaded` confirma submissões, não conclusão da persistência nem cobertura integral. O download inicial só entrega LODs efetivamente disponíveis no cache servidor.

Uma região solicitada cuja seção agregada não exista no servidor não inicia uma busca recursiva ilimitada por descendentes. A correção de entrada inicial também deve preservar dados locais que chegarem enquanto a resposta estiver em trânsito: nesse caso a seção local tem prioridade, inclusive se ainda for parcial. A candidata não garante cobertura completa imediatamente ou em um prazo fixo.

A inserção inicial verifica a ausência do registro dentro da mesma trava das atualizações locais; falha de consulta ao banco não é tratada como ausência. Esse novo caminho importa terreno sem sobrescrever seções filhas nem aplicar snapshots de feixes de beacon. Os caminhos existentes de atualização normal, dados locais e atualização ao vivo dos beacons permanecem ativos. Feixes remotos não são prometidos somente pela importação inicial.

As evidências, resultados efetivamente executados e o parecer do JAR final acompanham a entrega. Esta é uma candidata para homologação no cliente/Crucible do usuário.
