# Akashic: Horizons of The Mirai — 0.1.8-dev

Entrega de 8 de setembro de 2026. **Candidata compilada e verificada para teste no jogo.** Foram consolidados o histórico disponível, os fontes e logs da 0.1.7, comparados Voxy/Distant Horizons e auditadas as integrações relevantes. Quatro áreas de produção receberam correções localizadas. Não houve medição de FPS nem execução visual do modpack nesta rodada; portanto, não se declara resolvida toda a queda de desempenho.

## Resultado concreto

| Área | Problema demonstrado | Correção e evidência |
|---|---|---|
| Dados LOD parciais | Colunas não lidas usam uma sentinela zero. O renderer tentava resolvê-la como bloco, causando avisos de mapa vazio ou milhares de consultas desnecessárias. | Reconhece somente a sentinela completa. No cenário reproduzido, consultas de cor caem de **4.033 para 1**; ID zero real, geometria, cor, material e luz permanecem válidos. Três testes passam em Java 8/21; a versão anterior falha nos dois casos afetados. |
| Primeiro upload gráfico | O objeto de buffer recém-criado era destruído antes de receber o primeiro armazenamento imutável. | Reutiliza o objeto ainda não utilizado; preserva substituição em reupload e após falha parcial. Em 128 primeiros uploads: **256 criações + 128 exclusões → 128 criações + zero exclusões** antes da liberação final. |
| Troca de tipo de buffer | Um novo ID podia herdar o tamanho do ID destruído, levando `SUB_DATA` a presumir capacidade inexistente. | Zera a capacidade na criação do ID. Teste com driver simulado estrito reproduz a falha antiga e valida a alocação necessária. |
| Nuvens | Culling alocava 12 vetores temporários por grupo não central. | Reutiliza objetos locais ao handler mantendo a mesma ordem aritmética. **19.020 casos diferenciais por JVM**, além dos callbacks existentes, preservam as decisões. |
| Manutenção da fila gráfica | Um cliente parado podia acumular callbacks; ao voltar, cada callback podia autorizar até 500 ms de trabalho, mesmo se a renderização já tivesse retomado. | Um callback pendente; revalidação na execução; orçamento normal de metade do frame configurado; preservação da fila e retentativa após rejeição transitória. |
| Diagnóstico da fila | Cada agendamento em uma versão `dev` capturava a pilha; a média de uma tarefa recebia o tempo acumulado da fila. | Pilhas somente por opção explícita; média recebe o tempo da própria tarefa. Diagnóstico automático e trace continuam disponíveis. |

Esses números medem operações evitadas e propriedades dos testes. **Não são percentuais de ganho de FPS.** Uma chamada nativa individual de upload ainda pode ultrapassar o orçamento de uma tarefa; o código não tenta interrompê-la à força.

Não foram alterados distância, qualidade, camadas de nuvens, geometria, cores, neblina, SSAO, AA, transparência, VSync, shaderpack, backend LWJGL2, configuração do launcher, protocolo ou dependências do modpack. A instalação ativa, os mundos e o cache do usuário não foram modificados.

## O que seus logs comprovam

Os anexos são bons e permitiram encontrar um defeito real. Há, porém, diferenças importantes entre as sessões:

- **Somente `latest.log` é da 0.1.7.** Os dois `previous` são 0.1.6. O CubicChunks também mudou de dev.3 para dev.4 entre as rodadas.
- A sessão 0.1.7 contém aproximadamente **127 segundos com mundo carregado**, divididos em duas entradas. Ela registra `support=NONE` e nenhum tráfego Mirai; as anteriores tiveram sincronização. Isso impede uma comparação A/B direta.
- Na janela 04:17:39 UTC, a passagem Mirai chegou a **305,763 ms**, a fila a **303,048 ms** e uma tarefa a **301,253 ms**. O maior upload da janela foi **10,013 ms**, e a JVM registrou GC no período. Esses tempos inclusivos **não devem ser somados**; sem trace não se identifica qual tarefa causou a pausa.
- Entre 04:17:04 e 04:17:44 UTC, os intervalos médios por janela ficaram em **84–140 ms**, enquanto a passagem Mirai ficou em **4,96–12,82 ms**. Há custo relevante fora da passagem instrumentada; os registros não separam todas as esperas de apresentação, driver, ticks e outros mods.
- Os dois avisos de mapa vazio foram reproduzidos por sentinelas. **Isso não é prova de corrupção do banco ou de perda de blocos reais.** Não há motivo demonstrado para apagar o cache.
- O log geral contém dois crashes recuperados envolvendo **CubicChunks dev.4**: índice fora de faixa na iluminação chamada por CoreTweaks e um cast inválido no diagnóstico F3. A presença do Mirai na lista de listeners não o torna causador dessas exceções. Esta build não corrige esses componentes externos.

Não há trace de performance ativo nos anexos atuais. As durações são tempo de parede Java, incluindo esperas; não são medições puras de GPU. O relatório de logs nos fontes inclui linhas, horários e agregados para conferir cada conclusão.

## Voxy, Distant Horizons e compatibilidade

A pesquisa examinou revisões fixadas de [Voxy](https://github.com/MCRcortex/voxy/tree/b02ff02683cf6db5d048f0a36626d91ce35b031d), [Distant Horizons](https://gitlab.com/distant-horizons-team/distant-horizons/-/tree/203af029aa0c3bbe7ae920cb70480352f72ccee4) e seu [core](https://gitlab.com/jeseibel/distant-horizons-core/-/tree/a3d675e639e78b9b70771a437ed019bff989f69e). Foram consultadas também as tags específicas das integrações do modpack, incluindo [Angelica 2.2.8](https://github.com/GTNewHorizons/Angelica/releases/tag/2.2.8).

**Decisão de engenharia:** aproveitar princípios compatíveis, com implementação própria sobre o caminho existente. O Akashic já possui greedy meshing, reutilização por worker, prioridade e proteção contra uploads duplicados por seção. Adicionar outra estrutura com os mesmos papéis aumentaria a complexidade sem benefício demonstrado.

O Voxy consultado usa Minecraft/Fabric/Sodium modernos e Java 25; seu renderer depende de compute shaders, comandos indiretos e gestão própria de buffers. O arquivo de licença registra restrição de redistribuição. **Nenhum código, shader ou ativo Voxy foi incorporado.** Coalescência de trabalho, reutilização e controle do orçamento foram aplicados onde havia desperdício concreto. Arena persistente de uploads, fences, culling hierárquico GPU e mudanças de meshing ficam como experimentos futuros, condicionados a perfil do gargalo e prova de equivalência visual. [Código e requisitos](https://github.com/MCRcortex/voxy/blob/b02ff02683cf6db5d048f0a36626d91ce35b031d/build.gradle#L289), [licença registrada](https://github.com/MCRcortex/voxy/blob/b02ff02683cf6db5d048f0a36626d91ce35b031d/LICENSE.md).

Angelica redireciona várias chamadas GL para seu cache de estado; não seria correto presumir que toda consulta GL do Mirai chega ao driver ou adicionar outro cache global. As restaurações existentes foram preservadas. ParallelMipMap foi inspecionado no JAR real: é um coremod que modifica carregamento do atlas e paraleliza mipmaps; o aviso Forge de “non-mod” sozinho não demonstra defeito. Há sobreposição com hooks do Angelica, mas não um conflito causal comprovado nesta sessão. EntityCulling trabalha sobre entidades/tile entities; sua presença não demonstra duplicação do culling dos LODs. As decisões e referências detalhadas estão nos fontes.

## Validação da candidata

| Verificação | Resultado e limite |
|---|---|
| Build limpa Windows | **BUILD SUCCESSFUL**, 4min37s; Gradle 9.4.1/JDK 25.0.4.1. Compilação e transformação do alvo Forge 1.7.10. |
| Suíte Gradle final | **247 testes encontrados: 246 executados, 1 ignorado, zero falhas/erros.** Core: 242, incluindo 1 teste de link simbólico sem suporte/permissão nesta execução Windows. API: 5. Tarefas de teste dos loaders continuam desativadas pelo build herdado. |
| Mesmas classes de teste em Java 8/21 | Core/API aprovados nos dois runtimes Windows: Temurin 8u362 e 21.0.11, com a mesma ressalva do link simbólico. Não equivale ao Oracle 8u51 do jogo. |
| Manutenção da fila no JAR final | 9 testes passam em cada JVM com Log4j legado. O JAR original 0.1.7 falha exatamente nos 7 casos esperados; testes verificam mensagens específicas. |
| Escopo aninhado de tarefas no JAR final | 2 testes passam por JVM; o controle 0.1.6 reproduz as duas falhas esperadas. |
| Ciclo de buffers | Fonte completa `GLBuffer` contra fronteira GL simulada estrita: 922 verificações positivas por JVM e cinco controles negativos específicos. Bytecode final inspecionado para confirmar inclusão da correção; não há benchmark de driver. |
| Dados vazios e nuvens | DTO/transformador reais e casos diferenciais descritos acima, aprovados em Java 8/21. |
| Identidade e empacotamento | Commit/branch/versão corretos; 12.512 classes, todas com formato até Java 8; integridade ZIP aprovada. |
| ABI Angelica | 2.2.8 real do modpack e 2.1.5 de compilação aprovadas nos contratos examinados; controle antigo rejeitado. Não é execução de todos os mixins. |
| Fog | 36 verificações nas combinações Angelica/JVM, com classes reais e contexto controlado. Não substitui avaliação de imagem. |
| Isolamento fastutil | 16/16 cenários aprovados; 10.772 classes privadas, nenhuma classe fastutil no namespace público. |
| Continuidade de recursos | **99/99 recursos** nativos, shaders, texturas de nuvem e configurações/refmap de mixins examinados idênticos ao JAR 0.1.7 original. |
| SQLite empacotado no Windows | **45 verificações por JVM**, Java 8/21: JNI, transações, rollback, Unicode/BLOB, reabertura, foreign keys e integridade. A DLL AMD64 extraída coincide com a embutida. |
| Revisão cruzada | Agentes revisaram os caminhos de produção e testes; não é certificação externa. Não foram encontrados bloqueadores para esta candidata dentro do escopo verificado. |

Os scripts, comandos, saídas e hashes acompanham o pacote de evidências. A primeira execução de preparação incluiu trabalho ainda em andamento; a candidata distribuída vem da build limpa identificada acima. A tentativa inicial de comando PowerShell dividiu o argumento não citado `-PmcVer=1.7.10`; foi interrompida, corrigida e preservada no histórico de evidências. Nenhum artefato dessa tentativa foi distribuído.

## Identidade e integridade

- Versão: **0.1.8-dev**; nome técnico `distanthorizons`; Minecraft 1.7.10.
- Commit dos fontes: `fc3cab1b8939e4f58c359884e3c7d2158c799223`.
- Branch: `akashic/mirai-0.1.8-dev`.
- JAR: `Akashic-Horizons-of-The-Mirai-forge17-0.1.8-dev-1.7.10.jar`, **45.347.821 bytes**.
- SHA-256 do JAR: `da4e797882a2da4387fcd03764b25bd7fa0f15952790be71e046ee151b1ad929`.

Foi detectada e preservada uma divergência nos anexos: o ZIP de fontes recebido tem SHA-256 `086c6312…` e manifesto interno do candidato `2ae4158`, enquanto o manifesto externo esperava `426dd06c…`, associado à entrega final `d8afab15`. Todos os **1.349 arquivos internos** conferem. O relatório/evidências anteriores registram alteração final em dois arquivos de teste, com produção idêntica entre candidatos. O JAR 0.1.7 de Downloads corresponde ao hash final informado. A nova cópia Git registra sua restauração; não se apresenta como uma reconstrução da história original. Detalhes em `docs/CONTEXTO-RETOMADA-0.1.8.md` e `input-integrity.json`.

## Instalação e próxima medição

1. Feche o cliente antes de trocar o JAR. Guarde a 0.1.7 fora de `mods` para retorno e mantenha somente uma versão Akashic/Distant Horizons instalada. Se o servidor também utiliza o mod, alinhe sua versão durante a atualização normal.
2. Preserve cache, configurações, Java, argumentos e demais mods. O argumento Forestry já necessário no seu ambiente não é acrescentado nem removido por esta build.
3. Compare 0.1.7 e 0.1.8 no mesmo percurso e local, com condições equivalentes de cache. Separe o período de carregamento do período parado após os LODs carregarem. Faça a comparação normal sem trace em ambos os lados.
4. Se a queda persistir, use `/miraidiag trace 60` perto dela e `/miraidiag mark fps_drop`. Guarde os diagnósticos Mirai e o log normal da mesma sessão. Uma captura visual ajuda se houver artefato que o log não representa.
5. Registre crashes CubicChunks separadamente. Não trate os segundos de recuperação ou de troca de mundo como FPS normal de uma rota.

O plano cronológico e fluxograma estão em `docs/PLANO-0.1.8-dev.md`. O objetivo ainda aberto de homologação é comprovar a fluidez e a aparência no equipamento do jogador, além das fronteiras cliente/Crucible. Uma melhora estrutural demonstrada não autoriza prometer ausência universal de pausas, erros visuais ou incompatibilidades.
