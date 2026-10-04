# Akashic: Horizons of The Mirai
## Auditoria da rodada 0.1.10 e entrega da candidata 0.1.11-dev

Data: 08/09/2026. Coordenação de engenharia e três revisões especializadas: logs, compatibilidade/ciclo de vida e renderer/fontes primárias. Revisão interna do projeto, com evidências reproduzíveis; não é certificação externa.

**Veredito: houve avanço na evidência de estabilidade dos caminhos corrigidos, mas esta rodada não comprova melhora nem regressão de FPS.** O desempenho relatado pelo jogador continua sendo um problema aberto. A nova candidata corrige um mecanismo de espera reproduzível durante fechamento da árvore de LODs e mede melhor os custos restantes. Ela ainda exige teste no modpack.

O relatório colado foi tratado como hipótese. Vários cálculos iniciais estão corretos, porém algumas conclusões usam cobertura incompleta ou atribuem causa sem medir o escopo correspondente.

## O que os logs realmente permitem concluir

| Evidência auditada | Interpretação |
|---|---|
| 19.582 registros JSONL válidos; 602 janelas completas e uma parcial | Há muito mais dados do que as seis janelas usadas no relatório recebido. |
| 603 snapshots: 593 GUI/sem foco, sete GUI/display ativo, três gameplay focado | A média global não é um benchmark de gameplay. |
| Dois blocos com snapshots focados nas duas fronteiras, ~10 s | FRAME_INTERVAL médio 55,721 ms, equivalente a ~17,95 FPS apenas nesse recorte. Não é A/B. |
| Nenhuma gpu_sample preservada da 0.1.10 | Os comandos GPU/trace antecedem o início do JSONL conservado. GPU indisponível para comparação, não 0 ms nem falha comprovada do profiler. |
| Sem recorrência observada de Camera.update e contadores Mirai de erro zerados | Compatível com avanço das correções anteriores, dentro da cobertura inspecionada. Não garante ausência de todo defeito. |
| Render_Load máximo 227; Builder 3; filas depois drenam | A pipeline progride, mas não ficou vazia durante toda a sessão. |
| Sync_deferred=0 com FULL/ready em todos os snapshots | O estado da conexão difere do NONE/not-ready dos snapshots com deferred positivo na 0.1.9. Não atribuir a diferença somente à build. |
| Dez rajadas aproximadamente a cada cinco minutos; 1821 commits, todos dentro delas | Há recebimento e atualização reais. Ainda não demonstramos redundância do conteúdo. |

O arquivo previous-1.log tem prefixo de log Minecraft seguido de JSONL Mirai. A auditoria separou os formatos, comparou a sobreposição do log normal e evitou somar ocorrências repetidas. Não atribuímos a mistura ao logger ou ao usuário sem prova da origem. A cobertura JSONL começa cerca de 16,5 minutos depois do início da sessão; o começo útil das capturas não foi recuperado nos arquivos locais adicionais verificados.

Reproduzimos exatamente o recorte inicial do relatório: LOD 8,773 ms, passe cliente 8,914 ms, intervalo 52,540 ms e evento 27,410 ms. **Nesse mesmo recorte, tarefas e uploads GL têm contagem zero**, e o drain da fila custa aproximadamente 0,003626 ms por invocação. Isso não sustenta um novo governor de admissão como solução principal daquele custo contínuo. A fila já possui orçamento por chamada.

Há pressão em outras janelas: 5397 uploads storage, 2.493.694.272 bytes transferidos acumulados, média de 1,192 ms/chamada e máximo de 100,333 ms. Bytes transferidos não são VRAM residente. Uma chamada GL longa já iniciada não pode ser interrompida por um budget entre tarefas; médias/máximos por janela também não separam automaticamente driver, CPU e GC.

O aviso de culling MULTI_DRAW_NOT_INDIRECT pertence ao caminho do Angelica; ele não mede o culling do Mirai. A condição depende do método de desenho resolvido, e não prova sozinha ausência de capacidades OpenGL. Fonte: [Angelica 2.2.8, condição de disponibilidade fixada](https://github.com/GTNewHorizons/Angelica/blob/6d8cbf9c2f46247a87716c10ccbceb5d99385ae8/src/main/java/com/gtnewhorizons/angelica/rendering/culling/GpuCulling.java#L39-L50).

## Decisões e alterações entregues

**Fechamento de LODs:** um trabalhador podia aguardar a conclusão de upload GL enquanto o cliente aguardava o encerramento desse trabalhador. A candidata permite que essa espera termine no fechamento da árvore, conservando os bytes até todos os consumidores e a publicação realmente terminarem. O builder é fechado na thread de origem. Não são liberados buffers em uso nem publicadas conclusões em uma árvore fechada.

O limite normal de produção foi preservado: cancelar uma seção durante movimento, mantendo a árvore ativa, não libera antecipadamente seu trabalhador para acumular mais staging. A liberação está vinculada ao fechamento da árvore. Outras esperas de banco/executores, pausas e o rate limiter continuam possíveis. Não atribuímos os seis segundos entre mensagens do ThePatcher integralmente a esse defeito nem prometemos eliminá-los integralmente.

**Medições:** LOD_RENDER_LIST_WALL cobre seleção/lista/culling dentro do passe LOD. Eventos world_lifecycle registram começo, fim, ID e duração das etapas de fechamento, pools, banco, texturas, GC explícito e eventos de mundo. São wall time e podem se sobrepor; não representam CPU exclusiva nem GPU. O diagnóstico mantém fila limitada e comportamento desativável.

**Coleta:** scripts/collect-mirai-logs.ps1 reúne logs/latest.log e todas as rotações logs/akashic-mirai/*.log em um ZIP novo, com caminhos distintos e hashes dos bytes copiados. Rejeita fontes ausentes/bloqueadas e links/reparse points; identifica mudanças durante a leitura. Complete indica inventário copiado, não snapshot atômico. Não envia dados nem altera os arquivos de origem.

Reprovados nesta candidata: reduzir automaticamente SSAO/AA/fog/distância; reutilizar decisões negativas antigas de culling por 2–8 frames; reaproveitar caches sem identidade autoritativa; adicionar atraso/ramp-up arbitrário de handoff. O governor fica como investigação de rajadas, condicionado à medição e à preservação de progresso/memória. A origem/conteúdo das atualizações periódicas e os warnings de ícones de outros mods permanecem investigações abertas.

Shaders, geometria, configurações gráficas e isolamento de mundos foram preservados. Nenhum JAR/configuração do modpack instalado foi alterado nesta entrega.

## Validação e limites

- Build integrado limpo, alvo Minecraft 1.7.10, remapeamento e JAR distribuível: conferir evidence/final-build.log.
- Suítes recompiladas e executadas em Java 8u362 e 21.0.11. Contagens no formato [executados, ignorados, assumptions, falhas]: core / jdk8u362-b09: [285, 0, 1, 0]; core / jdk-21.0.11.10-hotspot: [285, 0, 1, 0]; api / jdk8u362-b09: [5, 0, 0, 0]; api / jdk-21.0.11.10-hotspot: [5, 0, 0, 0]. Assumption Windows é reportada separadamente, não tratada como teste aprovado.
- Regressões de lifecycle usam as classes de produção e consumidores/filas estritos. O manifesto retirement-verification.json registra 228 resultados esperados, incluindo falhas específicas na baseline e controles bem-sucedidos. Não é execução de Minecraft.
- Novas medições verificadas quanto a resultado, exceções, thread, caminho desativado e ausência de evento por frame. Provas SharedApi confirmam 68 assertions de transição e 11 de shutdown em cada JVM testada.
- JAR final: ABI Angelica 2.2.8/2.1.5, fog/fastutil e continuidade dos recursos de shader/nativos/mixins; testes GPU contra classes do próprio JAR e smoke OpenGL em contexto offscreen isolado. Isso não certifica imagem no modpack nem mede FPS no servidor.
- Coletor: PowerShell 5.1 e 7.6.5, dez testes aprovados e um skip explícito por runtime para symlink de arquivo sem privilégio. Junctions foram testadas; nenhuma fixture acessou o modpack real.

## Próxima rodada

Faça uma coleta curta de 5–10 minutos, mantendo a mesma cena, resolução, efeitos, limite de FPS, mods e condição de cache. Capture 60 segundos parado e depois andando com `/miraidiag gpu 60`, fechando o chat e mantendo foco. Registre markers. Faça o handoff separadamente com `/miraidiag trace 60`, pois o trace adiciona overhead. Saia normalmente logo após o teste e execute o coletor para preservar o início antes da rotação longa.

O plano detalhado e o fluxograma estão em docs/PLANO-E-DIAGNOSTICO-0.1.11.md. A auditoria completa está em docs/ANALISE-LOGS-0.1.10.md. Os pareceres, tabelas dos dez episódios, scripts de reprodução, controles negativos e resultados acompanham o ZIP de evidências. Os logs brutos fornecidos pelo usuário não são redistribuídos nesses pacotes.

## Identidade da entrega

Versão: 0.1.11-dev. Branch: akashic/mirai-0.1.11-dev. Commit: `1484fc07025140f5e5a109c26eb4ed58ebc062de`.

JAR: `Akashic-Horizons-of-The-Mirai-forge17-0.1.11-dev-1.7.10.jar`. SHA-256: `473a4a669811b418e46039e9e9657b2636391da9b4d78243cfa0cdfd5c74b798`.

Fontes e evidências têm manifestos internos verificados. O coletor também está entregue separadamente como Coletar-Logs-Mirai-0.1.11.ps1. O arquivo Akashic-Mirai-0.1.11-SHA256SUMS.txt identifica JAR, ZIPs, coletor e este relatório. Candidata aprovada para teste controlado; ganho de FPS e homologação visual no modpack ainda pendentes.
