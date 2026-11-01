# Mirai 1.0-BETA — changelog

Primeira versão com o nome público **Mirai** e numeração estável. Modid `distanthorizons`, `DistantHorizons.toml`, API `7.1.0`
e protocolo `15` **não mudam**: configs, dados LOD e servidores 0.1.x continuam compatíveis.

**Status:** gates offline verdes (testes, jar reprodutível, ABI e detecção contra os jars reais do Angelica, gate GLSM).
**Certificação runtime pendente**: nada aqui rodou em Minecraft, Crucible ou GPU real (AOTM-01/AOTM-10).

## Identidade

* Nome público `Mirai`; jar `Mirai-forge17-1.0-BETA-1.7.10.jar` (fim da ambiguidade das duas 0.1.19, AOTM-09).
* Coremod renomeado para `com.seibel.distanthorizons.DistantHorizonsTweaker` (nome que o Angelica procura).
* `1.0-BETA` é build de release: `ModInfo.IS_DEV_BUILD=false`, como numa release do upstream. Isso desliga as validações de
  debug, o log de erro GL por padrão (`IGNORE`), o rastreio de wrappers e o subcomando `crash`.
* Jar **reprodutível** (sem timestamps, ordem estável): o mesmo commit gera o mesmo SHA-256 em qualquer máquina (AOTM-11).

## Angelica × Mirai

Auditoria GL contra Angelica 2.2.20 (pack) e 2.2.43 (atual). **Nenhuma chamada GL do Mirai escapa do GLSM**: as 171 referências
LWJGL do jar são redirecionadas, ou não mudam estado cacheado (debug output, sync objects, query results). As colisões reais
estavam na integração:

| Mudança | Efeito |
|---|---|
| `DistantHorizonsTweaker` (upstream `dfca6541`) | O Angelica detecta o Mirai sozinho; a ponte da AkashicThePatcher beta.42 se desarma (o fix de zip do Java 8 dela continua útil) |
| Passe translúcido adiado em `sortAndRender` (upstream `ef720c8e`), condicionado a `getDeferTransparentRendering` | Água distante com shaderpack; sem shader o caminho é idêntico. `require=0`: nunca impede o boot |
| Textura de profundidade nova criada antes de apagar a antiga (upstream `b8aad6e5`) | O Iris re-anexa por id; sem isso, após redimensionar a janela os framebuffers DH do Iris podiam ficar na textura apagada |
| `GLState` salva/restaura `GL_COLOR_WRITEMASK` | O programa de terreno forçava `TTTT` e não devolvia a máscara do chamador |
| Sem fade vanilla no passe de sombra do Iris | Evita compor cor no framebuffer de sombra de packs sem programas `dh_*` |
| Viewport também sem Angelica (upstream `6dbca398`) | Condição invertida |
| Cor da névoa por `glGetFloat(GL_FOG_COLOR)` | O GLSM responde do mesmo `fogState` (floats idênticos); `GLStateManager.getFogState()` sumiu no 2.2.40 |
| `getDepthTextureId` checa `IRenderTargetExt` | Sem crash com `enableIris=false` |

## Defeitos corrigidos (com causa raiz)

* **AOTM-03 — "Duplicate or excessive LOD transfer".** Com o renderer `DISABLED`, cada atualização parcial suprimida deixava sua
  transferência remontada no receptor; na 129ª (limite 128) a exceção **fechava a sessão de rede DH** até reconectar. Evidência: a
  sessão 2 tinha `QUIESCENT_PARTIAL_UPDATE_SUPPRESSED = 128` exatos. Agora: descarte na quiescência e no replay; respostas de
  pedidos cancelados/expirados são liberadas; o receptor despeja a transferência mais antiga (contador `NETWORK_TRANSFER_EVICTED`)
  em vez de derrubar a sessão; o timer de limpeza não morre com exceção.
* **AOTM-02 — `AssertFailureException` de bioma.** Biomas sem nome (mods que nunca chamam `setBiomeName`) faziam o índice de nomes
  lançar NPE (`ConcurrentHashMap.put(null, …)`), e o fallback de plains passava pelo mesmo índice. Mesma causa corrigida pelo
  upstream `67e242e4` (portado). Mais: lista copy-on-write, reconstrução do índice para biomas tardios, plains direto de
  `BiomeGenBase.plains`, falha não fica mais em cache como `EMPTY` para sempre.
* **EndlessIDs nativo:** o Mirai não chama mais `Chunk.getBiomeArray()` (nunca falhava na 1.7.10 e quebrava com EndlessIDs). O
  companion **Akashic-DH-EndlessIDs-Compat deixa de ser necessário**.
* **Servidor:** cliente rejeitado no handshake do FML não derruba mais o servidor (upstream `4e373c16`); o companion
  **Akashic-DH-Rejected-Logout-Guard deixa de ser necessário**. Troca para dimensão ainda não carregada sem NPE.
* **Scheduler:** *lost wakeup* no `PriorityTaskPicker` (tarefa enfileirada podia esperar a próxima submissão).
* **Pregen:** arquivo de progresso regravado após concluir (re-execuções não faziam nada); cobertura em anéis completos
  (lado `2·⌈r/4⌉+1`, o upstream deixava o anel externo pela metade); seção com timeout sai da fila; chat na thread do servidor;
  raio máximo 16384 chunks.

## Absorvido do upstream (cherry-picks adaptados)

Dados: downsample sem aliasing (corrompia IDs da fonte, `2c7c7864b`). Geração: tarefa que falha libera o slot (`64a225320`),
submissão atômica (`8338222de`/`bb1f0eaf6`, sem a atualização recursiva de `ConcurrentHashMap` do upstream), thread de progresso
encerrada (`2a3ace636` — cada parada de pregen vazava uma), tickets Forge antigos liberados (`9004a6f8`). Vazamentos/concorrência:
`PositionalLockProvider` invertido (`c45c13f67`), override injector sem lock na leitura (`4539c2cae`), world-generator injector
concorrente e desvinculado ao fechar (`dceafa453`), params estáticos de render e de world-gen (`10f7655c`), singleplayer fecha o
gerenciador de jogadores (`db7a34e4b`). Render: matriz identidade é válida — yaw 180/pitch 0 não pula mais o frame (`85c217699`).
Servidor: saúde = média < 25 ms e p99 < 50 ms do orçamento de 50 ms (`1581a2e6`), drenagem de 1 tarefa por tick (`6ab427c7`).

**Não absorvido de propósito:** protocolo 16 / API 7.3 / config v5 (quebrariam compatibilidade), mudanças visuais (reverse-Z,
AA/TAA, texturas, detalhe de LOD, vidro/transparência/neve — só com A/B), e o gate de saúde em servidor dedicado (`6758511e`),
que pausaria as threads de geração num pack pesado e travaria o `/dh pregen` (que já se limita pelo tick, 45 ms).

## Operação

* Logs de diagnóstico: `-Dakashic.mirai.logSegmentMiB` (1–64, padrão 4) e `-Dakashic.mirai.logArchives` (1–32, padrão **8** ≈
  36 MiB, antes 20 MiB) (AOTM-08).
* Chaves de rollback só desligam com `false` explícito (inclusive `runtimeGovernor`).

## Gates (CI)

`:core:test :api:test` (362) → `:forge17:shadeDowngradedApi` → `verify-mirai-jar.py` → SHA-256 publicado → `audit.py` contra
Angelica 2.2.20 + GTNHLib 0.11.51 + Hodgepodge 2.7.189 **e** Angelica 2.2.43 + GTNHLib 0.11.54 → `detection.py` → `gl_calls.py`.

## Antes de instalar

1. Backup do jar atual fora de `mods`; **um único** jar DH/Mirai em `mods` (regra 7).
2. Os companions EndlessIDs-Compat e Rejected-Logout-Guard podem sair do servidor (são inofensivos se ficarem: fail-closed).
3. Rodar os Gates A/B/C de `HOMOLOGATION-0.1.19.md` §9 com esta build e arquivar os logs.
