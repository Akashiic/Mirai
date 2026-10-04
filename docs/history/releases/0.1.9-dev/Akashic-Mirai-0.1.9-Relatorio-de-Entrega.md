# Akashic: Horizons of The Mirai — entrega 0.1.9-dev

Data: 8 de setembro de 2026. Minecraft 1.7.10, backend LWJGL2, alvo Java 8. Fontes consolidados no commit `fa91834906f7d643b283e42ce2c5113e1dbdc2d2`, branch `akashic/mirai-0.1.9-dev`.

JAR final: **45.366.457 bytes**, SHA-256 `7bc77b4ba6d42ceacfb12f90353d57e45953cc6f37c5dd5d94c31818bea23fab`.

**Resultado:** candidata com diagnóstico GPU opcional, assíncrono e limitado. A rodada anterior foi um avanço na compreensão do problema; os aproximadamente 20 FPS continuam sem solução comprovada. Acrescentar timers não é uma otimização capaz, por si só, de aumentar FPS. Não houve alteração de qualidade, distância, geometria, shaders, fog ou configuração do jogador.

## O que os novos logs realmente demonstram

Foi confirmada a identidade declarada da 0.1.8 e reproduzida a janela citada: FRAME_INTERVAL **41,755 ms**, LOD_RENDER_WALL **5,313 ms**, CLIENT_RENDER_PASS_WALL **5,463 ms**. Isso corresponde a aproximadamente **23,95 FPS naquela janela**, não à média da sessão. Os snapshots anteriores e posteriores registravam uma interface aberta.

Uma seleção reproduzível de 26 janelas do maior episódio contínuo, retirados períodos de entrada/saída, forneceu **46,781 ms por intervalo, aproximadamente 21,38 FPS**, LOD de **5,432 ms** e passe cliente de **5,703 ms**. Em 24 janelas, a última observação de GUI era verdadeira. O relato de baixo FPS é coerente com os logs; ainda falta um ensaio controlado com cena, foco e GUI comparáveis.

Há picos de tarefa de **189,309 ms** e LOD de **368,257 ms**, em janelas com aumentos de **173/244 ms de GC**. A coincidência não permite dividir execução ativa e pausa. Um upload de **14,561 ms** ocorreu sem aumento de GC naquela janela. Estes são alvos adicionais, separados do custo sustentado observado sem uploads.

Persiste a exceção de cast no painel F3 do **CubicChunks dev.5**, recuperada pelo CoreTweaks. A listagem de listeners do Forge não coloca o Mirai na origem dessa falha. Não houve nova ocorrência dos avisos de sentinela FullData; é um sinal favorável, enquanto a prova da correção permanece nos testes da 0.1.8. O aviso de ícone OreQuartz continua como item de compatibilidade visual sem relação demonstrada com o baixo FPS.

O relatório independente completo, linhas exatas, hashes das entradas e programa de reprodução estão em `evidence/relatorio-independente-logs-0.1.8.md` e `evidence/audit_snapshot_logs.py`, dentro do ZIP de evidências. Logs originais, chat e identificadores de servidor não foram redistribuídos.

## Julgamento técnico da proposta recebida

Aprovar GPU Timer Queries como próximo instrumento. Reprovar a atribuição automática do “restante do frame” à GPU: wall time inclui esperas, os escopos se sobrepõem e as médias podem ter contagens distintas. Pares GL_TIMESTAMP observam intervalos entre conclusão de comandos, com possíveis lacunas de submissão; não medem ocupação exclusiva da GPU. Essas distinções seguem a [especificação Khronos ARB_timer_query](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_timer_query.txt).

A captura abrange o passe do cliente Mirai, não o frame inteiro do Minecraft, vanilla/Angelica, VSync ou apresentação. GPU barata dentro desse passe não absolve geração, GC ou trabalho concorrente do mod. Governor adaptativo e substituição de arquitetura Voxy/DH dependem de novos dados e não foram introduzidos nesta candidata. A pesquisa de bindings e convivência com queries está em `evidence/gpu-timer-research.md`.

## O que foi implementado

- `/miraidiag gpu [10–120 segundos]`, padrão 60 s; desativado por padrão. Comandos não executam GL. `gpu stop` encerra GPU; `stop` encerra GPU e trace, mantendo logging básico.
- Seis slots, amostragem de cada quarta invocação main/deferred de forma independente. Resultados antigos só são lidos após disponibilidade; slots ocupados provocam descarte. Sem espera ativa, `glFinish`, `glFlush` ou leitura imediata.
- Etapas de tarefas, terreno opaco, objetos com/sem SSAO, SSAO, transparência, fog, fade distante, AA e composição. Uploads dentro de TASKS podem contribuir para essa etapa; não se apresenta isso como todos os uploads do jogo. Rebuilds CPU permanecem fora do relógio GPU.
- Origem temporal, identificador da captura, sequência, atraso de leitura, wall time pareado e indicação de etapa LOD no mesmo registro. Invocações sem desenho de terreno podem ser separadas na análise. Ausência de etapa é distinta de duração zero.
- Validação de capacidade/contador de 64 bits; proteção de thread/contexto; leitura adiada se houver query buffer alheio vinculado; falhas do observador desativam somente a instrumentação. Expiração/saída para menu liberam queries próprias. Em perda de contexto, nomes antigos nunca são excluídos no contexto errado; a retenção é limitada até destruir o contexto original.
- Snapshot de foco, janela ativa e resolução. Analisador Python sanitizado, com estatística por tipo de passe, deduplicação e tratamento explícito de ausência/conflitos. Logs passam ao schema 2.

## Validação e limites

A suite Gradle contém **278 testes core e 5 API**, sem falhas; um teste core de symlink é ignorado por limitação do ambiente Windows. O mecanismo novo possui **36 testes de ring/fachada**, aprovados isoladamente em Java 8u362 e 21.0.11. O backend passou **84 verificações por JVM**, com cinco mutações negativas detectadas. O analisador passou sete testes e classificou corretamente o log 0.1.8, sem GPU, como indisponível.

A compilação limpa terminou com sucesso em **3 min 11 s**. A suite completa foi repetida em **Java 8u362 e 21.0.11**, com **282 aprovações, uma condição ignorada e zero falhas por JVM**. Os 36 testes novos também passaram diretamente contra as classes do JAR final em ambas as JVMs, com verificação de origem das classes e bytecode Java 8; o JAR permaneceu com o mesmo hash.

Também houve teste **nativo** num Pbuffer offscreen de 16×16, em processo separado, sem abrir Minecraft nem alterar o modpack: Intel UHD, OpenGL 4.6, driver `27.20.100.9268`, contador de 64 bits e caminhos core/ARB funcionando. Este teste confirma a viabilidade local das queries; não mede FPS, nem substitui uma sessão com Angelica, servidor e cena real. O Java usado nos testes é diferente do Oracle 8u51 registrado na sessão do usuário.

Uma segunda execução nativa utilizou **backend, ring e serviço LWJGL2 carregados do JAR final**: **42 verificações aprovadas**, incluindo contexto/thread, query buffer ocupado, leitura adiada e criação/exclusão de 22 queries. O processo durou aproximadamente 1,1 s e destruiu seu contexto independente. Continua sendo um teste pequeno de infraestrutura, sem renderizar a cena Minecraft.

Passaram os gates do JAR para identidade/Java 8, ABI do Angelica **2.2.8** e **2.1.5**, cor de fog e isolamento de Fastutil, incluindo controles negativos históricos. A comparação de **99 recursos** preservou shaders, texturas de nuvens, natives e JSONs de mixins em relação à base 0.1.7. Esses checks verificam contrato e conteúdo; não equivalem a homologação visual do modpack em execução.

O custo da captura ainda precisa ser comparado no jogo. `GPU_OBSERVER_WALL` registra chamadas do observador; não equivale a custo total por frame. A cadência pode deixar de observar eventos raros, e resultados atrasados pertencem à origem registrada, não necessariamente à janela de publicação. Não há certificação visual ou promessa de ganho de FPS nesta entrega.

## Próximo teste, sem mudar qualidade

1. Com Minecraft fechado, substituir o JAR anterior pelo **Akashic-Horizons-of-The-Mirai-forge17-0.1.9-dev-1.7.10.jar**, mantendo a configuração. Guardar a versão antiga fora de `mods`.
2. No mesmo local, aguardar estabilização e registrar 30 s com janela em foco, sem GUI. Usar `/miraidiag mark baseline` e fechar o chat.
3. Usar `/miraidiag gpu 60`, fechar o chat e repetir a mesma cena/percurso. A resposta inicial é um pedido; o log informa `recording`, indisponibilidade ou falha efetiva.
4. Registrar mais 30 s sem captura. Se necessário, fazer uma janela separada com `/miraidiag trace 60` para CPU/GC. Evitar F3 até resolver sua falha externa; não é necessário para estes comandos.
5. Preservar `logs/latest.log`, `logs/akashic-mirai/latest.log` e segmentos `previous-*.log` da sessão. Relatar FPS observado e comportamento visual.

O plano com metas, ordem de investigação, fluxograma e critérios está em `docs/PLANO-E-DIAGNOSTICO-0.1.9.md` no ZIP de fontes. Os arquivos desta entrega recebem hashes no `Akashic-Mirai-0.1.9-SHA256SUMS.txt`; o JAR final é o artefato sombreado e rebaixado para Java 8, não um intermediário `-all` ou `-dev`.
