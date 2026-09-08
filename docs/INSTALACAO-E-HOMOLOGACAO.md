# Instalação e homologação — Akashic: Horizons of The Mirai 0.1.0-dev

**Destino desta build: ambiente de teste. A implantação em produção permanece congelada.** Este guia prepara a execução do JAR final quando a compilação e a conferência do pacote forem concluídas. Compilar o mod e passar testes isolados de Java não certifica sua execução no Minecraft, a transformação dos mixins, o comportamento da GPU ou o funcionamento do Crucible com o modpack completo.

## 1. Qual arquivo instalar

O artefato de execução esperado é:

`Akashic-Horizons-of-The-Mirai-forge17-0.1.0-dev-1.7.10.jar`

Use somente o arquivo de execução identificado no manifesto de entrega, depois de conferir seu SHA-256 final. Arquivos `-sources.jar`, `-dev.jar`, `-all.jar` e arquivos de diretórios temporários do build não são substitutos automáticos do artefato final: algumas etapas ainda precisam remapear classes, transformar bytecode e empacotar dependências.

O mesmo JAR Forge é destinado ao cliente e, opcionalmente, ao servidor dedicado. Instale-o na pasta **`mods`** da instância correspondente. Não o coloque em `plugins`, não o execute como substituto do Crucible e não o instale no proxy Velocity. Não há companion Bukkit/Spigot separado entregue ou exigido nesta etapa.

Durante a preparação deste guia, um JAR preliminar foi aberto apenas para conferir estrutura. Ele continha `mcmod.info` com nome e versão do Mirai, core, backend LWJGL2, coreplugin Forge, configuração de mixins, traduções `en_US.lang`/`pt_BR.lang` e textos de licença. Esse exame não congela sua identidade: **nenhum hash preliminar deste guia vale como hash final de distribuição**. A revisão final do pacote precisa confirmar novamente essas propriedades.

## 2. Ambiente e dependências

A base de compilação seleciona Minecraft **1.7.10**, Forge **10.13.4.1614-1.7.10**, backend **LWJGL2 2.9.1** e bytecode de destino Java 8. O ambiente dedicado pretendido pelo usuário é **Crucible com Java 21**. O commit público do Crucible examinado serve como referência de código e não identifica o JAR efetivamente instalado no servidor.

| Componente | Classificação para instalação | Procedimento nesta homologação |
|---|---|---|
| Minecraft 1.7.10 e loader Forge compatível | Obrigatórios | No cliente, usar a instância Forge do pack; no servidor, a cópia de teste do Crucible já utilizada. |
| UniMixins com Mixin e API GTNHMixins | Obrigatório onde o Mirai estiver instalado | O coreplugin referencia `com.gtnewhorizon.gtnhmixins`. Essas classes e o runtime Mixin não estavam embutidos no JAR preliminar. No log do cliente consta a variante `+unimixins-all-1.7.10-0.3.1.jar`; conferir sua presença e a instalação correspondente no servidor de teste. |
| LWJGL2 | Backend gráfico do cliente | Manter a configuração LWJGL2 da instância. Não há requisito de instalar LWJGL3 ou lwjgl3ify para este alvo. |
| Angelica | Integração gráfica opcional do Mirai, cliente | O log registra **2.2.8**. Se já faz parte do pack, mantê-lo no primeiro teste, com shaders desligados. O accessor contém verificação mínima **2.1.5**; isso não certifica automaticamente toda versão posterior. |
| Hodgepodge | Integração opcional, cliente/servidor conforme o pack | O log do cliente registra **2.7.189**. Sua presença não significa que a supressão de simulação esteja ativa ou funcione no caminho híbrido do Crucible. |
| GTNHLib | Dependência do ecossistema do pack | O log registra **0.11.41**. Preservar quando exigida por Angelica ou outros mods. Não foi identificada referência direta à API GTNHLib nos bytecodes Mirai examinados; não confundir com GTNHMixins. |
| GregTech e RPLE | Integrações opcionais já existentes | Não instalar somente para completar uma lista de dependências de compilação. Se presentes no pack, homologar suas integrações posteriormente. |
| Core DH, bibliotecas empacotadas e backend Mirai | Componentes do artefato | Usar o JAR final completo; não copiar JARs de desenvolvimento das bibliotecas para `mods`. |

A anotação `@Mod` declara `after:angelica`, que expressa ordenação quando Angelica existe, e não `required-after:angelica`. A ausência de uma dependência formal em `mcmod.info` não elimina a dependência efetiva do coreplugin em UniMixins/GTNHMixins. Verificar os dois é necessário para entender a instalação.

O manifesto preliminar conserva `Lwjgl3ify-Aware: true`, herdado do upstream. Esse atributo não instala nem exige lwjgl3ify; não deve motivar uma troca do backend selecionado.

As versões acima vieram do log do **cliente**, não de um inventário dos JARs do servidor. Seus hashes não foram obtidos porque o ZIP do pack não pôde ser materializado nesta sessão. Não afirmar que o servidor já tem exatamente essas dependências sem conferir seus próprios arquivos e logs.

### Java do cliente e Java do servidor

O log examinado registra **Java 1.8.0_51 no cliente** e LWJGL 2.9.1. O usuário informa **Java 21 no Crucible**. São processos distintos. O dado do cliente é evidência histórica, não indicação de instalar Java 8u51 nem prova de que o cliente já esteja usando Java 21.

Mantenha o perfil de execução que já funciona na cópia de cada ambiente durante o primeiro comparativo. Registre o executável Java selecionado pelo launcher do cliente e pelo script do servidor. Não transfira argumentos de uma JVM para a outra nem acrescente opções de abertura de módulos ou memória sem um problema reproduzido que as justifique.

## 3. Onde o mod pode ser instalado

| Cliente | Servidor | Comportamento esperado deste caminho Forge |
|---|---|---|
| Mirai 0.1.0-dev | Sem Mirai | A verificação padrão admite a ausência do mod no servidor. O cliente pode construir/exibir LODs dos dados recebidos ou de seu cache compatível; não passa a conhecer automaticamente regiões nunca recebidas. |
| Mirai 0.1.0-dev | Mirai 0.1.0-dev | Instalação prevista para testar a troca de dados. Usar o mesmo artefato final nos dois lados e manter inicialmente a geração distante desligada. |
| Sem Mirai | Mirai 0.1.0-dev | A verificação padrão do Forge rejeita a ausência do mod no cliente. Instalar no servidor altera, portanto, o requisito de entrada dos jogadores. |
| Outro Distant Horizons/fork ou outra versão | Mirai 0.1.0-dev | Não considerar intercambiáveis. A checagem padrão compara o mod ID e a versão; igualdade desses campos também não comprova equivalência binária. |

O código não possui método ativo com `@NetworkCheckHandler` nem libera todas as versões remotas na anotação `@Mod`. A regra acima vem do `DefaultNetworkChecker` oficial do FML 1.7.10: quando o mod está ausente, aceita servidor remoto e rejeita cliente remoto; quando presente, aplica a comparação de versão. Essa conclusão de código ainda precisa ser reproduzida no Crucible real e em seu caminho de conexão.

**Não instalar Mirai junto com Distant Horizons ou outro fork que utilize o mesmo mod ID.** Foi preservado `distanthorizons`, assim como namespaces internos e nomes de configuração/dados. O rebrand não cria isolamento entre duas cópias do mesmo mod.

## 4. Preparação da cópia de teste

1. Pare normalmente cliente e servidor antes de copiar arquivos. Preserve uma cópia completa da instância do cliente e uma cópia separada do servidor, incluindo mundos, dimensões, `mods`, `config` e plugins/configurações do Crucible. O teste deve abrir essas cópias, não os diretórios de produção.
2. Preserve a configuração **`config/DistantHorizons.toml`**, se existir. Também preserve dados LOD: o banco continua chamado **`DistantHorizons.sqlite`**; dados de conexões somente cliente usam **`Distant_Horizons_server_data`**; mundos locais/dedicados usam a estrutura do respectivo save/dimensão, salvo override de API. A cópia integral evita depender de uma suposição sobre todos os caminhos utilizados pelo pack.
3. Com os processos encerrados, copie também quaisquer arquivos auxiliares presentes ao lado dos bancos SQLite. Não copie somente um banco aberto durante gravação.
4. Registre o resultado do pack **sem Mirai**: entrada, mundo, percurso curto, dimensões e encerramento. Guarde os logs desta execução como baseline.
5. Na cópia do cliente, retire o JAR DH anterior, se houver, e coloque apenas o JAR final Mirai em `mods`. Confira UniMixins e as dependências já exigidas pelo pack. Faça primeiro o teste cliente com o servidor de homologação ainda sem Mirai.
6. Somente após esse primeiro teste, instale o mesmo JAR no `mods` do Crucible de homologação, confira UniMixins nesse ambiente e reinicie-o com seu perfil Java 21. Garanta que os clientes usados nessa rodada tenham Mirai 0.1.0-dev.

O atualizador automático do upstream está bloqueado no fork. Atualizações desta linha devem ser instaladas manualmente a partir do pacote conferido; não habilitar uma atualização DH upstream esperando preservar os patches do Mirai.

## 5. Configuração inicial conservadora

Em uma configuração nova, **`common.worldGenerator.enableDistantGeneration` é falso**. Isso desliga a geração distante proativa; não desliga automaticamente a renderização de dados LOD disponíveis. Deixe esse recurso desligado nas primeiras rodadas.

Uma configuração DH antiga pode conter `true`. Confira o arquivo gerado, com o jogo/servidor parado, e edite a chave existente na seção correspondente, sem duplicar a seção TOML:

```toml
[common.worldGenerator]
enableDistantGeneration = false
```

O servidor **integrado** respeita sua configuração; o valor antigo `true` pode continuar ativo nele. O servidor **dedicado 1.7.10** desta build tem uma proteção adicional: sem o opt-in de inicialização, o valor efetivo é forçado a falso mesmo se o arquivo guardar `true`. Essa proteção usa override em memória e preserva o valor persistido. Um valor no arquivo, isoladamente, não prova o estado efetivo.

Comece com shaders desligados. Preserve LWJGL2 e as versões atuais do pack. Não aumente simultaneamente distância LOD, geração, threads e qualidade: é necessário saber qual alteração causou cada resultado. Nenhuma distância, reserva de heap ou quantidade de workers foi certificada para a GPU/CPU do usuário nesta sessão.

### Ensaio opcional da geração dedicada

Esta etapa continua **experimental e fora da aprovação de produção**. Execute-a apenas depois do smoke test, em mundo descartável/cópia e com acesso aos logs e ao encerramento normal do processo.

Para permitir esse ensaio, são necessárias as duas condições:

1. Adicionar **`-Dakashic.mirai.experimentalServerGeneration=true`** aos argumentos da JVM do **Crucible de teste**, antes de `-jar` quando seu script utiliza essa forma de inicialização.
2. Definir `common.worldGenerator.enableDistantGeneration = true` na configuração desse mesmo servidor, com ele parado, e reiniciá-lo.

A propriedade não ativa a configuração sozinha, não remove outras restrições de compatibilidade e não garante supressão de ticks. O backend legado seleciona `INTERNAL_SERVER`; não escolher outro gerador como tentativa de contornar esse limite.

Não ativar automaticamente `addSimulationDistance_WIP` do Hodgepodge. Seu diagnóstico distingue ausente, inativo e habilitado na configuração, mas a opção habilitada continua sem certificar comportamento no Crucible ou perto de jogadores. Não modificar essa opção durante tarefas de geração: a aquisição/liberação de supressão depende de uma configuração estável e do contrato do Hodgepodge.

Para encerrar o ensaio, pare o servidor, remova a propriedade experimental do script, restaure `enableDistantGeneration = false` e reinicie. Não deixar esse opt-in no comando de produção.

## 6. Smoke test e evidência mínima

A rodada curta sugerida dura aproximadamente **15–20 minutos** e serve para encontrar falhas imediatas. Esse tempo não certifica estabilidade prolongada.

| Etapa | Ação | Evidência essencial |
|---|---|---|
| Inicialização cliente | Abrir a cópia do pack com Mirai e shaders desligados. | Log identifica Mirai 0.1.0-dev; nenhuma falha de dependência/mixin atribuível ao novo JAR; Java, LWJGL, GPU/driver registrados. |
| Cliente sem Mirai no servidor | Entrar no servidor de homologação sem o mod; percorrer uma área e observar terreno distante disponível. | Entrada sem rejeição causada pela ausência do mod no servidor; captura das opções efetivas e imagem/vídeo curto do resultado. Terreno nunca recebido pode permanecer ausente. |
| Menu/configuração | Abrir e fechar opções Mirai; conferir geração desligada. | Ausência de exceção; título rebrand e configuração efetiva legíveis. Registrar valores, não só nomes de presets. |
| Lados e protocolo | Iniciar Crucible de teste com Mirai e conectar o cliente correspondente. Em teste separado, tentar cliente sem Mirai. | Cliente correspondente entra; ausência do mod no cliente é rejeitada de forma controlada. Guardar logs dos dois lados. |
| Ciclo de vida | Sair e reconectar; trocar entre duas dimensões já existentes; voltar à dimensão inicial. | Sem crash, espera permanente, mistura visível de dados entre dimensões ou erro contínuo no log. |
| Recursos cliente | Fazer um recarregamento de recursos pelo atalho do cliente, se suportado pela instância. | Renderização retorna; registrar duração e eventual erro. Isso não substitui ensaio repetido de recursos nativos. |
| Encerramento | Fechar cliente e parar normalmente o servidor de homologação. Reiniciar uma vez. | Processo encerra; configuração/cache reabrem sem erro novo. Guardar logs inclusive do encerramento. |

Interrompa a rodada diante de crash, corrupção visual persistente, desconexões repetidas, travamento prolongado ou exceções recorrentes. Preserve os logs e a cópia usada antes de alterar mods/configurações para tentar novamente.

Para relatar o resultado, envie:

- Nome e **SHA-256 final** do JAR Mirai em cada lado.
- Log completo do cliente e do Crucible desde a inicialização até a falha/encerramento; crash report ou `hs_err_pid` se tiver sido criado.
- Caminho/versão do Java de cada processo e identificação do JAR Crucible.
- Versões reais de UniMixins, Angelica, Hodgepodge e GTNHLib; configurações utilizadas e shaders ativos/desligados.
- Sequência exata que reproduz o problema, mundo/dimensão e horário aproximado. Não enviar somente a última linha da exceção.

Depois do smoke test, ainda ficam pendentes sessões prolongadas, pressão de memória, cancelamento durante geração, persistência após falha, shaders específicos e interação com plugins do Crucible. Somente a matriz de homologação preenchida com essas evidências poderá sustentar uma aprovação posterior.

## 7. Crash anterior do Sampler

O log anterior do pack registra **Sampler 1.60.1** e um crash associado a OpenGL. Esse diagnóstico antecede a execução desta build e **não foi corrigido nem homologado pelo Mirai**. Não atribuir sua repetição automaticamente ao novo mod e não declarar o problema resolvido porque a compilação passou.

Se reaparecer, compare a reprodução na cópia baseline sem Mirai e na cópia com Mirai, mantendo os demais parâmetros iguais. Preserve o relatório completo antes de alterar o conjunto de mods; qualquer teste com Sampler retirado deve ser outra cópia documentada.

## 8. Retorno à situação anterior

1. Encerre cliente e Crucible normalmente. Se houve falha, copie primeiro os logs, dumps e arquivos alterados da instância de teste.
2. Retire o JAR Mirai da cópia que será revertida; restaure o JAR anterior, quando existia, sem deixá-los juntos.
3. Restaure as configurações e os dados LOD correspondentes à cópia anterior. Compartilhar nome de arquivo não certifica leitura reversa de um cache alterado por uma build posterior.
4. Para um rollback completo do ensaio, restaure também os mundos/dimensões da mesma cópia inicial. Não tentar recuperar cache removendo os saves originais ou misturando arquivos de duas execuções.
5. Remova qualquer opt-in experimental do script de teste e repita a entrada baseline. A produção permanece nos arquivos anteriores durante toda esta avaliação.

## 9. Fontes, licenças e rastreabilidade

O Mirai deriva do Distant Horizons; nomes públicos e patches foram alterados, mas autoria upstream e avisos devem acompanhar a distribuição. O JAR preliminar continha **`LICENSE.txt`**, **`LICENSE.LESSER.txt`**, avisos de recursos e licenças de bibliotecas. A entrega final deve conservar esses textos e disponibilizar o código-fonte correspondente, os commits de base, patches e instruções de build. O rebrand não transforma o trabalho upstream em código de autoria exclusiva da Rede Akashic.

Referências de instalação e comportamento examinadas:

- Código local: `forge17/ForgeMain`, `DistantHorizonsLoadingPlugin`, `forge17/build.gradle`, `versionProperties/1.7.10.properties`, `ConfigHandler`, `AbstractModInitializer` e estruturas de save do core.
- [Verificação padrão de rede do FML 1.7.10](https://raw.githubusercontent.com/MinecraftForge/FML/1.7.10/src/main/java/cpw/mods/fml/common/network/internal/NetworkModHolder.java).
- [Fonte oficial do Crucible](https://github.com/CrucibleMC/Crucible).
- [Fonte Hodgepodge 2.7.189: contrato da supressão](https://github.com/GTNewHorizons/Hodgepodge/blob/2.7.189/src/main/java/com/mitchej123/hodgepodge/SimulationDistanceHelper.java).
- [Fonte Angelica 2.2.8](https://github.com/GTNewHorizons/Angelica/tree/2.2.8).
- Inventário local `docs/inventory-log-20260905.json`: evidência de log, sem hashes dos JARs do pack.

A estrutura preliminar examinada e as declarações de versão não substituem o manifesto, os hashes e a inspeção do JAR final. **Este documento orienta a homologação; não declara produção aprovada.**
