# Parecer de revisão — Akashic: Horizons of The Mirai

**Data: 2026-09-05. Decisão: alterações revisadas aprovadas para integração na linha de desenvolvimento; certificação de produção não concedida.**

Esta é uma revisão cruzada entre agentes da mesma sessão, não uma auditoria externa ou certificação independente da organização. A base principal é `b4abe2f26bd1da4ce86ba5323711b2b50de84930`, com core `791070ae3647c1791b057f6269431434369da128`, acrescidos dos diffs locais desta entrega.

O revisor `crucible_audit` inspecionou alterações dos outros autores: inicialização antecipada e separação de lados, integrações opcionais, evento de fog, buffer nativo de shader, branding e bloqueio do atualizador upstream; limites de transporte, filas, remontagem e descompressão; propriedade dos fragmentos e encerramento de sessões, emissores e mundos. Os bloqueadores encontrados durante essa revisão foram devolvidos aos autores e suas correções reexaminadas. Não se afirma uma auditoria integral de cada linha do core ou das dependências.

O delta final de `SharedApi` e `ThreadPoolUtil` está aprovado no escopo inspecionado:

- O executor de entrada é publicado por um campo `volatile`; seu encerramento remove a referência pública antes de descartar tarefas pendentes.
- O encerramento dos quatro responsáveis por pools tolera inicialização parcial e tenta as demais paradas mesmo quando uma falha, preservando os erros.
- Falhas ao encerrar um mundo não deixam esse mundo globalmente publicado. A substituição malsucedida é descartada e as limpezas independentes são tentadas antes de propagar o erro.
- A troca normal preserva o contexto esperado pelos callbacks. O handshake do cliente começa depois da publicação do mundo e da preparação dos pools.

**Evidência conferida pelo revisor:**

| Verificação | Resultado | Alcance real |
|---|---|---|
| `tests/transport/test_shared_api_cleanup.py`, reexecutado com JDK 21 e alvo Java 8 | 68 verificações de troca de mundo + 11 de pools aprovadas | Métodos extraídos da fonte atual, com substitutos explícitos das dependências; não executa Minecraft |
| `testScripts/worldClose/run.py`, reexecutado com JDK 21 e alvo Java 8 | 29 verificações aprovadas, timeout externo de cinco segundos | Fonte real do mundo servidor com dependências simuladas; cobre falhas que antes deixavam futures incompletas |
| Relatórios XML JUnit de core e API, lidos nesta revisão | 76 + 5 testes; zero falhas, erros ou ignorados | Resultados da execução de build da equipe; esta leitura não equivale a reexecutar a suíte nem a iniciar o jogo |
| `git diff --check` | Aprovado | Integridade textual dos diffs; não substitui compilação |

A equipe de build informou compilação real de core, código comum, LWJGL2 e Forge 1.7.10. Este parecer não aprova automaticamente a transformação final de bytecode, o empacotamento ou o carregamento do JAR resultante; esses resultados precisam constar da evidência do artefato distribuído.

As alterações de leases em `InternalServerGenerator`, `GenerationChunkLease` e `ChunkSimulationOwnership` são de autoria deste revisor e **não recebem sua própria aprovação independente**. Foram revisadas por `integration_fixes` e `network_lifecycle_audit`. Os helpers passaram 45 verificações em Java 8 e Java 21; `integration_fixes` também aprovou estaticamente o delta final que serializa a verificação e a remoção atrasada do marcador na thread do servidor. Esses testes não executam o gerador dentro do Minecraft.

Permanecem necessários para produção: identificação por hash dos JARs e configurações reais; carregamento cliente/integrado/Crucible com Java 21; aplicação dos mixins; renderização LWJGL2, Angelica e shaders; recuperação de cache e testes prolongados. O cancelamento dos workers de geração, o encerramento global de tickets/timers e a parada com tarefas de mundo pendentes ainda exigem homologação própria. Os testes de fechamento não garantem um prazo de término para I/O ou callbacks externos que bloqueiem indefinidamente. A integração Hodgepodge pressupõe configuração estável e respeito à thread proprietária; sua API não comprova recuperação de efeitos parciais de terceiros.

Nenhum bloqueador novo foi encontrado no delta final inspecionado. Isso sustenta sua integração de desenvolvimento, sem promessa de ausência de bugs, vulnerabilidades ou incompatibilidades no modpack real.
