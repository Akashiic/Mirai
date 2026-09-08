# Akashic Mirai 0.1.7-dev

Candidata de desenvolvimento baseada nos fontes publicados da 0.1.6-dev. O relatório que acompanha o JAR registra quais verificações foram executadas. A aprovação de build candidata não equivale à homologação de Minecraft, Crucible, GPU ou modpack completo.

## Alterações

- Nuvens reutilizam a posição exata da câmera já capturada para cada passe. A atualização de cor continua abrangendo todos os grupos, preservando seu aviso de alteração.
- A sincronização aguarda negociação e permissão do servidor, guardando intenções limitadas em vez de antecipar futuros e consultas extras de timestamp. A retomada utiliza o executor e o ciclo de tick existentes.
- Cancelamentos normais de pedidos, inclusive encapsulados em `CompletionException`, recebem classificação de cancelamento em vez de falha remota.
- O diagnóstico mede a passagem completa do render cliente, a fila de tarefas gráficas, cada tarefa e as chamadas de upload por método. Os bytes registrados são os bytes tentados pela aplicação.
- O contexto da tarefa gráfica é restaurado em `finally`, inclusive em execução aninhada ou propagação de `Error`.

## Contratos preservados e limites

As alterações não reduzem distância, qualidade, transparência ou efeitos; não trocam o backend, driver, protocolo de rede nem a política de geração do servidor. Ganhos de FPS precisam de comparação no mesmo cliente e cenário. Tempos inclusivos se sobrepõem e não devem ser somados como se fossem etapas independentes.

As intenções de sincronização têm limite de 4096 posições por provider. Em saturação, a mais antiga é retirada e o diagnóstico contabiliza o descarte; nova leitura continua podendo solicitá-la. Isso limita memória, mas não garante recuperar intenções ilimitadas sem novas leituras. O cache local permanece disponível.

A falha nativa observada no compilador C2 do Java 8u51 ao compilar `forestry.core.genetics.Genome.getChromosomes` é um problema separado. Esta versão não altera o bytecode do Forestry e não configura argumentos do launcher. O argumento que resolveu o teste do usuário continua necessário no ambiente que apresentou a falha:

```text
-XX:CompileCommand=exclude,forestry/core/genetics/Genome.getChromosomes
```

Não há atualização de dependências do modpack nesta entrega. LWJGL2 e a identidade técnica `distanthorizons` continuam sendo utilizados.

## Ensaio da candidata

Substitua o JAR anterior, mantenha o mesmo Java, argumento, configurações e cache, e percorra a mesma área. Primeiro compare o comportamento sem trace detalhado; depois colete `/miraidiag trace 60` perto do sintoma. Teste também lobby sem DH → Crucible, desconexão/reconexão e mudança de dimensão. Preserve `logs/akashic-mirai` e o log normal da mesma sessão.
